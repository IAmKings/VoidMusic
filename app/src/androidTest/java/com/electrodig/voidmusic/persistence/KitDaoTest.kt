package com.electrodig.voidmusic.persistence

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KitDaoTest {
    private lateinit var db: KitDatabase
    private lateinit var dao: KitDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, KitDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.kitDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertReadReplaceAndDelete() = runBlocking {
        val original = KitEntity("kit-a", "A", 1, "KICK:1")
        dao.upsert(original)
        assertEquals(original, dao.byId("kit-a"))

        val replacement = original.copy(name = "A2", displayOrder = 0)
        dao.upsert(replacement)
        assertEquals(listOf(replacement), dao.all())

        dao.delete(replacement)
        assertNull(dao.byId("kit-a"))
    }
}
