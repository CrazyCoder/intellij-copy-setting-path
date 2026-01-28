@file:Suppress("UNCHECKED_CAST")

package io.github.crazycoder.copysettingpath.path

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.options.newEditor.SettingsDialog
import com.intellij.ui.TitledSeparator
import com.intellij.ui.tabs.JBTabs
import io.github.crazycoder.copysettingpath.*
import java.awt.Component
import java.awt.Container
import java.util.*
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.border.TitledBorder

/**
 * Simplified Settings path extraction matching IntelliJ's CopySettingsPathAction pattern.
 *
 * The extraction follows this approach:
 * 1. Get base path from SettingsEditor.getPathNames()
 * 2. Walk up hierarchy for tabs and titled borders (within ConfigurableEditor boundary)
 * 3. Add TitledSeparator if present
 */
object SettingsPathExtractor {

    private const val SETTINGS_PREFIX = "Settings"

    /**
     * Appends the Settings dialog path to the path builder.
     *
     * @param src The source component.
     * @param path StringBuilder to append path segments to.
     * @param separator The separator to use between path components.
     */
    fun appendSettingsPath(src: Component, path: StringBuilder, separator: String) {
        // 1. Get base path from SettingsEditor via component hierarchy
        val settingsEditorPath = getPathFromSettingsEditor(src)
        if (!settingsEditorPath.isNullOrEmpty()) {
            path.append(SETTINGS_PREFIX)
            path.append(separator)
            path.append(settingsEditorPath.joinToString(separator))
            path.append(separator)
        } else {
            // Fall back to dialog-level extraction
            val dialog = findSettingsDialog(src)
            if (dialog != null) {
                val dialogPath = getPathFromSettingsDialog(dialog, separator)
                appendItem(path, dialogPath, separator)
            }
        }

        // 2. Find ConfigurableEditor boundary
        val configurableEditor = findParentByClassName(src, PathConstants.CONFIGURABLE_EDITOR_CLASS)

        // 3. Collect middle path (tabs, titled borders) within ConfigurableEditor
        appendMiddlePath(src, configurableEditor, path, separator)

        // 4. Add TitledSeparator if present (but skip if src is itself a TitledSeparator or its child)
        if (!isInsideTitledSeparator(src)) {
            findPrecedingTitledSeparator(src, configurableEditor)?.let { separatorComponent ->
                appendItem(path, separatorComponent.text, separator)
            }
        }
    }

    /**
     * Extracts the settings path from the SettingsEditor component hierarchy.
     *
     * @param component The source component to start searching from.
     * @return Collection of path segments, or null if extraction fails.
     */
    private fun getPathFromSettingsEditor(component: Component): Collection<String>? {
        return runCatching {
            val settingsEditor = findParentByClassName(component, PathConstants.SETTINGS_EDITOR_CLASS)
            if (settingsEditor == null) {
                LOG.debug("SettingsEditor not found in component hierarchy")
                return@runCatching null
            }

            LOG.debug("Found SettingsEditor: ${settingsEditor.javaClass.name}")
            invokeGetPathNames(settingsEditor)
        }.onFailure { e ->
            LOG.debug("Error getting path from SettingsEditor: ${e.message}")
        }.getOrNull()
    }

    /**
     * Extracts the settings path from a SettingsDialog instance.
     *
     * Note: We use reflection to access the editor to avoid direct references to internal APIs.
     * The SettingsDialog.getEditor() method returns AbstractEditor which is @ApiStatus.Internal.
     *
     * @param settings The SettingsDialog to extract path from.
     * @param separator The separator to use between path components.
     * @return The formatted path string, or null if extraction fails.
     */
    private fun getPathFromSettingsDialog(settings: SettingsDialog, separator: String): String? {
        return runCatching {
            // Use reflection to get the editor to avoid internal API reference
            // SettingsDialog.getEditor() returns AbstractEditor which is @ApiStatus.Internal
            val editor =
                getEditorViaReflection(settings) ?: return@runCatching getPathFromSettingsDialogLegacy(settings, separator)
            val editorClassName = editor.javaClass.name
            LOG.debug("Editor class: $editorClassName")

            // getPathNames() only exists on SettingsEditor, not on SingleSettingEditor or AbstractEditor
            if (!editorClassName.contains("SettingsEditor")) {
                LOG.debug("Editor is not SettingsEditor, falling back to legacy approach")
                return@runCatching getPathFromSettingsDialogLegacy(settings, separator)
            }

            val pathNames = invokeGetPathNames(editor)
            LOG.debug("pathNames result: $pathNames")

            if (!pathNames.isNullOrEmpty()) {
                buildPath(pathNames, separator)
            } else {
                SETTINGS_PREFIX
            }
        }.onFailure { e ->
            when (e) {
                is NoSuchMethodException -> LOG.debug("getPathNames method not found: ${e.message}")
                else -> LOG.debug("Exception when getting path from settings dialog: ${e.message}")
            }
        }.getOrNull() ?: getPathFromSettingsDialogLegacy(settings, separator)
    }

    /**
     * Builds a path string from the Settings prefix and collection of segments.
     *
     * @param segments The path segments to join.
     * @param separator The separator to use between path components.
     */
    private fun buildPath(segments: Collection<String>, separator: String): String {
        return buildString {
            append(SETTINGS_PREFIX)
            append(separator)
            append(segments.joinToString(separator))
        }
    }

    /**
     * Legacy approach to extract path from SettingsDialog using deep reflection.
     *
     * @param settings The SettingsDialog to extract path from.
     * @param separator The separator to use between path components.
     */
    private fun getPathFromSettingsDialogLegacy(settings: SettingsDialog, separator: String): String? {
        return runCatching {
            val editorField =
                findInheritedField(
                    settings.javaClass,
                    PathConstants.FIELD_MY_EDITOR,
                    PathConstants.ABSTRACT_EDITOR_CLASS
                )
                    ?: findInheritedField(
                        settings.javaClass,
                        PathConstants.FIELD_EDITOR,
                        PathConstants.ABSTRACT_EDITOR_CLASS
                    )

            if (editorField == null) {
                LOG.debug("Could not find editor field in SettingsDialog")
                return@runCatching null
            }

            editorField.isAccessible = true
            val settingsEditorInstance = editorField.get(settings) as? JPanel ?: return@runCatching null

            val bannerField = findInheritedField(
                settingsEditorInstance.javaClass,
                PathConstants.FIELD_MY_BANNER,
                PathConstants.BANNER_CLASS
            ) ?: findInheritedField(
                settingsEditorInstance.javaClass,
                PathConstants.FIELD_MY_BANNER,
                PathConstants.CONFIGURABLE_EDITOR_BANNER_CLASS
            )

            if (bannerField == null) {
                LOG.debug("Could not find banner field in editor")
                return@runCatching null
            }

            bannerField.isAccessible = true
            val bannerInstance = bannerField.get(settingsEditorInstance) ?: return@runCatching null

            val breadcrumbsField = findInheritedField(
                bannerInstance.javaClass,
                PathConstants.FIELD_MY_BREADCRUMBS,
                PathConstants.BREADCRUMBS_CLASS
            )
            if (breadcrumbsField == null) {
                LOG.debug("Could not find myBreadcrumbs field in banner")
                return@runCatching null
            }

            breadcrumbsField.isAccessible = true
            val breadcrumbsInstance = breadcrumbsField.get(bannerInstance) ?: return@runCatching null

            val viewsField = breadcrumbsField.type.getDeclaredField(PathConstants.FIELD_VIEWS)
            viewsField.isAccessible = true
            val views = viewsField.get(breadcrumbsInstance) as? ArrayList<*> ?: return@runCatching SETTINGS_PREFIX

            buildString {
                append(SETTINGS_PREFIX)
                views.forEachIndexed { _, crumb ->
                    crumb ?: return@forEachIndexed
                    val textField = crumb.javaClass.getDeclaredField(PathConstants.FIELD_TEXT)
                    textField.isAccessible = true
                    textField.get(crumb)?.let { value ->
                        append(separator)
                        append(value)
                    }
                }
            }
        }.onFailure { e ->
            LOG.debug("Exception when appending path (legacy): ${e.message}")
        }.getOrNull()
    }

    /**
     * Appends middle path segments (tabs, titled borders) from the component hierarchy.
     *
     * This matches IntelliJ's CopySettingsPathAction approach:
     * - Walk up from component to boundary
     * - Collect JBTabs selected tab names
     * - Collect JTabbedPane selected tab titles
     * - Collect TitledBorder titles
     *
     * @param src The source component.
     * @param boundary The boundary component (ConfigurableEditor) to stop at.
     * @param path StringBuilder to append path segments to.
     * @param separator The separator to use between path components.
     */
    fun appendMiddlePath(src: Component, boundary: Component?, path: StringBuilder, separator: String) {
        val items = ArrayDeque<String>()
        var component: Component? = src

        while (component != null && component !== boundary) {
            collectTabName(component, items)
            collectTitledBorder(component, items)
            component = component.parent
        }

        // Add collected items in correct order (from root to leaf)
        for (item in items) {
            appendItem(path, item, separator)
        }
    }

    /**
     * Finds the SettingsDialog for the given component.
     */
    private fun findSettingsDialog(component: Component): SettingsDialog? {
        return com.intellij.openapi.ui.DialogWrapper.findInstance(component) as? SettingsDialog
    }

    /**
     * Gets the editor from a SettingsDialog via reflection.
     *
     * This avoids direct reference to the internal AbstractEditor class that is returned
     * by SettingsDialog.getEditor().
     *
     * @param settings The SettingsDialog to get the editor from.
     * @return The editor as Any, or null if reflection fails.
     */
    private fun getEditorViaReflection(settings: SettingsDialog): Any? {
        return runCatching {
            // Try the public getter method first (via reflection to avoid type reference)
            val getEditorMethod = settings.javaClass.getMethod("getEditor")
            getEditorMethod.invoke(settings)
        }.onFailure { e ->
            LOG.debug("Failed to get editor via getEditor() method: ${e.message}")
        }.getOrNull()
    }

    /**
     * Collects tab name from JBTabs, JTabbedPane, or ActionToolbar with toggle buttons.
     */
    private fun collectTabName(component: Component, items: ArrayDeque<String>) {
        when (component) {
            is JBTabs -> {
                component.selectedInfo?.text?.takeIf { it.isNotEmpty() }?.let {
                    items.addFirst(it)
                }
            }

            is JTabbedPane -> {
                val selectedIndex = component.selectedIndex
                if (selectedIndex >= 0 && selectedIndex < component.tabCount) {
                    component.getTitleAt(selectedIndex)?.takeIf { it.isNotEmpty() }?.let {
                        items.addFirst(it)
                    }
                }
            }

            is ActionToolbar -> {
                // Find selected toggle button in toolbar (e.g., scope selector in Find in Files)
                collectSelectedToggleButtonText(component, items)
            }
        }
    }

    /**
     * Finds the selected toggle button in an ActionToolbar and adds its text to items.
     * This handles toolbars like the scope selector in Find in Files (In Project/Module/Directory/Scope).
     */
    private fun collectSelectedToggleButtonText(toolbar: ActionToolbar, items: ArrayDeque<String>) {
        val toolbarComponent = toolbar.component
        for (child in toolbarComponent.components) {
            if (child is ActionButton) {
                val presentation = child.presentation
                if (Toggleable.isSelected(presentation)) {
                    val text = presentation.text?.removeHtmlTags()?.trim()
                    if (!text.isNullOrBlank()) {
                        items.addFirst(text)
                        return // Only add the first selected toggle
                    }
                }
            }
        }
    }

    /**
     * Collects titled border text from a JComponent.
     */
    private fun collectTitledBorder(component: Component, items: ArrayDeque<String>) {
        if (component is JComponent) {
            val border = component.border
            // TitledBorder includes IdeaTitledBorder (which extends TitledBorder)
            if (border is TitledBorder) {
                border.title?.takeIf { it.isNotEmpty() }?.let {
                    items.addFirst(it)
                }
            }
        }
    }

    /**
     * Finds the TitledSeparator that visually precedes the given component.
     *
     * Simplified version that assumes single-column layout (covers 95%+ of cases).
     *
     * @param component The component to find the preceding separator for.
     * @param boundary The boundary component to limit the search.
     * @return The TitledSeparator that precedes the component, or null if not found.
     */
    private fun findPrecedingTitledSeparator(component: Component, boundary: Component?): TitledSeparator? {
        val componentY = getAbsoluteY(component)
        val searchContainer = (boundary as? Container) ?: component.parent ?: return null

        var bestSeparator: TitledSeparator? = null
        var bestY = Int.MIN_VALUE

        findAllComponentsOfType<TitledSeparator>(searchContainer).forEach { separator ->
            if (!separator.isShowing) return@forEach
            val sepY = getAbsoluteY(separator)
            if (sepY in (bestY + 1)..<componentY) {
                bestSeparator = separator
                bestY = sepY
            }
        }

        return bestSeparator
    }

    /**
     * Checks if the component is a TitledSeparator or is contained within one.
     *
     * When clicking directly on a group title (TitledSeparator), we should not
     * search for a preceding TitledSeparator, as that would add an extra parent
     * group to the path.
     *
     * @param component The component to check.
     * @return true if the component is or is inside a TitledSeparator.
     */
    private fun isInsideTitledSeparator(component: Component): Boolean {
        var current: Component? = component
        while (current != null) {
            if (current is TitledSeparator) {
                return true
            }
            current = current.parent
        }
        return false
    }
}
