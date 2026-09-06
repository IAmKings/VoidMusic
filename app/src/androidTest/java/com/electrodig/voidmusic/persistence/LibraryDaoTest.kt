package com.electrodig.voidmusic.persistence

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDaoTest {
    private lateinit var db: KitDatabase
    private lateinit var dao: KitDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            KitDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.kitDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun completeKitRoundTripsAndCatalogueIsObservable() = runBlocking {
        val kit = kit("kit-a")
        val asset = asset("asset-a", 'a')
        val mapping = KitPadMappingEntity(kit.id, "KICK", asset.id)

        dao.insertCompleteKit(kit, listOf(asset), listOf(mapping))

        assertEquals(listOf(kit), dao.allKits())
        assertEquals(listOf(kit), dao.observeAllKits().first())
        assertEquals(asset, dao.assetById(asset.id))
        assertEquals(asset, dao.assetBySha256(asset.sha256))
        assertEquals(listOf(mapping), dao.mappingsForKit(kit.id))
        assertEquals(1, dao.assetReferenceCount(asset.id))
    }

    @Test
    fun roomLibraryMergesBuiltInsBeforeCustomKits() = runBlocking {
        dao.insertKit(kit("kit-a"))
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "room-library-catalogue"
        )
        val store = AudioAssetStore(root)
        val library = RoomKitLibrary(
            database = db,
            assetStore = store,
            audioImporter = AudioImporter(store),
            builtInSampleSource = BuiltInSampleSource { error("Not used by catalogue") }
        )

        val catalogue = library.kits.first()

        assertEquals(listOf("default", "electro", "kit-a"), catalogue.map { it.id })
        assertEquals(listOf(true, true, false), catalogue.map { it.isBuiltIn })
    }

    @Test
    fun deletingKitCascadesMappingsButPreservesAsset() = runBlocking {
        val kit = kit("kit-a")
        val asset = asset("asset-a", 'a')
        dao.insertCompleteKit(
            kit,
            listOf(asset),
            listOf(KitPadMappingEntity(kit.id, "KICK", asset.id))
        )

        dao.deleteKitById(kit.id)

        assertNull(dao.kitById(kit.id))
        assertEquals(emptyList<KitPadMappingEntity>(), dao.mappingsForKit(kit.id))
        assertEquals(asset, dao.assetById(asset.id))
    }

    @Test
    fun referencedAssetCannotBeDeleted() = runBlocking {
        val kit = kit("kit-a")
        val asset = asset("asset-a", 'a')
        dao.insertCompleteKit(
            kit,
            listOf(asset),
            listOf(KitPadMappingEntity(kit.id, "KICK", asset.id))
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.deleteAssetById(asset.id) }
        }
        assertEquals(asset, dao.assetById(asset.id))
    }

    @Test
    fun failedMappingRollsBackWholeCompleteKitTransaction() = runBlocking {
        val kit = kit("kit-a")
        val asset = asset("asset-a", 'a')
        val missingAssetMapping = KitPadMappingEntity(kit.id, "KICK", "missing")

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insertCompleteKit(kit, listOf(asset), listOf(missingAssetMapping)) }
        }

        assertNull(dao.kitById(kit.id))
        assertNull(dao.assetById(asset.id))
    }

    @Test
    fun assetHashAndStorageKeyAreUnique() = runBlocking {
        dao.insertAsset(asset("asset-a", 'a'))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insertAsset(asset("asset-b", 'a').copy(storageKey = "b".repeat(64) + ".wav")) }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insertAsset(asset("asset-c", 'c').copy(storageKey = "a".repeat(64) + ".wav")) }
        }
    }

    private fun kit(id: String) = LibraryKitEntity(
        id = id,
        name = "Custom kit",
        displayOrder = 0,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private fun asset(id: String, hashCharacter: Char) = AudioAssetEntity(
        id = id,
        storageKey = hashCharacter.toString().repeat(64) + ".wav",
        originalName = "sample.wav",
        sha256 = hashCharacter.toString().repeat(64),
        byteSize = 128L,
        frameCount = 32L,
        sampleRate = 48_000,
        channelCount = 1,
        encoding = "PCM_16",
        storageVersion = 1,
        validationVersion = 1,
        status = AudioAssetStatuses.READY,
        createdAtMs = 1L,
        updatedAtMs = 1L
    )
}
