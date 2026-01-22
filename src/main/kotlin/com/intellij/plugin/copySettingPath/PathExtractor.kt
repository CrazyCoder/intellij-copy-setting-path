@file:Suppress("UNCHECKED_CAST")

package com.intellij.plugin.copySettingPath

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.newEditor.SettingsDialog
import com.intellij.ui.navigation.History
import com.intellij.ui.navigation.Place
import java.awt.Component
import javax.swing.DefaultListModel
import javax.swing.JPanel

/**
 * Utility functions for extracting navigation paths from Settings and Project Structure dialogs.
 *
 * This includes modern API-based extraction as well as legacy reflection-based
 * approaches for compatibility with older IDE versions.
 */

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
 * Appends path information from the Project Structure dialog.
 *
 * This extracts the current category and section name from the ProjectStructureConfigurable.
 *
 * @param configurable The configurable instance.
 * @param path StringBuilder to append path segments to.
 * @param separator The separator to use between path components.
 */
fun appendPathFromProjectStructureDialog(
    configurable: Configurable,
    path: StringBuilder,
    separator: String = PathConstants.SEPARATOR
) {
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
 * Builds a path string from a prefix and collection of segments.
 */
internal fun buildPath(prefix: String, segments: Collection<String>): String {
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
