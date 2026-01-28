package io.github.crazycoder.copysettingpath

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Component
import java.awt.Container
import java.awt.MouseInfo
import java.awt.Point
import java.util.concurrent.TimeUnit
import javax.swing.MenuSelectionManager

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
val LOG: Logger = Logger.getInstance("#io.github.crazycoder.copysettingpath")

/**
 * Constants used throughout the plugin for path construction and reflection.
 */
object PathConstants {
    /** Separator used between path components. */
    const val SEPARATOR = " | "

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

    // Popup class names (for JBPopup-based floating dialogs)
    const val FIND_POPUP_PANEL_CLASS = "com.intellij.find.impl.FindPopupPanel"
    const val SWITCHER_PANEL_CLASS = "com.intellij.platform.recentFiles.frontend.Switcher\$SwitcherPanel"
    const val SEARCH_EVERYWHERE_UI_CLASS = "com.intellij.ide.actions.searcheverywhere.SearchEverywhereUI"
    const val BIG_POPUP_UI_CLASS = "com.intellij.ide.actions.BigPopupUI"

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
    /** Maximum height for value components (excludes large panels/text areas). */
    const val MAX_VALUE_COMPONENT_HEIGHT = 80

    /** Maximum width for value components (excludes large panels). */
    const val MAX_VALUE_COMPONENT_WIDTH = 400

    /** Tolerance for row alignment (center Y difference). */
    const val ROW_ALIGNMENT_TOLERANCE = 5
}

/**
 * Cached regex patterns for better performance.
 * Regex compilation is expensive, so we cache patterns that are used frequently.
 */
object RegexPatterns {
    /** Pattern to match HTML tags for removal. */
    val HTML_TAGS: Regex = Regex("<[^>]*>")

    /**
     * Pattern to match Advanced Settings ID display in HTML labels.
     * In Advanced Settings, the setting ID is shown after a <br> tag, e.g.:
     * <html>Label text<br><pre><font...>setting.id.here</font>...</html>
     * This pattern removes the <br> and everything after it.
     */
    val HTML_SETTING_ID_SUFFIX: Regex = Regex("<br>.*", RegexOption.DOT_MATCHES_ALL)

    /** Pattern to match Advanced Settings IDs appended to labels (requires colon separator). */
    val ADVANCED_SETTING_ID: Regex = Regex(":[a-z][a-z0-9]*(?:\\.[a-z0-9]+)+$")

    /** Pattern to detect default toString() object references (e.g., "ClassName@hexAddress"). */
    val OBJECT_REFERENCE: Regex = Regex(".*@[0-9a-fA-F]+$")

    /** Pattern to match multiple whitespace characters (spaces, tabs, newlines) for collapsing. */
    val MULTIPLE_WHITESPACE: Regex = Regex("\\s+")
}

// ============================================================================
// String Extension Functions
// ============================================================================

/**
 * Removes all HTML tags from a string and collapses whitespace.
 *
 * This function:
 * 1. Removes Advanced Settings ID suffixes (shown after <br> in Advanced Settings labels)
 * 2. Removes all HTML tags
 * 3. Collapses multiple whitespace characters (spaces, tabs, newlines) into a single space
 * 4. Trims leading and trailing whitespace
 *
 * Uses cached regex patterns for performance.
 */
fun String.removeHtmlTags(): String = this
    .replace(RegexPatterns.HTML_SETTING_ID_SUFFIX, "")
    .replace(RegexPatterns.HTML_TAGS, "")
    .replace(RegexPatterns.MULTIPLE_WHITESPACE, " ")
    .trim()

/**
 * Removes Advanced Settings IDs that may be appended to labels.
 *
 * Pattern: "Label text:setting.id.here" -> "Label text"
 * The ID pattern is: colon followed by a dotted identifier (e.g., "copy.setting.path.separator").
 */
private fun String.removeAdvancedSettingIds(): String =
    replace(RegexPatterns.ADVANCED_SETTING_ID, "")

// ============================================================================
// Path Building Functions
// ============================================================================

/**
 * Appends an item to the path if it's not empty and not already the last item.
 *
 * @param path StringBuilder to append to.
 * @param item The item to append.
 * @param separator The separator to use between path components.
 * @param allowDuplicate If true, allows appending even if it matches the last segment.
 *                       Useful for tree paths where parent and child can have the same name.
 */
fun appendItem(
    path: StringBuilder,
    item: String?,
    separator: String = PathConstants.SEPARATOR,
    allowDuplicate: Boolean = false
) {
    if (item.isNullOrEmpty()) return
    val cleanItem = item.removeHtmlTags()
    if (cleanItem.isEmpty()) return

    if (!allowDuplicate) {
        // Check for exact segment match (not just suffix match)
        val trimmedPath =
            path.trimEnd { it in PathSeparator.allSeparatorChars }
                .toString()
        val lastSegment =
            trimmedPath.substringAfterLast(PathConstants.SEPARATOR.trim())
                .trim()
        if (lastSegment == cleanItem) return
    }

    path.append(cleanItem)
    // If the item ends with ":", it acts as a natural grouping label.
    path.append(if (cleanItem.endsWith(":")) " " else separator)
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

// ============================================================================
// Balloon Notification Functions
// ============================================================================

/** Advanced setting ID for balloon notification configuration. */
private const val SHOW_BALLOON_SETTING_ID = "copy.setting.path.show.balloon"

/** Duration in seconds before the balloon auto-hides. */
private const val BALLOON_DISPLAY_DURATION_SECONDS = 2L

/**
 * Shows a brief balloon notification near the mouse cursor
 * displaying the path that was copied to clipboard.
 *
 * The balloon is only shown if the "copy.setting.path.show.balloon" advanced setting is enabled.
 *
 * @param copiedPath The path that was copied to clipboard, to display in the balloon.
 */
fun showCopiedBalloon(copiedPath: String) {
    if (!AdvancedSettings.getBoolean(SHOW_BALLOON_SETTING_ID)) return

    // Skip balloon when menu is open (balloon would appear behind menu)
    if (MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()) return

    val mouseLocation = MouseInfo.getPointerInfo()?.location ?: return
    val point = RelativePoint(Point(mouseLocation.x, mouseLocation.y))

    val balloon = JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(copiedPath, null, null, null)
        .setAnimationCycle(0)
        .setRequestFocus(false)
        .createBalloon()

    balloon.show(point, Balloon.Position.above)

    // Schedule auto-hide after delay on EDT
    AppExecutorUtil.getAppScheduledExecutorService().schedule(
        { ApplicationManager.getApplication().invokeLater { balloon.hide() } },
        BALLOON_DISPLAY_DURATION_SECONDS,
        TimeUnit.SECONDS
    )
}

// ============================================================================
// Component Position Functions
// ============================================================================

/**
 * Gets the absolute Y coordinate of a component on screen.
 *
 * @param component The component to get the Y coordinate for.
 * @return The absolute Y coordinate on screen.
 */
fun getAbsoluteY(component: Component): Int =
    runCatching { component.locationOnScreen.y }.getOrDefault(component.y)

/**
 * Finds all components of a specific type within a container recursively.
 *
 * @param container The container to search in.
 * @return A sequence of all components of the specified type.
 */
inline fun <reified T : Component> findAllComponentsOfType(container: Container): Sequence<T> = sequence {
    val stack = ArrayDeque<Component>()
    stack.addAll(container.components)

    while (stack.isNotEmpty()) {
        val component = stack.removeFirst()
        if (component is T) {
            yield(component)
        }
        if (component is Container) {
            stack.addAll(component.components)
        }
    }
}
