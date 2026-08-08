package com.sweetgirlfriend.pet.content

/** Limits untrusted manifest labels before any UI, Toast, or upload response can display them. */
internal object PetPackMetadataPolicy {
    const val MAX_NAME_LENGTH = 80
    const val MAX_AUTHOR_LENGTH = 120

    fun requireName(value: String): String = requireSafeDisplayText(
        value = value,
        label = "name",
        maxLength = MAX_NAME_LENGTH,
        allowBlank = false,
    )

    fun requireAuthor(value: String): String = requireSafeDisplayText(
        value = value,
        label = "author",
        maxLength = MAX_AUTHOR_LENGTH,
        allowBlank = false,
    )

    private fun requireSafeDisplayText(
        value: String,
        label: String,
        maxLength: Int,
        allowBlank: Boolean,
    ): String {
        require((allowBlank || value.isNotBlank()) && value.length <= maxLength) {
            "Pack $label must contain 1..$maxLength characters"
        }
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            require(
                !Character.isISOControl(codePoint) &&
                    Character.getType(codePoint) != Character.FORMAT.toInt(),
            ) { "Pack $label contains control or bidirectional formatting characters" }
            offset += Character.charCount(codePoint)
        }
        return value
    }
}
