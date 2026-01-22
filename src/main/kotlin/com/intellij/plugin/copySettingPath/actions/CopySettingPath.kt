package com.intellij.plugin.copySettingPath.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.ex.SingleConfigurableEditor
import com.intellij.openapi.options.newEditor.SettingsDialog
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.plugin.copySettingPath.PathSeparator
import com.intellij.plugin.copySettingPath.appendItem
import com.intellij.plugin.copySettingPath.appendPathFromProjectStructureDialog
import com.intellij.plugin.copySettingPath.appendSrcText
import com.intellij.plugin.copySettingPath.appendTreePath
import com.intellij.plugin.copySettingPath.detectColumnFromMousePoint
import com.intellij.plugin.copySettingPath.detectRowFromMousePoint
import com.intellij.plugin.copySettingPath.extractComponentValue
import com.intellij.plugin.copySettingPath.findAdjacentComponent
import com.intellij.plugin.copySettingPath.getConvertedMousePoint
import com.intellij.plugin.copySettingPath.getMiddlePath
import com.intellij.plugin.copySettingPath.getPathFromSettingsDialog
import com.intellij.plugin.copySettingPath.getPathFromSettingsEditor
import com.intellij.plugin.copySettingPath.trimFinalResult
import com.intellij.ui.treeStructure.Tree
import com.intellij.ui.treeStructure.treetable.TreeTable
import com.intellij.util.ui.TextTransferable
import java.awt.Component
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTree

/**
 * Action that copies the full navigation path to the currently focused UI setting.
 *
 * This action allows users to copy paths like "Settings | Editor | Code Style | Java"
 * from IDE dialogs to the clipboard. It works with:
 * - Settings dialogs (via breadcrumb extraction)
 * - Project Structure dialog (IntelliJ IDEA and EDU products)
 * - Tree structures, tabs, titled panels, buttons, and labels
 *
 * The action can be triggered via context menu or Ctrl+Click (Cmd+Click on macOS).
 */
class CopySettingPath : DumbAwareAction() {

    init {
        isEnabledInModalContext = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val src = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) ?: return
        val path = buildSettingPath(src, e) ?: return

        val result = trimFinalResult(path)
        com.intellij.plugin.copySettingPath.LOG.debug("Selected path: $result")
        e.inputEvent?.consume()
        CopyPasteManager.getInstance().setContents(TextTransferable(result, result))
    }

    /**
     * Builds the complete setting path for the given source component.
     *
     * @param src The source UI component.
     * @param e The action event containing context information.
     * @return StringBuilder with the built path, or null if path cannot be determined.
     */
    private fun buildSettingPath(src: Component, e: AnActionEvent): StringBuilder? {
        val dialog = DialogWrapper.findInstance(src) ?: return null
        val path = StringBuilder()
        val separator = getPathSeparator()

        when (dialog) {
            is SettingsDialog -> appendSettingsDialogPath(src, dialog, path, separator)
            else -> appendGenericDialogPath(dialog, path, separator)
        }

        getMiddlePath(src, path, separator)
        appendTreePathIfApplicable(src, e, path, separator)
        appendSourceComponentText(src, path)

        return path
    }

    /**
     * Appends path information from a Settings dialog.
     */
    private fun appendSettingsDialogPath(
        src: Component,
        dialog: SettingsDialog,
        path: StringBuilder,
        separator: String
    ) {
        // Try the new approach: get path directly from SettingsEditor via component hierarchy
        val settingsEditorPath = getPathFromSettingsEditor(src)
        if (!settingsEditorPath.isNullOrEmpty()) {
            path.append(SETTINGS_PREFIX)
            path.append(separator)
            path.append(settingsEditorPath.joinToString(separator))
            path.append(separator)
        } else {
            // Fall back to the old approach via SettingsDialog
            appendItem(path, getPathFromSettingsDialog(dialog), separator)
        }
    }

    /**
     * Appends path information from a generic (non-Settings) dialog.
     */
    private fun appendGenericDialogPath(dialog: DialogWrapper, path: StringBuilder, separator: String) {
        appendItem(path, dialog.title, separator)

        if (dialog is SingleConfigurableEditor && isProjectStructureSupported()) {
            appendPathFromProjectStructureDialog(dialog.configurable, path, separator)
        }
    }

    /**
     * Checks if the current IDE product supports Project Structure dialog.
     *
     * Project Structure is available in IntelliJ IDEA (Community/Ultimate) and EDU products.
     */
    private fun isProjectStructureSupported(): Boolean {
        val productName = ApplicationNamesInfo.getInstance().productName.lowercase()
        return IDEA_PRODUCT_IDENTIFIERS.any { productName.contains(it) }
    }

    /**
     * Appends tree/table path information if the source component is a tree, tree table, or table.
     */
    private fun appendTreePathIfApplicable(src: Component, e: AnActionEvent, path: StringBuilder, separator: String) {
        when (src) {
            is TreeTable -> appendTreeTablePath(src, e, path, separator)
            is Tree -> appendTreeComponentPath(src, e, path, separator)
            is JTable -> appendTablePath(src, e, path, separator)
        }
    }

    /**
     * Appends path from a TreeTable component.
     */
    private fun appendTreeTablePath(treeTable: TreeTable, e: AnActionEvent, path: StringBuilder, separator: String) {
        val selectedRow = treeTable.selectedRow.takeIf { it != -1 } ?: detectRowFromMousePoint(treeTable, e)
        if (selectedRow != -1) {
            treeTable.tree.getPathForRow(selectedRow)?.path?.let { rowPath ->
                val filteredPath = filterInvisibleRoot(rowPath, treeTable.tree)
                appendTreePath(filteredPath, path, separator)
            }
        }
    }

    /**
     * Appends path from a Tree component.
     */
    private fun appendTreeComponentPath(tree: Tree, e: AnActionEvent, path: StringBuilder, separator: String) {
        val point = getConvertedMousePoint(e, tree) ?: return
        val rowForLocation = tree.getRowForLocation(point.x, point.y)
        if (rowForLocation > 0) {
            tree.getPathForRow(rowForLocation)?.let { treePath ->
                val filteredPath = filterInvisibleRoot(treePath.path, tree)
                appendTreePath(filteredPath, path, separator)
            }
        }
    }

    /**
     * Filters out the root node from the path if it's not visible in the tree.
     *
     * Some trees (like the Keymap tree) have `isRootVisible = false`, meaning the root
     * node exists in the model but is not displayed. In such cases, we should skip
     * the root when building the copied path to match what the user actually sees.
     *
     * @param pathArray The full tree path including the root.
     * @param tree The tree component to check for root visibility.
     * @return The filtered path array, with root removed if not visible.
     */
    private fun filterInvisibleRoot(pathArray: Array<out Any>, tree: JTree): Array<out Any> {
        return if (!tree.isRootVisible && pathArray.size > 1) {
            pathArray.drop(1).toTypedArray()
        } else {
            pathArray
        }
    }

    /**
     * Appends path from a JTable component.
     *
     * For tables like Registry dialog, extracts the value from the clicked cell
     * (or first column if no specific cell was clicked).
     */
    private fun appendTablePath(table: JTable, e: AnActionEvent, path: StringBuilder, separator: String) {
        val selectedRow = table.selectedRow.takeIf { it != -1 } ?: detectRowFromMousePoint(table, e)
        if (selectedRow != -1) {
            // Get the column from the click point, default to 0 if not found
            val selectedColumn = detectColumnFromMousePoint(table, e).takeIf { it != -1 }
                ?: table.selectedColumn.takeIf { it != -1 }
                ?: 0
            val value = table.getValueAt(selectedRow, selectedColumn)
            value?.toString()?.takeIf { it.isNotEmpty() }?.let { cellValue ->
                appendItem(path, cellValue, separator)
            }
        }
    }

    /**
     * Appends the text from the source component (button, label, or component name).
     *
     * If the component text ends with ":" (colon), this indicates there is likely
     * an adjacent value component (combo box, text field, etc.). In such cases,
     * we find the adjacent component and append its current value.
     *
     * Example: "Logger:" with adjacent combo box "Unspecified" -> "Logger: Unspecified"
     */
    private fun appendSourceComponentText(src: Component, path: StringBuilder) {
        val rawText = when (src) {
            is AbstractButton -> src.text
            is JLabel -> src.text
            else -> src.name
        }

        val text = rawText?.removeHtmlTags()?.trim()

        if (text.isNullOrEmpty()) {
            return
        }

        // Check if the text ends with ":" - indicates an adjacent value component
        if (text.endsWith(":")) {
            appendSrcText(path, text)
            // Try to find and append the adjacent component's value (if enabled)
            if (isAdjacentValueIncluded()) {
                findAdjacentComponent(src)?.let { adjacentComponent ->
                    extractComponentValue(adjacentComponent)?.let { value ->
                        // Only append if there's actually a non-empty value
                        if (value.isNotBlank()) {
                            path.append(" ")
                            path.append(value)
                        }
                    }
                }
            }
        } else {
            appendSrcText(path, text)
        }
    }

    /**
     * Removes HTML tags from a string for clean text processing.
     */
    private fun String.removeHtmlTags(): String = replace(Regex("<[^>]*>"), "")

    @Suppress("CompanionObjectInExtension")
    companion object {
        /** Advanced setting ID for path separator configuration. */
        private const val PATH_SEPARATOR_SETTING_ID = "copy.setting.path.separator"

        /** Advanced setting ID for including adjacent value after colon labels. */
        private const val INCLUDE_ADJACENT_VALUE_SETTING_ID = "copy.setting.path.include.adjacent.value"

        /** Prefix for Settings dialog paths. */
        private const val SETTINGS_PREFIX = "Settings"

        /**
         * Product identifiers for IDEs that support Project Structure dialog.
         * Includes IntelliJ IDEA variants (idea, intellij) and educational products.
         */
        private val IDEA_PRODUCT_IDENTIFIERS = listOf("idea", "intellij", "edu")

        /**
         * Returns the currently configured path separator from Advanced Settings.
         */
        fun getPathSeparator(): String =
            AdvancedSettings.getEnum(PATH_SEPARATOR_SETTING_ID, PathSeparator::class.java).separator

        /**
         * Returns whether adjacent value should be included for labels ending with colon.
         */
        private fun isAdjacentValueIncluded(): Boolean =
            AdvancedSettings.getBoolean(INCLUDE_ADJACENT_VALUE_SETTING_ID)
    }
}
