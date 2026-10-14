package com.modhub.stardew

/**
 * SMAPI-style semantic versions: `major[.minor[.patch[.platformRelease]]][-prerelease][+build]`,
 * optionally prefixed with `v`. Pre-release versions sort before the release
 * (`1.0.0-beta < 1.0.0`); pre-release labels compare per dot-separated part, numbers numerically.
 * Build metadata (`+...`) is ignored.
 */
data class SmapiVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val platformRelease: Int = 0,
    val prerelease: String? = null,
) : Comparable<SmapiVersion> {

    override fun compareTo(other: SmapiVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        compareValues(platformRelease, other.platformRelease).let { if (it != 0) return it }
        return comparePrerelease(prerelease, other.prerelease)
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        if (platformRelease != 0) append(".$platformRelease")
        prerelease?.let { append('-').append(it) }
    }

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?(?:\+[0-9A-Za-z.\-]*)?$""")

        fun parse(text: String?): SmapiVersion? {
            val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            fun g(i: Int) = m.groupValues[i].takeIf { it.isNotEmpty() }?.toIntOrNull()
            return SmapiVersion(
                major = g(1) ?: return null,
                minor = g(2) ?: 0,
                patch = g(3) ?: 0,
                platformRelease = g(4) ?: 0,
                prerelease = m.groupValues[5].takeIf { it.isNotEmpty() },
            )
        }

        /**
         * Compares two version strings. Unparseable versions sort before parseable ones and
         * are compared case-insensitively among themselves; null sorts first.
         */
        fun compare(a: String?, b: String?): Int {
            val va = parse(a)
            val vb = parse(b)
            return when {
                va != null && vb != null -> va.compareTo(vb)
                va != null -> 1
                vb != null -> -1
                else -> compareValues(a?.lowercase(), b?.lowercase())
            }
        }

        /** True if [installed] satisfies [minimum] (no minimum means anything satisfies it). */
        fun isAtLeast(installed: String?, minimum: String?): Boolean {
            if (minimum.isNullOrBlank()) return true
            val min = parse(minimum) ?: return true // can't judge: don't raise a false alarm
            val inst = parse(installed) ?: return false
            return inst >= min
        }

        val COMPARATOR: Comparator<String?> = Comparator { a, b -> compare(a, b) }

        private fun comparePrerelease(a: String?, b: String?): Int {
            if (a == b) return 0
            if (a == null) return 1 // release > prerelease
            if (b == null) return -1
            val pa = a.split('.', '-')
            val pb = b.split('.', '-')
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(i) ?: return -1
                val y = pb.getOrNull(i) ?: return 1
                val nx = x.toIntOrNull()
                val ny = y.toIntOrNull()
                val c = when {
                    nx != null && ny != null -> nx.compareTo(ny)
                    nx != null -> -1 // numeric identifiers sort before alphanumeric
                    ny != null -> 1
                    else -> x.compareTo(y, ignoreCase = true)
                }
                if (c != 0) return c
            }
            return 0
        }
    }
}
