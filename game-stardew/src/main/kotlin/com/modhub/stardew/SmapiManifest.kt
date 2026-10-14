package com.modhub.stardew

import com.modhub.core.model.ModDependency
import com.modhub.core.model.ModManifest

/**
 * A SMAPI `manifest.json` (https://stardewvalleywiki.com/Modding:Modder_Guide/APIs/Manifest).
 *
 * Parsed with [TolerantJson] rather than kotlinx-serialization: SMAPI (Json.NET) accepts
 * comments, trailing commas and case-insensitive keys, and `Version` may be a string or an
 * object, so a hand-mapped reader is simpler and matches SMAPI's leniency.
 */
data class SmapiManifest(
    val uniqueId: String?,
    val name: String?,
    val version: String?,
    val author: String? = null,
    val description: String? = null,
    val minimumApiVersion: String? = null,
    val dependencies: List<Dependency> = emptyList(),
    val contentPackFor: ContentPackFor? = null,
    val updateKeys: List<String> = emptyList(),
    val entryDll: String? = null,
) {
    data class Dependency(val uniqueId: String, val isRequired: Boolean = true, val minimumVersion: String? = null)
    data class ContentPackFor(val uniqueId: String, val minimumVersion: String? = null)

    /** Maps to the core model. [fallbackId] is used when `UniqueID` is missing (usually the folder name). */
    fun toModManifest(fallbackId: String): ModManifest {
        val extra = buildMap {
            contentPackFor?.let {
                put(EXTRA_CONTENT_PACK_FOR, it.uniqueId)
                it.minimumVersion?.let { v -> put(EXTRA_CONTENT_PACK_FOR_MIN_VERSION, v) }
            }
            entryDll?.let { put(EXTRA_ENTRY_DLL, it) }
        }
        return ModManifest(
            uniqueId = uniqueId?.takeIf { it.isNotBlank() } ?: fallbackId,
            name = name?.takeIf { it.isNotBlank() } ?: uniqueId?.takeIf { it.isNotBlank() } ?: fallbackId,
            version = version.orEmpty(),
            author = author.orEmpty(),
            description = description.orEmpty(),
            minimumLoaderVersion = minimumApiVersion,
            dependencies = dependencies.map { ModDependency(it.uniqueId, it.minimumVersion, it.isRequired) },
            updateKeys = updateKeys,
            extra = extra,
        )
    }

    companion object {
        const val FILE_NAME = "manifest.json"
        const val EXTRA_CONTENT_PACK_FOR = "ContentPackFor"
        const val EXTRA_CONTENT_PACK_FOR_MIN_VERSION = "ContentPackFor.MinimumVersion"
        const val EXTRA_ENTRY_DLL = "EntryDll"

        /** Parses manifest text. Throws [IllegalArgumentException] if it is not a JSON object. */
        fun parse(text: String): SmapiManifest {
            val root = TolerantJson.parse(text) as? Map<*, *>
                ?: throw IllegalArgumentException("manifest.json is not a JSON object")
            val o = CiMap(root)
            return SmapiManifest(
                uniqueId = o.string("UniqueID")?.trim(),
                name = o.string("Name"),
                version = versionOf(o["Version"]),
                author = o.string("Author"),
                description = o.string("Description"),
                minimumApiVersion = versionOf(o["MinimumApiVersion"]),
                dependencies = (o["Dependencies"] as? List<*>).orEmpty().mapNotNull { dep ->
                    val d = (dep as? Map<*, *>)?.let(::CiMap) ?: return@mapNotNull null
                    val id = d.string("UniqueID")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Dependency(id, d.bool("IsRequired") ?: true, versionOf(d["MinimumVersion"]))
                },
                contentPackFor = (o["ContentPackFor"] as? Map<*, *>)?.let(::CiMap)?.let { c ->
                    c.string("UniqueID")?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { ContentPackFor(it, versionOf(c["MinimumVersion"])) }
                },
                updateKeys = (o["UpdateKeys"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.ifEmpty { null } },
                entryDll = o.string("EntryDll"),
            )
        }

        /** Returns null instead of throwing. */
        fun parseOrNull(text: String): SmapiManifest? = runCatching { parse(text) }.getOrNull()

        /** `"1.2.3"`, a number, or `{MajorVersion, MinorVersion, PatchVersion, Build}` -> string. */
        internal fun versionOf(v: Any?): String? = when (v) {
            null -> null
            is String -> v.trim().ifEmpty { null }
            is Long -> v.toString()
            is Double -> v.toString()
            is Map<*, *> -> {
                val m = CiMap(v)
                val major = m.long("MajorVersion") ?: 0
                val minor = m.long("MinorVersion") ?: 0
                val patch = m.long("PatchVersion") ?: 0
                val build = m["Build"]?.let { (it as? String) ?: (it as? Long)?.toString() }?.trim()?.ifEmpty { null }
                "$major.$minor.$patch" + (build?.let { "-$it" } ?: "")
            }
            else -> null
        }
    }

    /** Case-insensitive key lookup, like Json.NET's default deserialisation. */
    private class CiMap(src: Map<*, *>) {
        private val map: Map<String, Any?> = src.entries.associate { (k, v) -> k.toString().lowercase() to v }
        operator fun get(key: String): Any? = map[key.lowercase()]
        fun string(key: String): String? = when (val v = get(key)) {
            is String -> v
            is Long, is Double, is Boolean -> v.toString()
            else -> null
        }
        fun bool(key: String): Boolean? = when (val v = get(key)) {
            is Boolean -> v
            is String -> v.trim().lowercase().toBooleanStrictOrNull()
            else -> null
        }
        fun long(key: String): Long? = when (val v = get(key)) {
            is Long -> v
            is Double -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    }
}
