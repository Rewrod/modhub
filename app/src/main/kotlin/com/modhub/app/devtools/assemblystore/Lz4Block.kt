package com.modhub.app.devtools.assemblystore

/**
 * Decoder for the raw LZ4 *block* format (no frame header), as produced by `LZ4_compress_default`
 * and read by K4os `LZ4Codec.Decode` in launcher/AssemblyStore. .NET for Android uses it for
 * `XALZ` compressed assemblies.
 *
 * Block = sequences of: token (hi nibble literal length, lo nibble match length - 4), optional
 * literal-length extension bytes (each added; 255 means "more follow"), literals, 2-byte
 * little-endian match offset, optional match-length extension bytes. The last sequence holds
 * literals only and ends the block.
 */
object Lz4Block {
    private const val MIN_MATCH = 4

    /**
     * Decodes `src[srcOff, srcOff + srcLen)` into `dst` starting at [dstOff], writing at most
     * [dstLen] bytes. Returns the number of bytes written; throws [Lz4FormatException] on
     * malformed or truncated input or when the output does not fit.
     */
    fun decompress(
        src: ByteArray,
        srcOff: Int,
        srcLen: Int,
        dst: ByteArray,
        dstOff: Int = 0,
        dstLen: Int = dst.size - dstOff,
    ): Int {
        require(srcOff >= 0 && srcLen >= 0 && srcOff + srcLen <= src.size) { "bad source range" }
        require(dstOff >= 0 && dstLen >= 0 && dstOff + dstLen <= dst.size) { "bad destination range" }
        var s = srcOff
        val sEnd = srcOff + srcLen
        var d = dstOff
        val dEnd = dstOff + dstLen
        if (srcLen == 0) throw Lz4FormatException("empty LZ4 block")

        while (true) {
            if (s >= sEnd) throw Lz4FormatException("truncated block (missing token)")
            val token = src[s++].toInt() and 0xFF

            // Literals.
            var literalLength = token ushr 4
            if (literalLength == 15) {
                while (true) {
                    if (s >= sEnd) throw Lz4FormatException("truncated literal length")
                    val b = src[s++].toInt() and 0xFF
                    literalLength += b
                    if (literalLength > srcLen) throw Lz4FormatException("literal length out of range")
                    if (b != 255) break
                }
            }
            if (literalLength > 0) {
                if (s + literalLength > sEnd) throw Lz4FormatException("literals run past end of block")
                if (d + literalLength > dEnd) throw Lz4FormatException("output buffer too small")
                System.arraycopy(src, s, dst, d, literalLength)
                s += literalLength
                d += literalLength
            }

            // The last sequence has literals only.
            if (s == sEnd) break

            // Match.
            if (s + 2 > sEnd) throw Lz4FormatException("truncated match offset")
            val offset = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
            s += 2
            if (offset == 0 || offset > d - dstOff) throw Lz4FormatException("invalid match offset $offset")

            var matchLength = token and 0x0F
            if (matchLength == 15) {
                while (true) {
                    if (s >= sEnd) throw Lz4FormatException("truncated match length")
                    val b = src[s++].toInt() and 0xFF
                    matchLength += b
                    if (matchLength > dstLen) throw Lz4FormatException("match length out of range")
                    if (b != 255) break
                }
            }
            matchLength += MIN_MATCH
            if (d + matchLength > dEnd) throw Lz4FormatException("output buffer too small")

            val m = d - offset
            if (offset >= matchLength) {
                System.arraycopy(dst, m, dst, d, matchLength)
            } else {
                // Overlapping copy (e.g. run-length): must go byte by byte, front to back.
                for (i in 0 until matchLength) dst[d + i] = dst[m + i]
            }
            d += matchLength
        }
        return d - dstOff
    }

    /** Decodes a whole block whose decompressed size is known exactly. */
    fun decompress(src: ByteArray, srcOff: Int, srcLen: Int, uncompressedSize: Int): ByteArray {
        if (uncompressedSize < 0) throw Lz4FormatException("negative uncompressed size $uncompressedSize")
        val out = ByteArray(uncompressedSize)
        val n = decompress(src, srcOff, srcLen, out, 0, uncompressedSize)
        if (n != uncompressedSize) {
            throw Lz4FormatException("decoded $n bytes, expected $uncompressedSize")
        }
        return out
    }
}

class Lz4FormatException(message: String) : RuntimeException(message)
