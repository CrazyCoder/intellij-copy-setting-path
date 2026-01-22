package com.intellij.plugin.copySettingPath

import com.intellij.openapi.diagnostic.Logger

/**
 * Core utilities and constants for the Copy Setting Path plugin.
 *
 * This file contains:
 * - Logger instance
 * - Path-related constants
 * - Cached regex patterns
 * - Layout constants (magic numbers extracted)
 * - Common string manipulation functions
 */

/** Logger instance for the Copy Setting Path plugin. */
val LOG: Logger = Logger.getInstance("#com.intellij.plugin.CopySettingPath")

/**
 * Constants used throughout the plugin for path construction and reflection.
 */
object PathConstants {
    /** Separator used between path components. */
    const val SEPARATOR = " | "

    /** Prefix for Settings dialog paths. */
    const val SETTINGS_PREFIX = "Settings"

    /** Placeholder separator in Project Structure that should be ignored. */
    const val IGNORED_SEPARATOR = "--"

    // Class names used for reflection (to avoid direct dependencies)
    const val SETTINGS_EDITOR_CLASS = "com.intellij.openapi.options.newEditor.SettingsEditor"
    const val ABSTRACT_EDITOR_CLASS = "com.intellij.openapi.options.newEditor.AbstractEditor"
    const val CONFIGURABLE_EDITOR_CLASS = "com.intellij.openapi.options.newEditor.ConfigurableEditor"
    const val BANNER_CLASS = "com.intellij.openapi.options.newEditor.Banner"
    const val CONFIGURABLE_EDITOR_BANNER_CLASS = "com.intellij.openapi.options.newEditor.ConfigurableEditorBanner"
    const val BREADCRUMBS_CLASS = "com.intellij.ui.components.breadcrumbs.Breadcrumbs"
    const val PROJECT_STRUCTURE_CONFIGURABLE_CLASS =
        "com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable"

    // Field names used for reflection
    const val FIELD_MY_TEXT = "myText"
    const val FIELD_MY_EDITOR = "myEditor"
    const val FIELD_EDITOR = "editor"
    const val FIELD_MY_BANNER = "myBanner"
    const val FIELD_MY_BREADCRUMBS = "myBreadcrumbs"
    const val FIELD_VIEWS = "views"
    const val FIELD_TEXT = "text"
    const val FIELD_MY_HISTORY = "myHistory"
    const val FIELD_MY_SIDE_PANEL = "mySidePanel"
    const val FIELD_MY_MODEL = "myModel"
    const val FIELD_MY_INDEX_2_SEPARATOR = "myIndex2Separator"
    const val FIELD_MY_PLACE = "myPlace"

    // Navigation place keys
    const val PLACE_CATEGORY = "category"

    // Method names
    const val METHOD_GET_PATH_NAMES = "getPathNames"
    const val METHOD_GET = "get"
}

/**
 * Layout-related constants extracted from magic numbers.
 * These control spatial analysis for component positioning.
 */
object LayoutConstants {
    /** Maximum horizontal distance for radio button group label detection. */
    const val MAX_HORIZONTAL_DISTANCE = 200

    /** Minimum indentation difference to consider a parent-child relationship. */
    const val MIN_INDENT_DIFF = 10

    /** Maximum height for value components (excludes large panels/text areas). */
    const val MAX_VALUE_COMPONENT_HEIGHT = 80

    /** Maximum width for value components (excludes large panels). */
    const val MAX_VALUE_COMPONENT_WIDTH = 400

    /** Maximum search depth when traversing ancestor containers. */
    const val MAX_SEARCH_DEPTH = 5

    /** Distance threshold for considering a candidate "good enough". */
    const val GOOD_CANDIDATE_DISTANCE = 100

    /** Tolerance for row alignment (center Y difference). */
    const val ROW_ALIGNMENT_TOLERANCE = 5

    /** Tolerance for horizontal overlap detection. */
    const val HORIZONTAL_OVERLAP_TOLERANCE = 5
}

/**
 * Cached regex patterns for better performance.
 * Regex compilation is expensive, so we cache patterns that are used frequently.
 */
object RegexPatterns {
    /** Pattern to match HTML tags for removal. */
    val HTML_TAGS: Regex = Regex("<[^>]*>")

    /** Pattern to match Advanced Settings IDs appended to labels. */
    val ADVANCED_SETTING_ID: Regex = Regex(":[a-z][a-z0-9]*(?:\\.[a-z0-9]+)+$")

    /** Pattern to detect default toString() object references (e.g., "ClassName@hexAddress"). */
    val OBJECT_REFERENCE: Regex = Regex(".*@[0-9a-fA-F]+$")
}

// ============================================================================
// String Extension Functions
// ============================================================================

/**
 * Removes all HTML tags from a string.
 * Uses cached regex pattern for performance.
 */
fun String.removeHtmlTags(): String = replace(RegexPatterns.HTML_TAGS, "")

/**
 * Removes Advanced Settings IDs that may be appended to labels.
 *
 * Pattern: "Label text:setting.id.here" -> "Label text"
 * The ID pattern is: colon followed by a dotted identifier (e.g., "copy.setting.path.separator").
 */
private fun String.removeAdvancedSettingIds(): String = replace(RegexPatterns.ADVANCED_SETTING_ID, "")

// ============================================================================
// Path Building Functions
// ============================================================================

/**
 * Appends an item to the path if it's not empty and not already the last item.
 *
 * @param path StringBuilder to append to.
 * @param item The item to append.
 * @param separator The separator to use between path components.
 */
fun appendItem(path: StringBuilder, item: String?, separator: String = PathConstants.SEPARATOR) {
    if (item.isNullOrEmpty()) return
    val cleanItem = item.removeHtmlTags()
    if (cleanItem.isEmpty()) return

    // Check for exact segment match (not just suffix match)
    val trimmedPath = path.trimEnd { it in PathSeparator.allSeparatorChars }.toString()
    val lastSegment = trimmedPath.substringAfterLast(PathConstants.SEPARATOR.trim()).trim()
    if (lastSegment == cleanItem) return

    path.append(cleanItem)
    // If the item ends with ":", it acts as a natural grouping label.
    path.append(if (cleanItem.endsWith(":")) " " else separator)
}

/**
 * Appends source component text to the path without a trailing separator.
 *
 * @param path StringBuilder to append to.
 * @param text The text to append.
 */
fun appendSrcText(path: StringBuilder, text: String?) {
    if (!text.isNullOrEmpty()) {
        val trimmedPath = path.trimEnd { it in PathSeparator.allSeparatorChars }
        if (!trimmedPath.endsWith(text)) {
            path.append(text)
        }
    }
}

/**
 * Trims the final result by removing trailing separators, HTML tags, and Advanced Settings IDs.
 *
 * @param path The path StringBuilder to process.
 * @return The cleaned path string.
 */
fun trimFinalResult(path: StringBuilder): String {
    return path.toString()
        .trimEnd { it in PathSeparator.allSeparatorChars }
        .removeHtmlTags()
        .removeAdvancedSettingIds()
}
