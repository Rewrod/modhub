package com.modhub.app.devtools.assemblystore

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class Lz4BlockTest {

    /** Same input as the vector below: built here byte for byte. */
    private fun vectorSource(): ByteArray =
        "Stardew Valley assembly store test vector. ".repeat(6).toByteArray() +
            ByteArray(256) { it.toByte() } +
            ByteArray(300) { 'A'.code.toByte() } +
            "xyz".repeat(40).toByteArray() +
            "tail-literals!".toByteArray()

    /**
     * Produced by the reference implementation (liblz4 1.9.4, `LZ4_compress_default`, called
     * through Python ctypes) from [vectorSource]: 948 bytes -> 335 bytes. Exercises literal
     * length extension, long overlapping matches (offset 1 and 3) and a literal-only tail.
     */
    private val liblz4Vector =
        "ff1c537461726465772056616c6c657920617373656d626c792073746f7265207465737420766563746f722e202b00c4fff4" +
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f30" +
            "3132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f6061" +
            "62636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192" +
            "939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3" +
            "c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4" +
            "f5f6f7f8f9fafbfcfdfeff4141410300ff173f78797a030062e07461696c2d6c69746572616c7321"

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun decodesReferenceVectorFromLiblz4() {
        val compressed = hex(liblz4Vector)
        assertEquals(335, compressed.size)
        val expected = vectorSource()
        assertEquals(948, expected.size)
        assertContentEquals(expected, Lz4Block.decompress(compressed, 0, compressed.size, expected.size))
    }

    @Test
    fun decodesHandBuiltBlock() {
        val lits = "0123456789ABCDEFGHIJ".toByteArray() // 20 literals: 15 + extension 5
        val block = Le()
            // seq 1: 20 literals, match offset 1 length 4+15+3 = 22 (overlapping run of 'J')
            .u8(0xFF).u8(5).bytes(lits).u16(1).u8(3)
            // seq 2: 3 literals, match offset 45 length 4 -> "0123"
            .u8(0x30).bytes("xyz".toByteArray()).u16(45)
            // seq 3 (last): 5 literals only
            .u8(0x50).bytes("END!!".toByteArray())
            .build()
        val expected = (String(lits) + "J".repeat(22) + "xyz" + "0123" + "END!!").toByteArray()
        assertContentEquals(expected, Lz4Block.decompress(block, 0, block.size, expected.size))
    }

    @Test
    fun decodesMultiByteLiteralLengthAndOffsetSlices() {
        val data = ByteArray(15 + 255 + 10) { (it % 251).toByte() } // needs extension bytes 255, 10
        val block = Lz4Literal.encode(data)
        assertEquals(0xF0, block[0].toInt() and 0xFF)
        assertEquals(255, block[1].toInt() and 0xFF)
        assertEquals(10, block[2].toInt() and 0xFF)
        // Decode from inside a larger buffer into an offset destination.
        val src = byteArrayOf(9, 9) + block + byteArrayOf(9)
        val dst = ByteArray(data.size + 4)
        val n = Lz4Block.decompress(src, 2, block.size, dst, 3, data.size)
        assertEquals(data.size, n)
        assertContentEquals(data, dst.copyOfRange(3, 3 + data.size))
    }

    @Test
    fun rejectsMalformedBlocks() {
        // Offset 0.
        assertFailsWith<Lz4FormatException> {
            val b = Le().u8(0x10).u8('a'.code).u16(0).u8(0x10).u8('b'.code).build()
            Lz4Block.decompress(b, 0, b.size, 16)
        }
        // Offset reaching before the start of the output.
        assertFailsWith<Lz4FormatException> {
            val b = Le().u8(0x10).u8('a'.code).u16(2).u8(0x10).u8('b'.code).build()
            Lz4Block.decompress(b, 0, b.size, 16)
        }
        // Literals past the end of input.
        assertFailsWith<Lz4FormatException> {
            val b = Le().u8(0x50).bytes("abc".toByteArray()).build()
            Lz4Block.decompress(b, 0, b.size, 5)
        }
        // Output larger than declared.
        assertFailsWith<Lz4FormatException> {
            val b = Lz4Literal.encode("abcdef".toByteArray())
            Lz4Block.decompress(b, 0, b.size, 4)
        }
        // Output smaller than declared.
        assertFailsWith<Lz4FormatException> {
            val b = Lz4Literal.encode("abcdef".toByteArray())
            Lz4Block.decompress(b, 0, b.size, 7)
        }
        // Truncated match offset.
        assertFailsWith<Lz4FormatException> {
            val b = Le().u8(0x14).u8('a'.code).u8(1).build()
            Lz4Block.decompress(b, 0, b.size, 16)
        }
    }

    @Test
    fun xalzContainerIsDecompressedAndPlainDataPassesThrough() {
        val plain = vectorSource()
        val compressed = hex(liblz4Vector)
        val xalz = Le().bytes("XALZ".toByteArray()).u32(7).u32(plain.size).bytes(compressed).build()
        assertContentEquals(plain, Xalz.decompressIfNeeded(xalz))

        val dll = StoreFixtures.fakeDll("Foo")
        assertSame(dll, Xalz.decompressIfNeeded(dll))
    }
}
