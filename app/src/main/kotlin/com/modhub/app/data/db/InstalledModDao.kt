package com.modhub.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledModDao {
    @Query("SELECT * FROM installed_mods WHERE gameId = :gameId ORDER BY name COLLATE NOCASE")
    fun observe(gameId: String): Flow<List<InstalledModEntity>>

    @Query("SELECT * FROM installed_mods WHERE gameId = :gameId AND modId = :modId")
    suspend fun get(gameId: String, modId: String): InstalledModEntity?

    @Upsert
    suspend fun upsert(entity: InstalledModEntity)

    @Query("DELETE FROM installed_mods WHERE gameId = :gameId AND modId = :modId")
    suspend fun delete(gameId: String, modId: String)
}
