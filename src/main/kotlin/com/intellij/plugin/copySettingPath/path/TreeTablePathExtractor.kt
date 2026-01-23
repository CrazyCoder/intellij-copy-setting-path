package com.intellij.plugin.copySettingPath.path

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.plugin.copySettingPath.*
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.treeStructure.Tree
import com.intellij.ui.treeStructure.treetable.TreeTable
import java.awt.Component
import java.awt.Container
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Extracts path segments from tree, table, and list components.
 *
 * Handles:
 * - TreeTable: Tree path extraction from tree table
 * - Tree: Tree path extraction
 * - JTable: Cell value extraction (e.g., Registry dialog)
 * - JList: List item extraction (e.g., Notifications settings)
 */
object TreeTablePathExtractor {

    /**
     * Appends tree/table/list path information if the source component is applicable.
     *
     * @param src The source component.
     * @param e The action event.
     * @param path StringBuilder to append path segments to.
     * @param separator The separator to use.
     */
    fun appendPath(src: Component, e: AnActionEvent?, path: StringBuilder, separator: String) {
        when (src) {
            is TreeTable -> appendTreeTablePath(src, e, path, separator)
            is Tree -> appendTreeComponentPath(src, e, path, separator)
            is JTable -> appendTablePath(src, e, path, separator)
            is JList<*> -> appendListPath(src, e, path, separator)
        }
    }

    /**
     * Appends path from a TreeTable component.
     */
    private fun appendTreeTablePath(treeTable: TreeTable, e: AnActionEvent?, path: StringBuilder, separator: String) {
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
    private fun appendTreeComponentPath(tree: Tree, e: AnActionEvent?, path: StringBuilder, separator: String) {
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
     * Appends path from a JTable component.
     */
    private fun appendTablePath(table: JTable, e: AnActionEvent?, path: StringBuilder, separator: String) {
        val selectedRow = table.selectedRow.takeIf { it != -1 } ?: detectRowFromMousePoint(table, e)
        if (selectedRow != -1) {
            val selectedColumn = detectColumnFromMousePoint(table, e).takeIf { it != -1 }
                ?: table.selectedColumn.takeIf { it != -1 }
                ?: 0

            val displayText = extractTableCellDisplayText(table, selectedRow, selectedColumn)
            displayText?.takeIf { it.isNotEmpty() }?.let { cellValue ->
                appendItem(path, cellValue, separator)
            }
        }
    }

    /**
     * Appends path from a JList component.
     */
    private fun appendListPath(list: JList<*>, e: AnActionEvent?, path: StringBuilder, separator: String) {
        val clickedIndex = detectListIndexFromMousePoint(list, e)
        val targetIndex = if (clickedIndex != -1) clickedIndex else list.selectedIndex
        if (targetIndex != -1 && targetIndex < list.model.size) {
            val item = list.model.getElementAt(targetIndex)
            val displayText = extractListItemDisplayText(list, item, targetIndex)
            displayText?.takeIf { it.isNotEmpty() }?.let { itemText ->
                appendItem(path, itemText, separator)
            }
        }
    }

    /**
     * Filters out the root node from the path if it's not visible in the tree.
     */
    private fun filterInvisibleRoot(pathArray: Array<out Any>, tree: JTree): Array<out Any> {
        return if (!tree.isRootVisible && pathArray.size > 1) {
            pathArray.drop(1).toTypedArray()
        } else {
            pathArray
        }
    }

    /**
     * Appends tree node path segments to the path builder.
     */
    private fun appendTreePath(treePath: Array<out Any>, path: StringBuilder, separator: String) {
        treePath.forEach { node ->
            val displayText = extractTreeNodeDisplayText(node)
            if (!displayText.isNullOrEmpty()) {
                appendItem(path, displayText, separator)
            }
        }
    }

    /**
     * Extracts the display text from a tree node.
     *
     * For DefaultMutableTreeNode:
     * 1. First try reflection on userObject to get display name (getName, getDisplayName, title, etc.)
     * 2. If reflection fails, check if node.toString() differs from userObject.toString() -
     *    this indicates a custom toString() override (like ColorOptionsTree.MyTreeNode)
     * 3. Fall back to userObject.toString() if it looks like a display name
     */
    private fun extractTreeNodeDisplayText(node: Any): String? {
        if (node is DefaultMutableTreeNode) {
            val userObject = node.userObject ?: return null

            // First try to get display name via reflection (handles InlayGroup.title(), etc.)
            extractDisplayNameViaReflection(userObject)?.let { return it }

            // Check if node has a custom toString() override (like ColorOptionsTree.MyTreeNode)
            // If node.toString() differs from userObject.toString(), use node's version
            val nodeString = node.toString()
            val userObjectString = userObject.toString()
            if (nodeString != userObjectString && !nodeString.isNullOrEmpty() && !looksLikeObjectReference(nodeString)) {
                return nodeString
            }

            // Fall back to userObject string representation
            return extractDisplayTextFromUserObject(userObject)
        }

        return node.toString().ifEmpty { extractMyTextField(node) }
    }

    /**
     * Extracts display text from a tree node's userObject.
     */
    private fun extractDisplayTextFromUserObject(userObject: Any): String? {
        return when (userObject) {
            is String -> {
                // Could be an action ID - try to resolve it to a display name
                resolveActionDisplayName(userObject) ?: userObject
            }

            else -> {
                extractDisplayNameViaReflection(userObject) ?: userObject.toString().takeIf { it.isNotEmpty() }
            }
        }
    }

    /**
     * Resolves an action ID to its display name via ActionManager.
     */
    private fun resolveActionDisplayName(actionId: String): String? {
        if (actionId.isBlank()) return null

        return runCatching {
            val action = ActionManager.getInstance().getAction(actionId)
            action?.templatePresentation?.text?.takeIf { it.isNotBlank() }
        }.onFailure { e ->
            LOG.debug("Could not resolve action display name for '$actionId': ${e.message}")
        }.getOrNull()
    }

    /**
     * Extracts the display text from a JList item using its renderer.
     */
    private fun extractListItemDisplayText(list: JList<*>, item: Any?, index: Int): String? {
        if (item == null) return null

        runCatching {
            @Suppress("UNCHECKED_CAST")
            val renderer = list.cellRenderer as? ListCellRenderer<Any?>
            if (renderer != null) {
                @Suppress("UNCHECKED_CAST")
                val renderedComponent = renderer.getListCellRendererComponent(
                    list as JList<Any?>,
                    item,
                    index,
                    false,
                    false
                )

                val text = extractTextFromRenderedComponent(renderedComponent)
                if (!text.isNullOrBlank()) {
                    return text
                }
            }
        }.onFailure { e ->
            LOG.debug("Error extracting list item display text via renderer: ${e.message}")
        }

        extractDisplayNameViaReflection(item, "toString")?.let { return it }

        return item.toString().takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the display text from a JTable cell using its renderer.
     */
    private fun extractTableCellDisplayText(table: JTable, row: Int, column: Int): String? {
        val modelValue = runCatching { table.getValueAt(row, column) }.getOrNull()

        runCatching {
            val renderer = table.getCellRenderer(row, column)
            val renderedComponent = table.prepareRenderer(renderer, row, column)

            val text = extractTextFromRenderedComponent(renderedComponent)
            if (!text.isNullOrBlank()) {
                return text
            }
        }.onFailure { e ->
            LOG.debug("Error extracting table cell display text via renderer: ${e.message}")
        }

        if (modelValue != null) {
            extractDisplayNameViaReflection(modelValue)?.let { return it }
        }

        val stringValue = modelValue?.toString()
        return if (stringValue != null && !looksLikeObjectReference(stringValue)) {
            stringValue.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    /**
     * Extracts text from a rendered component.
     */
    private fun extractTextFromRenderedComponent(component: Component): String? {
        return when (component) {
            is JLabel -> component.text?.removeHtmlTags()?.takeIf { it.isNotBlank() }
            is AbstractButton -> component.text?.removeHtmlTags()?.takeIf { it.isNotBlank() }
            is SimpleColoredComponent -> {
                runCatching {
                    component.getCharSequence(false).toString().takeIf { it.isNotBlank() }
                }.getOrNull()
            }

            is Container -> {
                for (child in component.components) {
                    val text = extractTextFromRenderedComponent(child)
                    if (!text.isNullOrBlank()) return text
                }
                null
            }

            else -> null
        }
    }

    /**
     * Checks if a string looks like a default toString() object reference.
     */
    private fun looksLikeObjectReference(str: String): Boolean {
        return str.matches(RegexPatterns.OBJECT_REFERENCE)
    }
}
