@file:Suppress("UNCHECKED_CAST")

package io.github.crazycoder.copysettingpath.path

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.options.Configurable
import com.intellij.ui.navigation.History
import com.intellij.ui.navigation.Place
import io.github.crazycoder.copysettingpath.LOG
import io.github.crazycoder.copysettingpath.PathConstants
import io.github.crazycoder.copysettingpath.appendItem
import javax.swing.DefaultListModel

/**
 * Handles path extraction for Project Structure dialog.
 *
 * Project Structure is available in IntelliJ IDEA (Community/Ultimate) and EDU products.
 * It extracts the current category and section name from the ProjectStructureConfigurable.
 */
object ProjectStructurePathHandler {

    /** Product identifiers for IDEs that support Project Structure dialog. */
    private val IDEA_PRODUCT_IDENTIFIERS = listOf("idea", "intellij", "edu")

    /**
     * Checks if the current IDE product supports Project Structure dialog.
     */
    fun isSupported(): Boolean {
        val productName = ApplicationNamesInfo.getInstance().productName.lowercase()
        return IDEA_PRODUCT_IDENTIFIERS.any { productName.contains(it) }
    }

    /**
     * Appends path information from the Project Structure dialog.
     *
     * @param configurable The configurable instance.
     * @param path StringBuilder to append path segments to.
     * @param separator The separator to use between path components.
     */
    fun appendPath(configurable: Configurable, path: StringBuilder, separator: String) {
        runCatching {
            val cfg = Class.forName(
                PathConstants.PROJECT_STRUCTURE_CONFIGURABLE_CLASS,
                true,
                configurable.javaClass.classLoader
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
        projectStructureConfigurable: Any,
        cfgClass: Class<*>,
        categoryConfigurable: Configurable
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
}
