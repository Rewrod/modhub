package com.modhub.app.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Where an installed mod came from (source/mod/file/version), so updates can be checked.
 * The files on disk remain the source of truth for *what* is installed; this table only
 * adds provenance the mods folder can't carry.
 */
@Entity(
    tableName = "installed_mods",
    primaryKeys = ["gameId", "modId"],
    indices = [Index("sourceId", "sourceModId")],
)
data class InstalledModEntity(
    val gameId: String,
    /** [com.modhub.core.model.InstalledMod.id] */
    val modId: String,
    val location: String,
    val name: String,
    val version: String?,
    val sourceId: String?,
    val sourceModId: String?,
    val sourceFileId: String?,
    val installedAt: Long,
)
