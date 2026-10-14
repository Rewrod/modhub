package com.modhub.app.devtools.assemblystore

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Port of `AssemblyStoreExplorer.ExtractElfPayload` (launcher/AssemblyStore): .NET 9+ wraps the
 * assembly store in an ELF shared object (`lib/<abi>/libassemblies.<abi>.blob.so`) whose
 * `payload` section is the store blob.
 *
 * Same logic as the C#: little-endian ELF64 only (ELF32 returns null), section headers at
 * `e_shoff` (0x28) with `e_shentsize`/`e_shnum`/`e_shstrndx` at 0x3A/0x3C/0x3E, section name
 * offset at +0x00 of each header, `sh_offset` at +0x18, `sh_size` at +0x20. Differences: out of
 * range offsets return null instead of throwing IndexOutOfRange.
 */
object ElfPayload {
    const val SECTION_NAME = "payload"

    /** Offset and size of the `payload` section inside the ELF file. */
    data class Section(val offset: Int, val size: Int)

    fun findPayloadSection(data: ByteArray): Section? {
        // Verify ELF magic
        if (data.size < 64 || data[0] != 0x7F.toByte() || data[1] != 'E'.code.toByte() ||
            data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte()
        ) return null

        val is64 = data[4].toInt() == 2
        if (!is64) return null // Only 64-bit ELF supported

        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        // Parse ELF64 header
        val eShentsize = b.getShort(0x3A).toInt() and 0xFFFF
        val eShnum = b.getShort(0x3C).toInt() and 0xFFFF
        val eShstrndx = b.getShort(0x3E).toInt() and 0xFFFF
        val eShoff = b.getLong(0x28)

        fun u64At(pos: Long): Long? =
            if (pos < 0 || pos + 8 > data.size) null else b.getLong(pos.toInt())

        // Read section name string table
        val strHeader = eShoff + eShstrndx.toLong() * eShentsize
        val shstrOffset = u64At(strHeader + 0x18) ?: return null
        u64At(strHeader + 0x20) ?: return null // shstrSize: read but unused, as in the C#

        // Find 'payload' section
        for (i in 0 until eShnum) {
            val entryOffset = eShoff + i.toLong() * eShentsize
            if (entryOffset < 0 || entryOffset + 0x28 > data.size) return null
            val nameIdx = b.getInt(entryOffset.toInt()).toLong() and 0xFFFFFFFFL

            // Read section name from string table
            val nameStart = shstrOffset + nameIdx
            if (nameStart < 0 || nameStart > data.size) continue
            var nameEnd = nameStart.toInt()
            while (nameEnd < data.size && data[nameEnd].toInt() != 0) nameEnd++
            val name = String(data, nameStart.toInt(), nameEnd - nameStart.toInt(), Charsets.US_ASCII)
            if (name != SECTION_NAME) continue

            val sectionOffset = b.getLong((entryOffset + 0x18).toInt())
            val sectionSize = b.getLong((entryOffset + 0x20).toInt())
            if (sectionOffset < 0 || sectionSize < 0 || sectionOffset + sectionSize > data.size) return null
            return Section(sectionOffset.toInt(), sectionSize.toInt())
        }
        return null
    }

    /** The payload as a little-endian buffer sharing [data] (no copy), or null. */
    fun extract(data: ByteArray): ByteBuffer? {
        val s = findPayloadSection(data) ?: return null
        return ByteBuffer.wrap(data, s.offset, s.size).slice().order(ByteOrder.LITTLE_ENDIAN)
    }
}
