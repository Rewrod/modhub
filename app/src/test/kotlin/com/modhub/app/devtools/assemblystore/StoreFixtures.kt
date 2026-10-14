package com.modhub.app.devtools.assemblystore

import java.io.ByteArrayOutputStream

/** Little-endian byte builder for synthetic stores / ELF files. */
class Le {
    private val out = ByteArrayOutputStream()
    val size: Int get() = out.size()
    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun u32(v: Long) = apply { for (i in 0 until 4) u8((v ushr (8 * i)).toInt()) }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) = apply { for (i in 0 until 8) u8((v ushr (8 * i)).toInt()) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun zeros(n: Int) = apply { repeat(n) { u8(0) } }
    fun padTo(n: Int) = apply { while (size < n) u8(0) }
    fun build(): ByteArray = out.toByteArray()
}

object StoreFixtures {
    const val MAGIC = 0x41424158L

    /** Fake image: an "MZ" file whose bytes mention its own name (enough for the regex fallback). */
    fun fakeDll(name: String, filler: Int = 64): ByteArray =
        "MZ".toByteArray() + ByteArray(filler) { (it * 7).toByte() } + "\u0000$name.dll\u0000".toByteArray()

    /** XALZ container around [Lz4Literal.encode] of [data]. */
    fun xalz(data: ByteArray, descriptorIndex: Int = 0): ByteArray =
        Le().bytes("XALZ".toByteArray()).u32(descriptorIndex).u32(data.size).bytes(Lz4Literal.encode(data)).build()

    /**
     * .NET 9 (v2) store: header (raw version 0x80010002), hash index with [indexStride]-byte
     * entries (u64 hash + u32 index, or u32 hash + u32 index when stride is 8, zero padded
     * beyond), 28-byte descriptors, name table, data.
     */
    fun v2Store(assemblies: List<Pair<String, ByteArray>>, indexStride: Int = 12): ByteArray {
        val lc = assemblies.size
        val gc = lc * 2 // like the real thing: name with and without ".dll"
        val nameTable = Le().apply {
            assemblies.forEach { (n, _) -> val b = "$n.dll".toByteArray(); u32(b.size); bytes(b) }
        }.build()
        var dataOffset = 20 + gc * indexStride + lc * 28 + nameTable.size
        val header = Le().u32(MAGIC).u32(0x80010002L).u32(lc).u32(gc).u32(gc * indexStride)
        val index = Le()
        for (i in 0 until gc) {
            val start = index.size
            val hash = 0x1000_0000_0000_0000L + i
            if (indexStride >= 12) index.u64(hash) else index.u32(hash.toInt())
            index.u32(i % lc)
            index.padTo(start + indexStride)
        }
        val descriptors = Le()
        val data = Le()
        assemblies.forEachIndexed { i, (_, bytes) ->
            descriptors.u32(i).u32(dataOffset).u32(bytes.size).u32(0).u32(0).u32(0).u32(0)
            data.bytes(bytes)
            dataOffset += bytes.size
        }
        return header.bytes(index.build()).bytes(descriptors.build()).bytes(nameTable).bytes(data.build()).build()
    }

    class V1Entry(val name: String, val data: ByteArray, val hash32: Long, val hash64: Long, val storeId: Int, val localIndex: Int)

    /**
     * Xamarin (v1) store with id [storeId]: 20-byte header, 24-byte descriptors, then (store 0
     * only) the 32-bit and 64-bit global indices (20 bytes per entry) for [globalIndex], then data.
     */
    fun v1Store(storeId: Int, local: List<ByteArray>, globalIndex: List<V1Entry>): ByteArray {
        val gc = globalIndex.size
        val header = Le().u32(MAGIC).u32(1).u32(local.size).u32(gc).u32(storeId)
        val indexBytes = if (storeId == 0) gc * 20 * 2 else 0
        var dataOffset = 20 + local.size * 24 + indexBytes
        val descriptors = Le()
        val data = Le()
        local.forEach { bytes ->
            descriptors.u32(dataOffset).u32(bytes.size).u32(0).u32(0).u32(0).u32(0)
            data.bytes(bytes)
            dataOffset += bytes.size
        }
        val index = Le()
        if (storeId == 0) {
            globalIndex.forEachIndexed { i, e -> index.u64(e.hash32).u32(i).u32(e.localIndex).u32(e.storeId) }
            globalIndex.forEachIndexed { i, e -> index.u64(e.hash64).u32(i).u32(e.localIndex).u32(e.storeId) }
        }
        return header.bytes(descriptors.build()).bytes(index.build()).bytes(data.build()).build()
    }

    fun v1Manifest(entries: List<V1Entry>): ByteArray = buildString {
        append("Hash 32     Hash 64             Blob ID  Blob idx  Name\r\n")
        entries.forEach { e ->
            append("0x%08x  0x%016x  %03d  %04d  %s\r\n".format(e.hash32, e.hash64, e.storeId, e.localIndex, e.name))
        }
    }.toByteArray()

    /**
     * Minimal little-endian ELF64: header, `.shstrtab`, a `.data` filler section and a `payload`
     * section (page aligned like the real libassemblies.*.blob.so), section headers at the end.
     */
    fun elf64(payload: ByteArray, sectionName: String = "payload"): ByteArray {
        val shstr = "\u0000.shstrtab\u0000.data\u0000$sectionName\u0000".toByteArray()
        val nameShstrtab = 1
        val nameData = nameShstrtab + ".shstrtab".length + 1
        val namePayload = nameData + ".data".length + 1
        val shstrOff = 64
        val dataOff = shstrOff + shstr.size
        val payloadOff = 0x1000
        val shoff = (payloadOff + payload.size + 7) / 8 * 8
        val b = Le()
            .bytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0)).zeros(8)
            .u16(3).u16(0xB7).u32(1).u64(0).u64(0).u64(shoff.toLong()) // type DYN, machine AArch64, version, entry, phoff, shoff
            .u32(0).u16(64).u16(56).u16(0).u16(64).u16(4).u16(1) // flags, ehsize, phentsize, phnum, shentsize, shnum, shstrndx
        check(b.size == 64)
        b.bytes(shstr).bytes(byteArrayOf(0x2A)).padTo(payloadOff).bytes(payload).padTo(shoff)
        fun sh(name: Int, type: Int, off: Int, size: Int) {
            b.u32(name).u32(type).u64(0).u64(0).u64(off.toLong()).u64(size.toLong()).u32(0).u32(0).u64(1).u64(0)
        }
        sh(0, 0, 0, 0)
        sh(nameShstrtab, 3, shstrOff, shstr.size)
        sh(nameData, 1, dataOff, 1)
        sh(namePayload, 1, payloadOff, payload.size)
        return b.build()
    }
}

/**
 * Trivial valid LZ4 block encoder (one literal-only sequence) so fixtures can carry XALZ data
 * without a compressor; real compressed vectors are in [Lz4BlockTest].
 */
object Lz4Literal {
    fun encode(data: ByteArray): ByteArray {
        val b = Le()
        val n = data.size
        if (n < 15) {
            b.u8(n shl 4)
        } else {
            b.u8(0xF0)
            var rest = n - 15
            while (rest >= 255) { b.u8(255); rest -= 255 }
            b.u8(rest)
        }
        return b.bytes(data).build()
    }
}
