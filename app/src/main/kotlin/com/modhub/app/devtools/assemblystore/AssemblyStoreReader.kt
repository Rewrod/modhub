package com.modhub.app.devtools.assemblystore

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AssemblyStoreFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Port of `AssemblyStoreHashEntry`.
 *
 * - V1: 20 bytes: hash u64, mapping index u32, local store index u32, store id u32
 *   (the 32-bit index stores 32-bit hashes in a u64 field).
 * - V2: hash + mapping index u32; local store index = mapping index, store id = 0.
 */
class AssemblyStoreHashEntry(
    val is32Bit: Boolean,
    val hash: ULong,
    val mappingIndex: UInt,
    val localStoreIndex: UInt,
    val storeId: UInt,
)

/**
 * Port of `AssemblyStoreAssembly` (descriptor part; extraction lives here too since the whole
 * store is always kept in memory, i.e. the C# `keepStoreInMemory: true`).
 *
 * - V1 descriptor, 24 bytes: data offset, data size, debug offset, debug size, config offset,
 *   config size (all u32).
 * - V2 descriptor, 28 bytes: mapping index u32 + the same six u32 fields.
 */
class AssemblyStoreAssembly internal constructor(
    val store: AssemblyStoreReader,
    val dataOffset: Long,
    val dataSize: Long,
    val debugDataOffset: Long,
    val debugDataSize: Long,
    val configDataOffset: Long,
    val configDataSize: Long,
) {
    var hash32: UInt = 0u
    var hash64: ULong = 0uL
    var name: String = ""
    var runtimeIndex: UInt = 0u

    val dllName: String get() = makeFileName("dll")
    val pdbName: String get() = makeFileName("pdb")
    val configName: String get() = makeFileName("dll.config")

    /** The image as stored (possibly `XALZ` compressed). */
    fun rawImage(): ByteArray = store.slice(dataOffset, dataSize)

    /** The image, LZ4-decompressed when it carries an `XALZ` header (C# `ExtractImage(decompress: true)`). */
    fun extractImage(decompress: Boolean = true): ByteArray {
        val raw = rawImage()
        return if (decompress) Xalz.decompressIfNeeded(raw) else raw
    }

    fun extractDebugData(): ByteArray? =
        if (debugDataOffset == 0L || debugDataSize == 0L) null else store.slice(debugDataOffset, debugDataSize)

    fun extractConfig(): ByteArray? =
        if (configDataOffset == 0L || configDataSize == 0L) null else store.slice(configDataOffset, configDataSize)

    private fun makeFileName(extension: String): String {
        val base = name.ifEmpty { "${hash32.toString(16)}_${hash64.toString(16)}" }
        return "$base.$extension"
    }
}

/**
 * Port of `AssemblyStoreReader`. [store] must be a little-endian buffer positioned at the store
 * start (`XABA` magic); offsets in descriptors are relative to that start.
 *
 * Header (20 bytes): magic `XABA` (0x41424158), version (low byte = format version, high bits
 * are flags in v2, e.g. 0x80010002), local entry count, global entry count, then
 * - v1: store id
 * - v2: index size in bytes (store id is always 0: one store per ABI, with the global index).
 *
 * Layouts:
 * - v1: header -> local entries (24 B) -> [store 0 only] 32-bit index, 64-bit index (20 B each)
 * - v2: header -> hash index (globalEntryCount entries) -> local entries (28 B) -> name table
 *   (per local entry: u32 length + UTF-8 name, `.dll` stripped) -> data.
 *
 * Deviation from the C#: the v2 index entry stride is taken from the header's index size
 * (`indexByteSize / globalEntryCount`) when that divides evenly into 8..16 bytes, so 32-bit ABI
 * stores (u32 hash, 8 B entries) and layouts with trailing per-entry bytes also parse; the C#
 * always assumes 12 bytes (u64 hash + u32 index), which is what 64-bit stores use. The name table
 * skips names >= 512 bytes instead of desynchronising.
 */
class AssemblyStoreReader(storeBuffer: ByteBuffer, arch: String? = null) {
    private val data: ByteBuffer = storeBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)

    val arch: String = arch ?: ""
    var version: UInt = 0u; private set
    var rawVersion: UInt = 0u; private set
    var localEntryCount: UInt = 0u; private set
    var globalEntryCount: UInt = 0u; private set
    var storeId: UInt = 0u; private set
    var indexByteSize: UInt = 0u; private set
    val assemblies = ArrayList<AssemblyStoreAssembly>()
    val globalIndex32 = ArrayList<AssemblyStoreHashEntry>()
    val globalIndex64 = ArrayList<AssemblyStoreHashEntry>()

    val hasGlobalIndex: Boolean get() = storeId == 0u
    val size: Int get() = data.limit()

    constructor(bytes: ByteArray, arch: String? = null) : this(ByteBuffer.wrap(bytes), arch)

    init {
        val r = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        try {
            readHeader(r)
            if (version <= 1u) {
                // V1 layout: header -> local entries -> global index
                readLocalEntries(r)
                if (hasGlobalIndex) readGlobalIndex(r)
            } else {
                // V2 layout: header -> hash index -> local entries -> name table -> data
                if (hasGlobalIndex) readGlobalIndex(r)
                readLocalEntries(r)
                readNameTable(r)
            }
        } catch (e: BufferUnderflowException) {
            throw AssemblyStoreFormatException("Assembly store is truncated", e)
        } catch (e: IllegalArgumentException) {
            throw AssemblyStoreFormatException("Assembly store has out of range offsets", e)
        }
    }

    fun hasIdenticalContent(other: AssemblyStoreReader): Boolean =
        other.version == version &&
            other.localEntryCount == localEntryCount &&
            other.globalEntryCount == globalEntryCount &&
            other.storeId == storeId &&
            other.assemblies.size == assemblies.size &&
            other.globalIndex32.size == globalIndex32.size &&
            other.globalIndex64.size == globalIndex64.size

    internal fun slice(offset: Long, length: Long): ByteArray {
        if (offset < 0 || length < 0 || offset + length > data.limit()) {
            throw AssemblyStoreFormatException("Entry data [$offset, +$length) is outside the store (${data.limit()} bytes)")
        }
        val out = ByteArray(length.toInt())
        val d = data.duplicate()
        d.position(offset.toInt())
        d.get(out)
        return out
    }

    private fun ByteBuffer.u32(): UInt = int.toUInt()
    private fun ByteBuffer.u64(): ULong = long.toULong()

    private fun readHeader(r: ByteBuffer) {
        val magic = r.u32()
        if (magic != ASSEMBLY_STORE_MAGIC) throw AssemblyStoreFormatException("Invalid header magic number")

        rawVersion = r.u32()
        // V2 stores flags in the high bits, extract the base version number
        version = rawVersion and 0xFFu
        if (version == 0u) throw AssemblyStoreFormatException("Invalid version number: 0")
        if (version > ASSEMBLY_STORE_FORMAT_VERSION_V2) {
            throw AssemblyStoreFormatException(
                "Store format version $version is higher than the one understood by this reader, $ASSEMBLY_STORE_FORMAT_VERSION_V2",
            )
        }

        localEntryCount = r.u32()
        globalEntryCount = r.u32()
        if (version <= 1u) {
            storeId = r.u32()
        } else {
            // V2: 5th field is index byte size, not store ID
            indexByteSize = r.u32()
            storeId = 0u // V2 always has a single store with global index
        }
        // Sanity: each entry needs at least a few bytes; reject absurd counts before allocating.
        if (localEntryCount.toLong() > data.limit() / 4 || globalEntryCount.toLong() > data.limit() / 4) {
            throw AssemblyStoreFormatException("Entry counts ($localEntryCount/$globalEntryCount) exceed the store size")
        }
    }

    private fun readLocalEntries(r: ByteBuffer) {
        for (i in 0u until localEntryCount) {
            if (version > 1u) r.u32() // mapping index (unused, as in the C#)
            assemblies += AssemblyStoreAssembly(
                store = this,
                dataOffset = r.u32().toLong(),
                dataSize = r.u32().toLong(),
                debugDataOffset = r.u32().toLong(),
                debugDataSize = r.u32().toLong(),
                configDataOffset = r.u32().toLong(),
                configDataSize = r.u32().toLong(),
            )
        }
    }

    private fun readNameTable(r: ByteBuffer) {
        for (a in assemblies) {
            val nameLength = r.u32().toLong()
            if (nameLength == 0L) continue
            if (nameLength > r.remaining()) throw AssemblyStoreFormatException("Name table runs past the end of the store")
            val nameBytes = ByteArray(nameLength.toInt())
            r.get(nameBytes)
            if (nameLength < 512) {
                var name = String(nameBytes, Charsets.UTF_8)
                // Strip .dll extension if present for consistency with v1 naming
                if (name.endsWith(".dll", ignoreCase = true)) name = name.substring(0, name.length - 4)
                a.name = name
            }
        }
    }

    private fun readGlobalIndex(r: ByteBuffer) {
        if (version == 1u) {
            // V1: two separate indices (32-bit and 64-bit), 20 bytes per entry
            for (is32 in listOf(true, false)) {
                val index = if (is32) globalIndex32 else globalIndex64
                for (i in 0u until globalEntryCount) {
                    index += AssemblyStoreHashEntry(is32, hash = r.u64(), mappingIndex = r.u32(), localStoreIndex = r.u32(), storeId = r.u32())
                }
            }
        } else {
            // V2: single combined index (hash + mapping_index32)
            val stride = v2IndexStride()
            val start = r.position()
            for (i in 0u until globalEntryCount) {
                r.position(start + i.toInt() * stride)
                val hash = if (stride >= 12) r.u64() else r.u32().toULong()
                val mapping = r.u32()
                globalIndex64 += AssemblyStoreHashEntry(false, hash, mapping, localStoreIndex = mapping, storeId = 0u)
            }
            r.position(start + globalEntryCount.toInt() * stride)
        }
    }

    private fun v2IndexStride(): Int {
        val count = globalEntryCount.toLong()
        val bytes = indexByteSize.toLong()
        if (count > 0 && bytes > 0 && bytes % count == 0L) {
            val stride = (bytes / count).toInt()
            if (stride in 8..16) return stride
        }
        return V2_INDEX_ENTRY_SIZE_64
    }

    companion object {
        // Must be identical to the native ones in src/monodroid/jni/xamarin-app.hh
        const val ASSEMBLY_STORE_MAGIC: UInt = 0x41424158u // 'XABA', little-endian
        const val ASSEMBLY_STORE_FORMAT_VERSION_V1: UInt = 1u
        const val ASSEMBLY_STORE_FORMAT_VERSION_V2: UInt = 2u // .NET 9+ (with high bits as flags)
        const val V2_INDEX_ENTRY_SIZE_64 = 12
    }
}

/** `XALZ` container: magic, u32 descriptor index, i32 uncompressed length, LZ4 block. */
object Xalz {
    private val MAGIC = byteArrayOf('X'.code.toByte(), 'A'.code.toByte(), 'L'.code.toByte(), 'Z'.code.toByte())
    const val HEADER_SIZE = 12

    fun isCompressed(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == MAGIC[0] && data[1] == MAGIC[1] && data[2] == MAGIC[2] && data[3] == MAGIC[3]

    /** Port of `AssemblyStoreAssembly.DecompressDll`; returns [data] unchanged if not `XALZ`. */
    fun decompressIfNeeded(data: ByteArray): ByteArray {
        if (!isCompressed(data)) return data
        if (data.size < HEADER_SIZE) throw Lz4FormatException("truncated XALZ header")
        val unpackLength = ByteBuffer.wrap(data, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return Lz4Block.decompress(data, HEADER_SIZE, data.size - HEADER_SIZE, unpackLength)
    }
}
