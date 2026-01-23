package io.github.crazycoder.copysettingpath

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.CopySettingPathBundle"

/**
 * Enum representing available path separator styles for the Copy Setting Path action.
 * Each separator defines how path components are joined in the copied result.
 *
 * @property separator The actual separator string used between path components.
 */
enum class PathSeparator(val separator: String) {
    PIPE(" | "),
    ARROW(" > "),
    UNICODE_ARROW(" → "),
    GUILLEMET(" » "),
    TRIANGLE(" ▸ ");

    @Nls
    override fun toString(): String = message("path.separator.${name.lowercase()}")

    companion object {
        private val bundle = DynamicBundle(PathSeparator::class.java, BUNDLE)

        /**
         * Set of all separator characters used across all separator styles.
         * Used for trimming trailing separators when checking for duplicates.
         */
        val allSeparatorChars: Set<Char> by lazy {
            entries.flatMap { it.separator.toList() }.toSet()
        }

        @Nls
        private fun message(@PropertyKey(resourceBundle = BUNDLE) key: String): String = bundle.getMessage(key)
    }
}
