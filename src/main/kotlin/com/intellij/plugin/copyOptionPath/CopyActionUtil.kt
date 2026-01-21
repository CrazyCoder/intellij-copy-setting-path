@file:Suppress("UNCHECKED_CAST")

package com.intellij.plugin.copyOptionPath

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.newEditor.SettingsDialog
import com.intellij.ui.TitledSeparator
import com.intellij.ui.border.IdeaTitledBorder
import com.intellij.ui.navigation.History
import com.intellij.ui.navigation.Place
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseEvent
import java.lang.reflect.Field
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Logger instance for the copy option path plugin. */
val LOG: Logger = Logger.getInstance("#com.intellij.plugin.copyOptionPath")

/**
 * Constants used throughout the plugin for path construction.
 */
private object PathConstants {
    /** Separator used between path components. */
    const val SEPARATOR = " | "

    /** Prefix for Settings dialog paths. */
    const val SETTINGS_PREFIX = "Settings"

    /** Placeholder separator in Project Structure that should be ignored. */
    const val IGNORED_SEPARATOR = "--"

    // Class names used for reflection (to avoid direct dependencies)
    const val SETTINGS_EDITOR_CLASS = "com.intellij.openapi.options.newEditor.SettingsEditor"
    const val ABSTRACT_EDITOR_CLASS = "com.intellij.openapi.options.newEditor.AbstractEditor"
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
 * Extracts the settings path from the SettingsEditor component hierarchy.
 *
 * This is the modern approach (2022+) that finds the SettingsEditor in the component
 * hierarchy and calls its `getPathNames()` method via reflection.
 *
 * @param component The source component to start searching from.
 * @return Collection of path segments, or null if extraction fails.
 */
fun getPathFromSettingsEditor(component: Component): Collection<String>? {
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
 * First tries the modern `getPathNames()` API, then falls back to legacy
 * reflection-based approach for older IDEs.
 *
 * @param settings The SettingsDialog to extract path from.
 * @return The formatted path string, or null if extraction fails.
 */
fun getPathFromSettingsDialog(settings: SettingsDialog): String? {
    return runCatching {
        val editor = settings.editor
        val editorClassName = editor.javaClass.name
        LOG.debug("Editor class: $editorClassName")

        // getPathNames() only exists on SettingsEditor, not on SingleSettingEditor or AbstractEditor
        if (!editorClassName.contains("SettingsEditor")) {
            LOG.debug("Editor is not SettingsEditor, falling back to legacy approach")
            return@runCatching getPathFromSettingsDialogLegacy(settings)
        }

        val pathNames = invokeGetPathNames(editor)
        LOG.debug("pathNames result: $pathNames")

        if (!pathNames.isNullOrEmpty()) {
            buildPath(PathConstants.SETTINGS_PREFIX, pathNames)
        } else {
            PathConstants.SETTINGS_PREFIX
        }
    }.onFailure { e ->
        when (e) {
            is NoSuchMethodException -> LOG.debug("getPathNames method not found, falling back to legacy approach: ${e.message}")
            else -> LOG.debug("Exception when getting path from settings dialog: ${e.message}")
        }
    }.getOrNull() ?: getPathFromSettingsDialogLegacy(settings)
}

/**
 * Appends tree node path segments to the path builder.
 *
 * For each node in the tree path, extracts its text representation either
 * via `toString()` or by accessing the `myText` field via reflection.
 *
 * @param treePath Array of tree path nodes.
 * @param path StringBuilder to append path segments to.
 * @param separator The separator to use between path components.
 */
fun appendTreePath(treePath: Array<out Any>, path: StringBuilder, separator: String = PathConstants.SEPARATOR) {
    treePath.forEach { node ->
        val pathStr = node.toString()
        if (pathStr.isEmpty()) {
            extractMyTextField(node)?.let { appendItem(path, it, separator) }
        } else {
            appendItem(path, pathStr, separator)
        }
    }
}

/**
 * Detects the row at the mouse pointer position in a TreeTable.
 *
 * @param treeTable The TreeTable component.
 * @param e The action event containing mouse information.
 * @return The row index at the mouse position, or -1 if not found.
 */
fun detectRowFromMousePoint(treeTable: TreeTable, e: AnActionEvent): Int {
    val point = getConvertedMousePoint(e, treeTable) ?: return -1
    val rowAtPoint = treeTable.rowAtPoint(point)
    return if (rowAtPoint <= treeTable.rowCount) rowAtPoint else -1
}

/**
 * Converts the mouse event coordinates to the destination component's coordinate space.
 *
 * @param event The action event containing the input event.
 * @param destination The component to convert coordinates to.
 * @return The converted point, or null if the event is not a mouse event.
 */
fun getConvertedMousePoint(event: AnActionEvent, destination: Component): Point? {
    val mouseEvent = event.inputEvent as? MouseEvent ?: return null
    return SwingUtilities.convertMouseEvent(mouseEvent.component, mouseEvent, destination).point
}

/**
 * Extracts the middle path segments (tabs, titled borders, and titled separators) from the component hierarchy.
 *
 * @param src The source component.
 * @param path StringBuilder to append path segments to.
 * @param separator The separator to use between path components.
 */
fun getMiddlePath(src: Component, path: StringBuilder, separator: String = PathConstants.SEPARATOR) {
    // Add selected tab name if present
    UIUtil.getParentOfType(JBTabs::class.java, src)?.selectedInfo?.text?.let { tabText ->
        if (tabText.isNotEmpty()) {
            path.append(tabText).append(separator)
        }
    }

    // Add titled separator group name if present (e.g., "Java" section in Auto Import settings)
    findPrecedingTitledSeparator(src)?.let { titledSeparator ->
        val separatorText = titledSeparator.text
        if (!separatorText.isNullOrEmpty()) {
            appendItem(path, separatorText, separator)
        }
    }

    // Add titled border text if present
    (src.parent as? JPanel)?.let { parent ->
        (parent.border as? IdeaTitledBorder)?.title?.let { title ->
            if (title.isNotEmpty()) {
                appendItem(path, title, separator)
            }
        }
    }
}

/**
 * Finds the TitledSeparator that visually precedes the given component.
 *
 * TitledSeparators are used in Settings panels to group related options
 * (e.g., "Java" section in Auto Import settings). This function traverses
 * the component hierarchy to find the separator that appears before the
 * target component in the visual layout.
 *
 * @param component The component to find the preceding separator for.
 * @return The TitledSeparator that precedes the component, or null if not found.
 */
private fun findPrecedingTitledSeparator(component: Component): TitledSeparator? {
    // Get the Y coordinate of the component to compare positions
    val componentY = getAbsoluteY(component)

    // Traverse up through parent containers looking for TitledSeparators
    var current: Container? = component.parent
    var bestSeparator: TitledSeparator? = null
    var bestSeparatorY = Int.MIN_VALUE

    while (current != null) {
        // Search this container and all its descendants for TitledSeparators
        val separators = findAllTitledSeparators(current)

        for (separator in separators) {
            val separatorY = getAbsoluteY(separator)
            // The separator must be above (or at same level as) the component
            // and closer than any previously found separator
            if (separatorY <= componentY && separatorY > bestSeparatorY) {
                bestSeparator = separator
                bestSeparatorY = separatorY
            }
        }

        // If we found a separator at this level, use it
        if (bestSeparator != null) {
            return bestSeparator
        }

        current = current.parent
    }

    return bestSeparator
}

/**
 * Gets the absolute Y coordinate of a component on screen.
 */
private fun getAbsoluteY(component: Component): Int {
    return runCatching {
        component.locationOnScreen.y
    }.getOrElse {
        // Fallback to relative calculation if component is not showing
        var y = 0
        var current: Component? = component
        while (current != null) {
            y += current.y
            current = current.parent
        }
        y
    }
}

/**
 * Recursively finds all TitledSeparator components within a container.
 */
private fun findAllTitledSeparators(container: Container): List<TitledSeparator> {
    val result = mutableListOf<TitledSeparator>()

    for (comp in container.components) {
        if (comp is TitledSeparator) {
            result.add(comp)
        } else if (comp is Container) {
            result.addAll(findAllTitledSeparators(comp))
        }
    }

    return result
}

/**
 * Appends an item to the path if it's not empty and not already the last item.
 *
 * @param path StringBuilder to append to.
 * @param item The item to append.
 * @param separator The separator to use between path components.
 */
fun appendItem(path: StringBuilder, item: String?, separator: String = PathConstants.SEPARATOR) {
    if (!item.isNullOrEmpty() && !path.trimEnd { it in PathSeparator.allSeparatorChars }.endsWith(item)) {
        path.append(item).append(separator)
    }
}

/**
 * Appends source component text to the path without a trailing separator.
 *
 * @param path StringBuilder to append to.
 * @param text The text to append.
 */
fun appendSrcText(path: StringBuilder, text: String?) {
    if (!text.isNullOrEmpty()) {
        path.append(text)
    }
}

/**
 * Trims the final result by removing trailing separators, HTML tags, and Advanced Settings IDs.
 *
 * Advanced Settings UI may append setting IDs to labels when searching by ID
 * (e.g., "Path separator style:copy.option.path.separator"). This function
 * removes such IDs to produce clean paths.
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

/**
 * Removes Advanced Settings IDs that may be appended to labels.
 *
 * Pattern: "Label text:setting.id.here" -> "Label text"
 * The ID pattern is: colon followed by a dotted identifier (e.g., "copy.option.path.separator").
 * Must contain at least one dot to distinguish from regular text ending with ":word".
 */
private fun String.removeAdvancedSettingIds(): String {
    // Pattern matches: colon followed by a dotted setting ID at the end
    // Requires at least one dot to avoid false positives (e.g., ":enabled" vs ":some.setting.id")
    return replace(Regex(":[a-z][a-z0-9]*(?:\\.[a-z0-9]+)+$"), "")
}

/**
 * Finds a private or inherited field by name or type name in a class hierarchy.
 *
 * @param type The class to start searching from.
 * @param name The field name to search for.
 * @param orTypeName Optional type name to match if field name doesn't match.
 * @return The found field, or null if not found.
 */
fun findInheritedField(type: Class<*>, name: String, orTypeName: String? = null): Field? {
    return generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }.firstOrNull { field ->
            field.name == name || (orTypeName != null && field.type.name == orTypeName)
        }
}

/**
 * Appends path information from the Project Structure dialog.
 *
 * This extracts the current category and section name from the ProjectStructureConfigurable.
 *
 * @param configurable The configurable instance.
 * @param path StringBuilder to append path segments to.
 * @param separator The separator to use between path components.
 */
fun appendPathFromProjectStructureDialog(configurable: Configurable, path: StringBuilder, separator: String = PathConstants.SEPARATOR) {
    runCatching {
        val cfg = Class.forName(
            PathConstants.PROJECT_STRUCTURE_CONFIGURABLE_CLASS, true, configurable.javaClass.classLoader
        )

        if (!cfg.isInstance(configurable)) return

        val categoryConfigurable = findCategoryConfigurable(configurable, cfg) ?: return

        // Try to get the separator (section name) from SidePanel
        getSidePanelSeparatorForConfigurable(configurable, cfg, categoryConfigurable)?.let { sectionName ->
            if (sectionName != PathConstants.IGNORED_SEPARATOR) {
                appendItem(path, sectionName, separator)
            }
        }

        // Add the category name (e.g., "Project", "Modules")
        appendItem(path, categoryConfigurable.displayName, separator)
    }.onFailure { e ->
        when (e) {
            is ClassNotFoundException -> LOG.debug("ProjectStructureConfigurable not available: ${e.message}")
            else -> LOG.debug("Cannot get project structure path: ${e.message}")
        }
    }
}

// ============================================================================
// Private Helper Functions
// ============================================================================

/**
 * Finds a parent component by its class name.
 */
private fun findParentByClassName(component: Component, className: String): Component? {
    var current: Component? = component
    while (current != null) {
        if (current.javaClass.name == className) {
            return current
        }
        current = current.parent
    }
    return null
}

/**
 * Invokes the getPathNames() method on an object via reflection.
 */
private fun invokeGetPathNames(target: Any): Collection<String>? {
    val method = target.javaClass.getDeclaredMethod(PathConstants.METHOD_GET_PATH_NAMES)
    method.isAccessible = true
    return method.invoke(target) as? Collection<String>
}

/**
 * Extracts the myText field value from an object via reflection.
 */
private fun extractMyTextField(obj: Any): String? {
    return runCatching {
        val field = obj.javaClass.getDeclaredField(PathConstants.FIELD_MY_TEXT)
        field.isAccessible = true
        field.get(obj)?.toString()
    }.onFailure { e ->
        LOG.debug("Error trying to get '${PathConstants.FIELD_MY_TEXT}' field from $obj: ${e.message}")
    }.getOrNull()
}

/**
 * Builds a path string from a prefix and collection of segments.
 */
private fun buildPath(prefix: String, segments: Collection<String>): String {
    return buildString {
        append(prefix)
        append(PathConstants.SEPARATOR)
        append(segments.joinToString(PathConstants.SEPARATOR))
    }
}

/**
 * Legacy approach to extract path from SettingsDialog using deep reflection.
 * Used for compatibility with older IDE versions.
 */
private fun getPathFromSettingsDialogLegacy(settings: SettingsDialog): String? {
    return runCatching {
        // Try both old field name "myEditor" and new field name "editor"
        val editorField =
            findInheritedField(settings.javaClass, PathConstants.FIELD_MY_EDITOR, PathConstants.ABSTRACT_EDITOR_CLASS)
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
        val views =
            viewsField.get(breadcrumbsInstance) as? ArrayList<*> ?: return@runCatching PathConstants.SETTINGS_PREFIX

        buildString {
            append(PathConstants.SETTINGS_PREFIX)
            views.forEachIndexed { index, crumb ->
                crumb ?: return@forEachIndexed
                val textField = crumb.javaClass.getDeclaredField(PathConstants.FIELD_TEXT)
                textField.isAccessible = true
                textField.get(crumb)?.let { value ->
                    append(if (index > 0) PathConstants.SEPARATOR else PathConstants.SEPARATOR)
                    append(value)
                }
            }
        }
    }.onFailure { e ->
        LOG.debug("Exception when appending path (legacy): ${e.message}")
    }.getOrNull()
}

/**
 * Finds the current category configurable from ProjectStructureConfigurable's history.
 */
private fun findCategoryConfigurable(configurable: Any, cfgClass: Class<*>): Configurable? {
    for (field in cfgClass.declaredFields) {
        if (field.name == PathConstants.FIELD_MY_HISTORY || field.type.name == History::class.java.name) {
            field.isAccessible = true
            val history = field.get(configurable) as? History ?: continue
            val place = history.query()
            return place.getPath(PathConstants.PLACE_CATEGORY) as? Configurable
        }
    }
    return null
}

/**
 * Gets the separator text (section name like "Project Settings") for a given configurable
 * from the ProjectStructureConfigurable's SidePanel.
 */
private fun getSidePanelSeparatorForConfigurable(
    projectStructureConfigurable: Any, cfgClass: Class<*>, categoryConfigurable: Configurable
): String? {
    return runCatching {
        val sidePanelField = cfgClass.getDeclaredField(PathConstants.FIELD_MY_SIDE_PANEL)
        sidePanelField.isAccessible = true
        val sidePanel = sidePanelField.get(projectStructureConfigurable) ?: return@runCatching null

        val modelField = sidePanel.javaClass.getDeclaredField(PathConstants.FIELD_MY_MODEL)
        modelField.isAccessible = true
        val model = modelField.get(sidePanel) as? DefaultListModel<*> ?: return@runCatching null

        val separatorField = sidePanel.javaClass.getDeclaredField(PathConstants.FIELD_MY_INDEX_2_SEPARATOR)
        separatorField.isAccessible = true
        val separatorMap = separatorField.get(sidePanel) ?: return@runCatching null

        val itemIndex = findConfigurableIndexInModel(model, categoryConfigurable)
        if (itemIndex < 0) return@runCatching null

        findBestSeparator(separatorMap, itemIndex)
    }.onFailure { e ->
        LOG.debug("Could not get SidePanel separator: ${e.message}")
    }.getOrNull()
}

/**
 * Finds the index of a configurable in the SidePanel model.
 */
private fun findConfigurableIndexInModel(model: DefaultListModel<*>, categoryConfigurable: Configurable): Int {
    for (i in 0 until model.size) {
        val item = model.getElementAt(i) ?: continue
        runCatching {
            val placeField = item.javaClass.getDeclaredField(PathConstants.FIELD_MY_PLACE)
            placeField.isAccessible = true
            val place = placeField.get(item) as? Place
            val itemCategory = place?.getPath(PathConstants.PLACE_CATEGORY) as? Configurable
            if (itemCategory === categoryConfigurable || itemCategory?.displayName == categoryConfigurable.displayName) {
                return i
            }
        }
    }
    return -1
}

/**
 * Finds the best separator for a given index in the separator map.
 */
private fun findBestSeparator(separatorMap: Any, itemIndex: Int): String? {
    val getMethod = separatorMap.javaClass.getMethod(PathConstants.METHOD_GET, Int::class.javaPrimitiveType)
    var bestSeparator: String? = null

    for (i in 0..itemIndex) {
        (getMethod.invoke(separatorMap, i) as? String)?.let {
            bestSeparator = it
        }
    }

    return bestSeparator
}

/**
 * Removes all HTML tags from a string.
 */
private fun String.removeHtmlTags(): String = replace(Regex("<[^>]*>"), "")
