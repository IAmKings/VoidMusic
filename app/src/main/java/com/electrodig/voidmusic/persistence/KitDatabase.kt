package com.electrodig.voidmusic.persistence

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.electrodig.voidmusic.audio.BuiltInKits
import com.electrodig.voidmusic.audio.Kit

/** Persisted metadata for selectable built-in kits; audio PCM stays in res/raw. */
@Entity(tableName = "kit_metadata")
data class KitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val displayOrder: Int,
    /** Stable diagnostic representation of the pad → bundled sample mapping. */
    val sampleSignature: String
)

@Dao
interface KitDao {
    @Query("SELECT * FROM kit_metadata ORDER BY displayOrder ASC")
    suspend fun all(): List<KitEntity>

    @Query("SELECT * FROM kit_metadata WHERE id = :id")
    suspend fun byId(id: String): KitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(kit: KitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(kits: List<KitEntity>)

    @Delete
    suspend fun delete(kit: KitEntity)
}

@Database(entities = [KitEntity::class], version = 1, exportSchema = false)
abstract class KitDatabase : RoomDatabase() {
    abstract fun kitDao(): KitDao
}

/** Owns database construction and the initial built-in Kit metadata seed. */
class KitRepository(context: Context) {
    private val db = Room.databaseBuilder(context, KitDatabase::class.java, DATABASE_NAME).build()
    private val dao = db.kitDao()

    suspend fun seedBuiltIns() {
        dao.upsertAll(BuiltInKits.all.mapIndexed(::toEntity))
    }

    suspend fun all(): List<KitEntity> = dao.all()
    suspend fun byId(id: String): KitEntity? = dao.byId(id)
    suspend fun upsert(kit: KitEntity) = dao.upsert(kit)
    suspend fun delete(kit: KitEntity) = dao.delete(kit)

    private fun toEntity(index: Int, kit: Kit): KitEntity = KitEntity(
        id = kit.id,
        name = kit.name,
        displayOrder = index,
        sampleSignature = kit.samples.entries
            .sortedBy { it.key.ordinal }
            .joinToString(separator = ";") { (pad, sample) -> "${pad.name}:${sample.rawResId}" }
    )

    private companion object { const val DATABASE_NAME = "ods_kits.db" }
}
