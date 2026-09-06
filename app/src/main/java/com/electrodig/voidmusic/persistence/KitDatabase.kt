package com.electrodig.voidmusic.persistence

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A user-created kit. Built-in kits remain code-owned and are not seeded here. */
@Entity(tableName = "library_kits")
data class LibraryKitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val displayOrder: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/** Metadata for one normalized WAV stored under filesDir/audio-assets. */
@Entity(
    tableName = "audio_assets",
    indices = [
        Index(value = ["storageKey"], unique = true),
        Index(value = ["sha256"], unique = true)
    ]
)
data class AudioAssetEntity(
    @PrimaryKey val id: String,
    val storageKey: String,
    val originalName: String,
    val sha256: String,
    val byteSize: Long,
    val frameCount: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: String,
    val storageVersion: Int,
    val validationVersion: Int,
    val status: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/** Stable string values stored by [AudioAssetEntity.status]. */
object AudioAssetStatuses {
    const val READY = "READY"
    const val BROKEN = "BROKEN"
    const val PENDING_DELETE = "PENDING_DELETE"

    val all: Set<String> = setOf(READY, BROKEN, PENDING_DELETE)
}

/** One complete kit has exactly one mapping for each stable drum-pad name. */
@Entity(
    tableName = "kit_pad_mappings",
    primaryKeys = ["kitId", "pad"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryKitEntity::class,
            parentColumns = ["id"],
            childColumns = ["kitId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AudioAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["audioAssetId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("kitId"), Index("audioAssetId")]
)
data class KitPadMappingEntity(
    val kitId: String,
    val pad: String,
    val audioAssetId: String
)

@Dao
interface KitDao {
    @Query("SELECT * FROM library_kits ORDER BY displayOrder ASC, id ASC")
    fun observeAllKits(): Flow<List<LibraryKitEntity>>

    @Query("SELECT * FROM library_kits ORDER BY displayOrder ASC, id ASC")
    suspend fun allKits(): List<LibraryKitEntity>

    @Query("SELECT * FROM library_kits WHERE id = :id")
    suspend fun kitById(id: String): LibraryKitEntity?

    @Query("SELECT * FROM audio_assets WHERE id = :id")
    suspend fun assetById(id: String): AudioAssetEntity?

    @Query("SELECT * FROM audio_assets WHERE sha256 = :sha256")
    suspend fun assetBySha256(sha256: String): AudioAssetEntity?

    @Query("SELECT * FROM kit_pad_mappings WHERE kitId = :kitId ORDER BY pad ASC")
    suspend fun mappingsForKit(kitId: String): List<KitPadMappingEntity>

    @Query("SELECT COUNT(*) FROM kit_pad_mappings WHERE audioAssetId = :assetId")
    suspend fun assetReferenceCount(assetId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertKit(kit: LibraryKitEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAsset(asset: AudioAssetEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMappings(mappings: List<KitPadMappingEntity>)

    @Query("DELETE FROM library_kits WHERE id = :kitId")
    suspend fun deleteKitById(kitId: String)

    @Query("DELETE FROM audio_assets WHERE id = :assetId")
    suspend fun deleteAssetById(assetId: String)

    @Transaction
    suspend fun insertCompleteKit(
        kit: LibraryKitEntity,
        assets: List<AudioAssetEntity>,
        mappings: List<KitPadMappingEntity>
    ) {
        insertKit(kit)
        assets.forEach { insertAsset(it) }
        insertMappings(mappings)
    }
}

@Database(
    entities = [LibraryKitEntity::class, AudioAssetEntity::class, KitPadMappingEntity::class],
    version = 1,
    exportSchema = true
)
abstract class KitDatabase : RoomDatabase() {
    abstract fun kitDao(): KitDao

    companion object {
        const val DATABASE_NAME = "void_music_library.db"

        fun open(context: Context): KitDatabase = Room.databaseBuilder(
            context.applicationContext,
            KitDatabase::class.java,
            DATABASE_NAME
        ).build()
    }
}
