package com.modhub.app.devtools.assemblystore

/**
 * Port of `AssemblyStoreManifestEntry`: one line of `assemblies.manifest`.
 * Fields: Hash 32 | Hash 64 | Store ID | Store idx | Name (hex values, optional `0x` prefix;
 * unparsable numbers become 0, as with the C# `TryParse`).
 */
class AssemblyStoreManifestEntry(fields: List<String>) {
    val hash32: UInt
    val hash64: ULong
    val storeId: UInt
    val indexInStore: UInt
    val name: String

    init {
        require(fields.size == NUMBER_OF_FIELDS) { "Invalid number of fields" }
        hash32 = prepHex(fields[0]).toUIntOrNull(16) ?: 0u
        hash64 = prepHex(fields[1]).toULongOrNull(16) ?: 0uL
        storeId = prepHex(fields[2]).toUIntOrNull(16) ?: 0u
        indexInStore = prepHex(fields[3]).toUIntOrNull(16) ?: 0u
        name = fields[4].trim()
    }

    private fun prepHex(value: String) = if (value.startsWith("0x")) value.substring(2) else value

    companion object {
        const val NUMBER_OF_FIELDS = 5
    }
}

/**
 * Port of `AssemblyStoreManifestReader`. The first line is a header and is ignored; other lines
 * are fields separated by any number of spaces. Differences from the C#: blank lines and lines
 * with the wrong field count are skipped (the C# throws), duplicate hashes keep the first entry.
 */
class AssemblyStoreManifestReader(text: String) {
    val entries = ArrayList<AssemblyStoreManifestEntry>()
    val entriesByHash32 = HashMap<UInt, AssemblyStoreManifestEntry>()
    val entriesByHash64 = HashMap<ULong, AssemblyStoreManifestEntry>()

    constructor(bytes: ByteArray) : this(String(bytes, Charsets.UTF_8))

    init {
        text.lineSequence().drop(1).forEach { line ->
            val fields = line.split(' ').filter { it.isNotEmpty() }
            if (fields.size != AssemblyStoreManifestEntry.NUMBER_OF_FIELDS) return@forEach
            val entry = AssemblyStoreManifestEntry(fields)
            entries += entry
            if (entry.hash32 != 0u) entriesByHash32.putIfAbsent(entry.hash32, entry)
            if (entry.hash64 != 0uL) entriesByHash64.putIfAbsent(entry.hash64, entry)
        }
    }
}
