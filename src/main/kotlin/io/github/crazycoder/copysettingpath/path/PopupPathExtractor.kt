package io.github.crazycoder.copysettingpath.path

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitlePanel
import io.github.crazycoder.copysettingpath.*
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * Extracts navigation paths from JBPopup-based floating dialogs.
 *
 * Handles:
 * - Find in Path / Replace in Path (FindPopupPanel)
 * - Recent Files / Switcher (SwitcherPanel)
 * - Search Everywhere / Go to Action / Go to File (SearchEverywhereUI, BigPopupUI)
 * - Generic JBPopup-based popups (context menus, intention popups, etc.)
 *
 * These popups use the JBPopup API and are detected via PopupUtil.getPopupContainerFor().
 *
 * Example paths produced:
 * - `Find in Path | In Project`
 * - `Recent Files | MyFile.kt`
 * - `Search Everywhere | Classes | MyClass`
 * - `Actions | Generate...`
 */
object PopupPathExtractor {

    /**
     * Checks if the component is inside a JBPopup context.
     *
     * @param component The component to check.
     * @return true if the component is inside a JBPopup, false otherwise.
     */
    fun isInPopupContext(component: Component): Boolean {
        return getPopupFor(component) != null
    }

    /**
     * Gets the JBPopup containing the given component.
     *
     * @param component The component to find the popup for.
     * @return The JBPopup, or null if the component is not inside a popup.
     */
    fun getPopupFor(component: Component): JBPopup? {
        return PopupUtil.getPopupContainerFor(component)
    }

    /**
     * Builds the path for a component within a JBPopup.
     *
     * The path is constructed in layers:
     * 1. Popup title (from caption, accessible name, or window title)
     * 2. Specific popup type path segments (tabs, sections)
     * 3. Tree/table/list selection path
     * 4. Component label
     *
     * @param src The source UI component.
     * @param e The action event (may be null).
     * @param separator The separator to use between path components.
     * @return The built path string, or null if not in a popup context or path cannot be determined.
     */
    fun buildPath(src: Component, e: AnActionEvent?, separator: String): String? {
        val popup = getPopupFor(src) ?: return null

        val path = StringBuilder()

        // Try specific popup handlers first
        val specificPath = trySpecificPopupHandlers(src, e, separator)
        if (specificPath != null) {
            return specificPath
        }

        // Generic popup handling
        val popupTitle = extractPopupTitle(popup, src)
        appendItem(path, popupTitle ?: "Popup", separator)

        // Add middle path segments (tabs, titled borders)
        SettingsPathExtractor.appendMiddlePath(src, null, path, separator)

        // Add tree/table/list selection path
        TreeTablePathExtractor.appendPath(src, e, path, separator)

        // Add component label
        ComponentLabelExtractor.appendComponentLabel(src, path)

        return if (path.isEmpty()) null else path.toString()
    }

    /**
     * Tries specific popup handlers for known popup types.
     *
     * @return The path if a specific handler matched, null otherwise.
     */
    private fun trySpecificPopupHandlers(
        src: Component,
        e: AnActionEvent?,
        separator: String
    ): String? {
        // Check for FindPopupPanel
        val findPopupPath = handleFindPopupPanel(src, e, separator)
        if (findPopupPath != null) return findPopupPath

        // Check for SwitcherPanel
        val switcherPath = handleSwitcherPanel(src, e, separator)
        if (switcherPath != null) return switcherPath

        // Check for SearchEverywhereUI
        val searchEverywherePath = handleSearchEverywhereUI(src, e, separator)
        if (searchEverywherePath != null) return searchEverywherePath

        return null
    }

    /**
     * Handles Find in Path / Replace in Path popup.
     */
    private fun handleFindPopupPanel(
        src: Component,
        e: AnActionEvent?,
        separator: String
    ): String? {
        val findPopupPanel = findParentByClassName(src, PathConstants.FIND_POPUP_PANEL_CLASS)
            ?: return null

        val path = StringBuilder()

        // Get the title from the popup window
        val popupTitle = extractTitleFromWindow(findPopupPanel) ?: "Find in Path"
        appendItem(path, popupTitle, separator)

        // Add middle path (tabs like "Options", etc.)
        SettingsPathExtractor.appendMiddlePath(src, null, path, separator)

        // Add tree/table path (results table)
        TreeTablePathExtractor.appendPath(src, e, path, separator)

        // Add component label (scope selector, checkboxes, etc.)
        ComponentLabelExtractor.appendComponentLabel(src, path)

        return if (path.isEmpty()) null else path.toString()
    }

    /**
     * Handles Recent Files / Switcher popup.
     */
    private fun handleSwitcherPanel(
        src: Component,
        e: AnActionEvent?,
        separator: String
    ): String? {
        val switcherPanel = findParentByClassName(src, PathConstants.SWITCHER_PANEL_CLASS)
            ?: return null

        val path = StringBuilder()

        // Get title from the panel's "title" property or popup
        val popupTitle = extractSwitcherTitle(switcherPanel) ?: "Recent Files"
        appendItem(path, popupTitle, separator)

        // Add middle path (if any tabs)
        SettingsPathExtractor.appendMiddlePath(src, null, path, separator)

        // Add list selection path (file name or tool window name)
        TreeTablePathExtractor.appendPath(src, e, path, separator)

        // Add component label
        ComponentLabelExtractor.appendComponentLabel(src, path)

        return if (path.isEmpty()) null else path.toString()
    }

    /**
     * Handles Search Everywhere / Go to Action / Go to File popup.
     */
    private fun handleSearchEverywhereUI(
        src: Component,
        e: AnActionEvent?,
        separator: String
    ): String? {
        // Check for SearchEverywhereUI or its parent BigPopupUI
        val searchEverywhereUI = findParentByClassName(src, PathConstants.SEARCH_EVERYWHERE_UI_CLASS)
            ?: findParentByClassName(src, PathConstants.BIG_POPUP_UI_CLASS)
            ?: return null

        val path = StringBuilder()

        // Get the title - try to get from header or use default
        val popupTitle = extractSearchEverywhereTitle(searchEverywhereUI)
        appendItem(path, popupTitle, separator)

        // Try to get selected tab name from header
        val tabName = extractSearchEverywhereTabName(searchEverywhereUI)
        if (!tabName.isNullOrBlank()) {
            appendItem(path, tabName, separator)
        }

        // Add list selection path (action name, class name, file name, etc.)
        TreeTablePathExtractor.appendPath(src, e, path, separator)

        // Add component label
        ComponentLabelExtractor.appendComponentLabel(src, path)

        return if (path.isEmpty()) null else path.toString()
    }

    /**
     * Extracts the popup title from various sources.
     *
     * This is a universal approach that works for all JBPopup-based popups:
     * 1. Call getTitle() on AbstractPopup to get CaptionPanel (standard popups)
     * 2. Search for title-like components in popup content (custom header panels)
     * 3. Fall back to accessible name or window title
     */
    private fun extractPopupTitle(popup: JBPopup, src: Component): String? {
        // Try to get title from popup's getTitle() method (AbstractPopup.getTitle() returns CaptionPanel)
        val captionTitle = extractTitleFromCaptionPanel(popup)
        if (!captionTitle.isNullOrBlank()) {
            return captionTitle
        }

        // Try to find title in popup content (for custom header panels like RecentLocations)
        val content = popup.content
        val contentTitle = extractTitleFromPopupContent(content)
        if (!contentTitle.isNullOrBlank()) {
            return contentTitle
        }

        // Try accessible name from popup content
        val accessibleName = content.accessibleContext?.accessibleName
        if (!accessibleName.isNullOrBlank()) {
            return accessibleName
        }

        // Try window title
        return extractTitleFromWindow(src)
    }

    /**
     * Extracts title from AbstractPopup's CaptionPanel (returned by getTitle()).
     *
     * This is the universal approach that works for all popup types:
     * - If caption is TitlePanel, it has a getLabel() method
     * - Otherwise, search for JLabel children in the CaptionPanel
     */
    private fun extractTitleFromCaptionPanel(popup: JBPopup): String? {
        return runCatching {
            // Call getTitle() which returns CaptionPanel (or TitlePanel which extends it)
            val getTitleMethod = popup.javaClass.getMethod("getTitle")
            val captionPanel = getTitleMethod.invoke(popup) ?: return@runCatching null

            // If it's a TitlePanel, use the dedicated getLabel() method
            if (captionPanel is TitlePanel) {
                val label = captionPanel.label
                val text = label.text?.removeHtmlTags()?.trim()
                if (!text.isNullOrBlank()) {
                    return@runCatching text
                }
            }

            // For other CaptionPanel types, search for JLabel children
            if (captionPanel is Container) {
                val labelText = findFirstLabelText(captionPanel)
                if (!labelText.isNullOrBlank()) {
                    return@runCatching labelText
                }
            }

            null
        }.getOrNull()
    }

    /**
     * Recursively searches for the first JLabel with non-empty text in a container.
     */
    private fun findFirstLabelText(container: Container): String? {
        for (component in container.components) {
            if (component is JLabel) {
                val text = component.text?.removeHtmlTags()?.trim()
                if (!text.isNullOrBlank()) {
                    return text
                }
            }
            if (component is Container) {
                val found = findFirstLabelText(component)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    /**
     * Extracts title from popup content by searching for title-like components.
     *
     * Many popups use custom header panels with SimpleColoredComponent or JLabel
     * instead of the standard CaptionPanel/TitlePanel mechanism.
     *
     * Strategy:
     * 1. Look for SimpleColoredComponent with BOLD text (common pattern for titles)
     * 2. Look for bold JLabel
     * 3. Look for JLabel at the top of the content hierarchy
     */
    private fun extractTitleFromPopupContent(content: Container): String? {
        // First, try to find SimpleColoredComponent with bold text (common for custom headers)
        val boldTitle = findBoldSimpleColoredComponentText(content, maxDepth = 6)
        if (!boldTitle.isNullOrBlank()) {
            return boldTitle
        }

        // Try to find bold JLabel
        val boldLabelTitle = findBoldLabelText(content, maxDepth = 6)
        if (!boldLabelTitle.isNullOrBlank()) {
            return boldLabelTitle
        }

        // Fall back to first title-like JLabel
        val labelTitle = findTitleLikeLabelText(content, maxDepth = 6)
        if (!labelTitle.isNullOrBlank()) {
            return labelTitle
        }

        return null
    }

    /**
     * Searches for a JLabel with bold font.
     */
    private fun findBoldLabelText(container: Container, maxDepth: Int): String? {
        if (maxDepth <= 0) return null

        for (component in container.components) {
            if (component is JLabel) {
                val font = component.font
                if (font != null && font.isBold) {
                    val text = component.text?.removeHtmlTags()?.trim()
                    if (!text.isNullOrBlank()) {
                        return text
                    }
                }
            }
            if (component is Container) {
                val found = findBoldLabelText(component, maxDepth - 1)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    /**
     * Searches for a JLabel that looks like a title (short, doesn't end with ":").
     */
    private fun findTitleLikeLabelText(container: Container, maxDepth: Int): String? {
        if (maxDepth <= 0) return null

        for (component in container.components) {
            if (component is JLabel) {
                val text = component.text?.removeHtmlTags()?.trim()
                if (!text.isNullOrBlank() &&
                    !text.endsWith(":") &&
                    text.length in 3..50 &&
                    !text.contains("Ctrl+") &&
                    !text.contains("Cmd+") &&
                    !text.contains("Alt+")
                ) {
                    return text
                }
            }
            if (component is Container) {
                val found = findTitleLikeLabelText(component, maxDepth - 1)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    /**
     * Searches for a SimpleColoredComponent with BOLD text attributes.
     * This is common for popup titles in custom header panels.
     *
     * @param maxDepth Maximum depth to search (to avoid going too deep into content)
     */
    private fun findBoldSimpleColoredComponentText(container: Container, maxDepth: Int): String? {
        if (maxDepth <= 0) return null

        for (component in container.components) {
            if (component is SimpleColoredComponent) {
                val boldText = extractBoldTextFromSimpleColoredComponent(component)
                if (!boldText.isNullOrBlank()) {
                    return boldText
                }
            }
            if (component is Container) {
                val found = findBoldSimpleColoredComponentText(component, maxDepth - 1)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    /**
     * Extracts bold text from a SimpleColoredComponent.
     * Returns the concatenated text of all BOLD fragments.
     */
    private fun extractBoldTextFromSimpleColoredComponent(component: SimpleColoredComponent): String? {
        return runCatching {
            val iterator = component.iterator()
            val boldParts = mutableListOf<String>()

            while (iterator.hasNext()) {
                val fragment = iterator.next()
                val text = fragment?.removeHtmlTags()?.trim()
                if (!text.isNullOrBlank()) {
                    // Check if this fragment has BOLD style
                    val style = iterator.textAttributes.style
                    if ((style and SimpleTextAttributes.STYLE_BOLD) != 0) {
                        boldParts.add(text)
                    }
                }
            }

            boldParts.joinToString(" ").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Extracts title from the window ancestor.
     */
    private fun extractTitleFromWindow(component: Component): String? {
        return when (val window = SwingUtilities.getWindowAncestor(component)) {
            is java.awt.Frame -> window.title?.takeIf { it.isNotBlank() }
            is java.awt.Dialog -> window.title?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /**
     * Extracts the title from a Switcher panel.
     */
    private fun extractSwitcherTitle(switcherPanel: Component): String? {
        // Try to get "title" field from SwitcherPanel
        return runCatching {
            val titleField = switcherPanel.javaClass.getDeclaredField("title")
            titleField.isAccessible = true
            titleField.get(switcherPanel)?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: extractTitleFromWindow(switcherPanel)
    }

    /**
     * Extracts the title from SearchEverywhereUI.
     */
    private fun extractSearchEverywhereTitle(searchEverywhereUI: Component): String {
        // SearchEverywhereUI typically uses "Search Everywhere" as the window title
        return extractTitleFromWindow(searchEverywhereUI) ?: "Search Everywhere"
    }

    /**
     * Extracts the selected tab name from SearchEverywhereUI header.
     */
    private fun extractSearchEverywhereTabName(searchEverywhereUI: Component): String? {
        return runCatching {
            // Try to get myHeader field
            val headerField = findInheritedField(searchEverywhereUI.javaClass, "myHeader")
            headerField?.isAccessible = true
            val header = headerField?.get(searchEverywhereUI) ?: return@runCatching null

            // Try to get selected tab from header
            val getSelectedTabMethod = header.javaClass.methods.find {
                it.name == "getSelectedTab" || it.name == "getSelectedContributor"
            }
            val selectedTab = getSelectedTabMethod?.invoke(header) ?: return@runCatching null

            // Extract display name from the tab
            extractDisplayNameViaReflection(selectedTab)
        }.getOrNull()
    }
}
