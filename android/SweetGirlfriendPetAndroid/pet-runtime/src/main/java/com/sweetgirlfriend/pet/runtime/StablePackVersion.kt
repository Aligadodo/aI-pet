package com.sweetgirlfriend.pet.runtime

/** Strict stable PetPack version: exactly three non-negative Int components. */
class StablePackVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<StablePackVersion> {
    override fun compareTo(other: StablePackVersion): Int =
        major.compareTo(other.major).takeIf { it != 0 }
            ?: minor.compareTo(other.minor).takeIf { it != 0 }
            ?: patch.compareTo(other.patch)

    override fun toString(): String = "$major.$minor.$patch"

    override fun equals(other: Any?): Boolean =
        other is StablePackVersion && major == other.major && minor == other.minor && patch == other.patch

    override fun hashCode(): Int = 31 * (31 * major + minor) + patch

    companion object {
        private val STABLE_VERSION = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

        fun parseOrNull(value: String): StablePackVersion? {
            val match = STABLE_VERSION.matchEntire(value) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return StablePackVersion(major, minor, patch)
        }

        fun parseRequired(value: String, label: String = "Pack version"): StablePackVersion =
            requireNotNull(parseOrNull(value)) {
                "$label must be a stable numeric x.y.z version without suffixes: $value"
            }
    }
}

/** PetPack v2 protocol identifiers use exactly `2.<numeric-minor>`. */
object PetPackProtocolVersion {
    private val SUPPORTED_V2 = Regex("2\\.[0-9]+")

    fun isSupported(value: String): Boolean = SUPPORTED_V2.matches(value)
}
