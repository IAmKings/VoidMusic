package com.electrodig.voidmusic.persistence

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.electrodig.voidmusic.audio.AudioSampleSource
import com.electrodig.voidmusic.audio.LibraryErrorCode
import com.electrodig.voidmusic.audio.LibraryResult
import com.electrodig.voidmusic.audio.SoundPoolDrumEngine
import com.electrodig.voidmusic.detection.color.DrumPad
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomKitLibraryMutationTest {
    private lateinit var database: KitDatabase
    private lateinit var dao: KitDao
    private lateinit var root: File
    private lateinit var assetStore: AudioAssetStore
    private var idCounter = 0
    private val now = 2_000_000_000_000L

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, KitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.kitDao()
        root = File(context.cacheDir, "kit-library-${System.nanoTime()}")
        assetStore = AudioAssetStore(root)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun copyBuiltInCreatesCompleteKitAndDeduplicatesEqualPadContent() = runBlocking {
        val library = library()

        val kitId = library.copyKit("default", "我的套鼓").successValue()
        val mappings = dao.mappingsForKit(kitId)

        assertEquals(DrumPad.entries.map { it.name }.toSet(), mappings.map { it.pad }.toSet())
        assertEquals(1, mappings.map { it.audioAssetId }.distinct().size)
        assertEquals(1, dao.allAssets().size)
        assertEquals(1, assetStore.assetStorageKeys().size)
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun customCopySharesAssetsAndDeletesOnlyAfterLastKit() = runBlocking {
        val library = library()
        val first = library.copyKit("default", "第一套").successValue()
        val second = library.copyKit(first, "第二套").successValue()
        val sharedAsset = dao.allAssets().single()

        assertEquals(10, dao.assetReferenceCount(sharedAsset.id))

        val firstDelete = library.deleteKit(first).successValue()
        assertEquals(0, firstDelete.deletedAssetCount)
        assertNotNull(dao.assetById(sharedAsset.id))
        assertTrue(assetStore.assetExists(sharedAsset.storageKey))
        assertEquals(5, dao.assetReferenceCount(sharedAsset.id))

        val secondDelete = library.deleteKit(second).successValue()
        assertEquals(1, secondDelete.deletedAssetCount)
        assertNull(dao.assetById(sharedAsset.id))
        assertFalse(assetStore.assetExists(sharedAsset.storageKey))
    }

    @Test
    fun replacePadDeduplicatesAndInvalidInputPreservesOriginalMapping() = runBlocking {
        val library = library()
        val kitId = library.copyKit("default", "替换测试").successValue()
        val originalKick = requireNotNull(dao.mappingForPad(kitId, DrumPad.KICK.name))

        val reused = library.replacePad(
            kitId = kitId,
            pad = DrumPad.KICK,
            originalName = "same.wav"
        ) { builtInWav().inputStream() }.successValue()
        assertTrue(reused.reusedExistingAsset)
        assertEquals(originalKick, dao.mappingForPad(kitId, DrumPad.KICK.name))
        assertEquals(1, dao.allAssets().size)

        val failure = library.replacePad(
            kitId = kitId,
            pad = DrumPad.KICK,
            originalName = "broken.wav"
        ) { ByteArray(16).inputStream() }
        assertEquals(LibraryErrorCode.IMPORT_FAILED, failure.failureCode())
        assertEquals(originalKick, dao.mappingForPad(kitId, DrumPad.KICK.name))
        assertEquals(1, dao.allAssets().size)
        assertEquals(1, assetStore.assetStorageKeys().size)
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun replacePadWithNewContentCommitsMappingAndDeletesOldExclusiveAsset() = runBlocking {
        val library = library(
            sampleSource = BuiltInSampleSource { resourceId ->
                wav(shortArrayOf(0, (resourceId and 0x7fff).toShort(), 0)).inputStream()
            }
        )
        val kitId = library.copyKit("default", "独占资产").successValue()
        val oldMapping = requireNotNull(dao.mappingForPad(kitId, DrumPad.KICK.name))
        val oldAsset = requireNotNull(dao.assetById(oldMapping.audioAssetId))

        val report = library.replacePad(
            kitId = kitId,
            pad = DrumPad.KICK,
            originalName = "new-kick.wav"
        ) { differentWav().inputStream() }.successValue()
        val newMapping = requireNotNull(dao.mappingForPad(kitId, DrumPad.KICK.name))

        assertFalse(report.reusedExistingAsset)
        assertEquals(0, report.pendingCleanupCount)
        assertTrue(newMapping.audioAssetId != oldMapping.audioAssetId)
        assertNull(dao.assetById(oldAsset.id))
        assertFalse(assetStore.assetExists(oldAsset.storageKey))
        assertEquals(5, dao.allAssets().size)
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun databaseFailureCompensatesNewFileAndPreservesMapping() = runBlocking {
        val ids = ArrayDeque(listOf("kit-a", "asset-a", "asset-a"))
        val library = library(idSource = { ids.removeFirst() })
        val kitId = library.copyKit("default", "事务补偿").successValue()
        val original = requireNotNull(dao.mappingForPad(kitId, DrumPad.SNARE.name))

        val result = library.replacePad(
            kitId = kitId,
            pad = DrumPad.SNARE,
            originalName = "different.wav"
        ) { differentWav().inputStream() }

        assertEquals(LibraryErrorCode.DATABASE_FAILURE, result.failureCode())
        assertEquals(original, dao.mappingForPad(kitId, DrumPad.SNARE.name))
        assertEquals(1, dao.allAssets().size)
        assertEquals(1, assetStore.assetStorageKeys().size)
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun finalMoveFailurePreservesMappingAndCleansPreparedStaging() = runBlocking {
        val library = library()
        val kitId = library.copyKit("default", "移动失败").successValue()
        val original = requireNotNull(dao.mappingForPad(kitId, DrumPad.TOM.name))
        val probe = AudioImporter(assetStore).prepare { differentWav().inputStream() }
        val blockedTarget = File(root, "audio-assets/${probe.storageKey}")
        assertTrue(assetStore.discardStaging(probe.stagingFile))
        assertTrue(blockedTarget.mkdir())

        val result = library.replacePad(
            kitId = kitId,
            pad = DrumPad.TOM,
            originalName = "blocked.wav"
        ) { differentWav().inputStream() }

        assertEquals(LibraryErrorCode.STORAGE_FAILURE, result.failureCode())
        assertEquals(original, dao.mappingForPad(kitId, DrumPad.TOM.name))
        assertEquals(1, dao.allAssets().size)
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun reconcileCleansStagingPendingAndOrphansAndMarksMissingReadyAssetBroken() = runBlocking {
        val library = library()
        val kitId = library.copyKit("default", "协调测试").successValue()
        val ready = requireNotNull(
            dao.assetById(requireNotNull(dao.mappingForPad(kitId, DrumPad.KICK.name)).audioAssetId)
        )
        assertTrue(assetStore.deleteAsset(ready.storageKey))

        val pendingKey = "e".repeat(64) + ".wav"
        assetStore.commit(
            assetStore.createStagingFile().apply { writeBytes(builtInWav()) },
            pendingKey
        )
        val pending = asset("pending", pendingKey, "e".repeat(64), AudioAssetStatuses.PENDING_DELETE)
        dao.insertAsset(pending)

        val orphanKey = "f".repeat(64) + ".wav"
        assetStore.commit(
            assetStore.createStagingFile().apply { writeBytes(builtInWav()) },
            orphanKey
        )
        val oldStaging = assetStore.createStagingFile().apply {
            writeText("old")
            setLastModified(now - STAGING_MAX_AGE_MS - 1)
        }
        val recentStaging = assetStore.createStagingFile().apply {
            writeText("recent")
            setLastModified(now)
        }

        val report = library.reconcile().successValue()

        assertEquals(1, report.deletedStagingCount)
        assertEquals(1, report.deletedPendingAssetCount)
        assertEquals(1, report.brokenAssetCount)
        assertEquals(1, report.deletedOrphanFileCount)
        assertFalse(oldStaging.exists())
        assertTrue(recentStaging.exists())
        assertNull(dao.assetById(pending.id))
        assertEquals(AudioAssetStatuses.BROKEN, dao.assetById(ready.id)?.status)
        assertFalse(assetStore.assetExists(orphanKey))
    }

    @Test
    fun builtInKitsCannotBeRenamedDeletedOrDirectlyReplaced() = runBlocking {
        val library = library()

        assertEquals(
            LibraryErrorCode.BUILT_IN_IMMUTABLE,
            library.renameKit("default", "x").failureCode()
        )
        assertEquals(
            LibraryErrorCode.BUILT_IN_IMMUTABLE,
            library.deleteKit("default").failureCode()
        )
        assertEquals(
            LibraryErrorCode.BUILT_IN_IMMUTABLE,
            library.replacePad("default", DrumPad.KICK, "x.wav") {
                builtInWav().inputStream()
            }.failureCode()
        )
    }

    @Test
    fun renameTrimsValidNamesAndRejectsBlankOrOverlongNames() = runBlocking {
        val library = library()
        val kitId = library.copyKit("default", "初始名称").successValue()

        assertEquals(Unit, library.renameKit(kitId, "  新名称  ").successValue())
        assertEquals("新名称", dao.kitById(kitId)?.name)
        assertEquals(LibraryErrorCode.INVALID_NAME, library.renameKit(kitId, "  ").failureCode())
        assertEquals(
            LibraryErrorCode.INVALID_NAME,
            library.renameKit(kitId, "x".repeat(41)).failureCode()
        )
        assertEquals("新名称", dao.kitById(kitId)?.name)
    }

    @Test
    fun prepareBuiltInAndImportedKitsProducesCompleteNormalizedPcm() = runBlocking {
        val library = library()
        val customId = library.copyKit("default", "可播放音色").successValue()

        val builtIn = library.prepare("default").successValue()
        val cachedBuiltIn = library.prepare("default").successValue()
        val imported = library.prepare(customId).successValue()
        val cachedImported = library.prepare(customId).successValue()

        assertEquals(48_000, builtIn.sampleRate)
        assertEquals(48_000, imported.sampleRate)
        assertEquals(DrumPad.entries.toSet(), builtIn.samples.keys)
        assertEquals(DrumPad.entries.toSet(), imported.samples.keys)
        assertTrue(builtIn.samples.values.all { it.source is AudioSampleSource.BuiltIn })
        assertTrue(imported.samples.values.all { it.source is AudioSampleSource.Imported })
        assertTrue(imported.samples.values.all { it.pcm.isNotEmpty() })
        assertSame(builtIn, cachedBuiltIn)
        assertSame(imported, cachedImported)
        assertEquals(1, imported.samples.values.toSet().size)
    }

    @Test
    fun customPreparationCacheIsInvalidatedByRenameAndPadReplacement() = runBlocking {
        val library = library()
        val customId = library.copyKit("default", "缓存音色").successValue()
        val initial = library.prepare(customId).successValue()

        library.renameKit(customId, "更新名称").successValue()
        val renamed = library.prepare(customId).successValue()
        assertTrue(initial !== renamed)
        assertEquals("更新名称", renamed.name)

        library.replacePad(customId, DrumPad.KICK, "kick.wav") {
            differentWav().inputStream()
        }.successValue()
        val replaced = library.prepare(customId).successValue()

        assertTrue(renamed !== replaced)
        assertTrue(
            !renamed.samples.getValue(DrumPad.KICK).pcm.contentEquals(
                replaced.samples.getValue(DrumPad.KICK).pcm
            )
        )
    }

    @Test
    fun missingImportedAssetCannotPrepareAndIsMarkedBroken() = runBlocking {
        val library = library()
        val customId = library.copyKit("default", "损坏音色").successValue()
        val mapping = requireNotNull(dao.mappingForPad(customId, DrumPad.KICK.name))
        val asset = requireNotNull(dao.assetById(mapping.audioAssetId))
        assertTrue(assetStore.deleteAsset(asset.storageKey))

        val result = library.prepare(customId)

        assertEquals(LibraryErrorCode.SOURCE_UNAVAILABLE, result.failureCode())
        assertEquals(AudioAssetStatuses.BROKEN, dao.assetById(asset.id)?.status)
    }

    @Test
    fun soundPoolLoadsAndTriggersAnImportedPreparedKit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = library()
        val customId = library.copyKit("default", "SoundPool 音色").successValue()
        val prepared = library.prepare(customId).successValue()
        val engine = SoundPoolDrumEngine(context, prepared, loadTimeoutMs = 5_000L)

        try {
            assertTrue(engine.start())
            DrumPad.entries.forEach { engine.trigger(it, 1f) }
        } finally {
            engine.stop()
        }
    }

    private fun library(
        idSource: () -> String = { "id-${++idCounter}" },
        sampleSource: BuiltInSampleSource = BuiltInSampleSource { builtInWav().inputStream() }
    ) = RoomKitLibrary(
        database = database,
        assetStore = assetStore,
        audioImporter = AudioImporter(assetStore),
        builtInSampleSource = sampleSource,
        nowMs = { now },
        newId = idSource
    )

    private fun builtInWav(): ByteArray = wav(shortArrayOf(0, 1_000, -1_000, 0))

    private fun differentWav(): ByteArray = wav(shortArrayOf(0, 2_000, -2_000, 0))

    private fun wav(samples: ShortArray): ByteArray {
        val dataSize = samples.size * 2
        return ByteArray(44 + dataSize).apply {
            putAscii(0, "RIFF")
            putU32(4, size - 8)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putU32(16, 16)
            putU16(20, 1)
            putU16(22, 1)
            putU32(24, 48_000)
            putU32(28, 96_000)
            putU16(32, 2)
            putU16(34, 16)
            putAscii(36, "data")
            putU32(40, dataSize)
            samples.forEachIndexed { index, sample -> putU16(44 + index * 2, sample.toInt()) }
        }
    }

    private fun asset(id: String, storageKey: String, hash: String, status: String) = AudioAssetEntity(
        id = id,
        storageKey = storageKey,
        originalName = "$id.wav",
        sha256 = hash,
        byteSize = 52,
        frameCount = 4,
        sampleRate = 48_000,
        channelCount = 1,
        encoding = "PCM_16",
        storageVersion = 1,
        validationVersion = 1,
        status = status,
        createdAtMs = now,
        updatedAtMs = now
    )

    private fun stagingFiles(): List<File> = File(root, "audio-import-staging")
        .listFiles()
        ?.toList()
        .orEmpty()

    private fun ByteArray.putAscii(offset: Int, value: String) {
        value.forEachIndexed { index, character -> this[offset + index] = character.code.toByte() }
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Int) {
        putU16(offset, value)
        putU16(offset + 2, value ushr 16)
    }

    private fun <T> LibraryResult<T>.successValue(): T = when (this) {
        is LibraryResult.Success -> value
        is LibraryResult.Failure -> error("Expected success, got ${error.code}")
    }

    private fun LibraryResult<*>.failureCode(): LibraryErrorCode = when (this) {
        is LibraryResult.Success -> error("Expected failure")
        is LibraryResult.Failure -> error.code
    }

    private companion object {
        const val STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}
