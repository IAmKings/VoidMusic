package com.electrodig.voidmusic.persistence

import com.electrodig.voidmusic.audio.BuiltInKits
import com.electrodig.voidmusic.audio.KitLibrary
import com.electrodig.voidmusic.audio.KitSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Merges code-owned built-in kits with Room-owned custom kits. */
class RoomKitLibrary(dao: KitDao) : KitLibrary {
    private val builtIns = BuiltInKits.all.map { kit ->
        KitSummary(id = kit.id, name = kit.name, isBuiltIn = true)
    }

    override val kits: Flow<List<KitSummary>> = dao.observeAllKits().map { imported ->
        builtIns + imported.map { kit ->
            KitSummary(id = kit.id, name = kit.name, isBuiltIn = false)
        }
    }
}
