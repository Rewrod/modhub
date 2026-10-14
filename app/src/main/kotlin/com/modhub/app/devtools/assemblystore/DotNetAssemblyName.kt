package com.modhub.app.devtools.assemblystore

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Names an assembly image when the store has no name for it (v1 store without manifest).
 *
 * Reads the ECMA-335 Module table's first row (`Name` column) from the PE metadata; the Module
 * table is always table 0, so no other table sizes are needed. Falls back to the C#
 * `TryGetAssemblyNameFromDll` heuristic (first `Name.dll` string in the file).
 */
object DotNetAssemblyName {
    private val DLL_NAME_REGEX = Regex("([A-Za-z][A-Za-z0-9_.]+)\\.dll")

    /** Assembly name without `.dll`, or null when [image] is not a recognisable PE. */
    fun derive(image: ByteArray): String? {
        if (image.size < 4 || image[0] != 'M'.code.toByte() || image[1] != 'Z'.code.toByte()) return null
        moduleName(image)?.let { return it.removeSuffix(".dll").removeSuffix(".DLL").removeSuffix(".exe") }
        val text = String(image, Charsets.ISO_8859_1)
        return DLL_NAME_REGEX.find(text)?.groupValues?.get(1)
    }

    /** The Module table name, e.g. `StardewValley.dll`. */
    fun moduleName(image: ByteArray): String? = try {
        readModuleName(ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN), image)
    } catch (e: RuntimeException) {
        null
    }

    private fun readModuleName(b: ByteBuffer, image: ByteArray): String? {
        val pe = b.getInt(0x3C)
        if (b.getInt(pe) != 0x00004550) return null // "PE\0\0"
        val coff = pe + 4
        val numberOfSections = b.getShort(coff + 2).toInt() and 0xFFFF
        val sizeOfOptionalHeader = b.getShort(coff + 16).toInt() and 0xFFFF
        val opt = coff + 20
        val dataDirectories = when (b.getShort(opt).toInt() and 0xFFFF) {
            0x10B -> opt + 96 // PE32
            0x20B -> opt + 112 // PE32+
            else -> return null
        }
        val cliRva = b.getInt(dataDirectories + 14 * 8)
        if (cliRva == 0) return null
        val sections = opt + sizeOfOptionalHeader

        fun rvaToOffset(rva: Int): Int? {
            for (i in 0 until numberOfSections) {
                val s = sections + i * 40
                val virtualSize = b.getInt(s + 8)
                val virtualAddress = b.getInt(s + 12)
                val rawSize = b.getInt(s + 16)
                val rawPointer = b.getInt(s + 20)
                val span = maxOf(virtualSize, rawSize)
                if (rva >= virtualAddress && rva < virtualAddress + span) return rva - virtualAddress + rawPointer
            }
            return null
        }

        val cli = rvaToOffset(cliRva) ?: return null
        val metadata = rvaToOffset(b.getInt(cli + 8)) ?: return null
        if (b.getInt(metadata) != 0x424A5342) return null // "BSJB"
        val versionLength = b.getInt(metadata + 12)
        var p = metadata + 16 + versionLength
        p += 2 // flags
        val streamCount = b.getShort(p).toInt() and 0xFFFF
        p += 2

        var tables = -1
        var strings = -1
        repeat(streamCount) {
            val offset = b.getInt(p)
            p += 8
            var end = p
            while (image[end].toInt() != 0) end++
            val name = String(image, p, end - p, Charsets.US_ASCII)
            p = (end + 1 + 3) and 3.inv()
            when (name) {
                "#~", "#-" -> tables = metadata + offset
                "#Strings" -> strings = metadata + offset
            }
        }
        if (tables < 0 || strings < 0) return null

        val heapSizes = image[tables + 6].toInt() and 0xFF
        val valid = b.getLong(tables + 8)
        if (valid and 1L == 0L) return null // no Module table
        var rows = tables + 24 + java.lang.Long.bitCount(valid) * 4
        if (heapSizes and 0x40 != 0) rows += 4 // extra data (#- / EnC)
        // Module row: Generation (u16), Name (#Strings index), ...
        val nameIndex = if (heapSizes and 0x01 != 0) b.getInt(rows + 2) else b.getShort(rows + 2).toInt() and 0xFFFF
        val start = strings + nameIndex
        var end = start
        while (image[end].toInt() != 0) end++
        return String(image, start, end - start, Charsets.UTF_8).ifEmpty { null }
    }
}
