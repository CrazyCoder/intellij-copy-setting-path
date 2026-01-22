@file:Suppress("UNCHECKED_CAST")

package com.intellij.plugin.copySettingPath

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ui.SimpleColoredComponent
import java.awt.Component
import java.awt.Container
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Utility functions for extracting display text from UI components.
 *
 * These functions handle the complex task of getting human-readable text
 * from various IntelliJ UI components like trees, tables, lists, and
 * custom renderers.
 */

/**
 * Appends tree node path segments to the path builder.
 *
 * For each node in the tree path, extracts its text representation.
 * The extraction logic handles several cases:
 * 1. DefaultMutableTreeNode with String userObject (e.g., Keymap action IDs) - resolves to display name via ActionManager
 * 2. DefaultMutableTreeNode with Group userObject - extracts group name
 * 3. Other nodes - uses toString() or myText field via reflection
 *
 * @param treePath Array of tree path nodes.
 * @param path StringBuilder to append path segments to.
 * @param separator The separator to use between path components.
 */
fun appendTreePath(treePath: Array<out Any>, path: StringBuilder, separator: String = PathConstants.SEPARATOR) {
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
 * Handles special cases like Keymap dialog where tree nodes contain action IDs
 * instead of display names. For such nodes, resolves the action ID to its
 * human-readable presentation text via ActionManager.
 *
 * @param node The tree path node to extract display text from.
 * @return The display text, or null if it cannot be determined.
 */
private fun extractTreeNodeDisplayText(node: Any): String? {
    // Handle DefaultMutableTreeNode (used by Keymap and other dialogs)
    if (node is DefaultMutableTreeNode) {
        val userObject = node.userObject ?: return null
        return extractDisplayTextFromUserObject(userObject)
    }

    // Fallback: try toString() or myText field
    val pathStr = node.toString()
    return if (pathStr.isEmpty()) {
        extractMyTextField(node)
    } else {
        pathStr
    }
}

/**
 * Extracts display text from a tree node's userObject.
 *
 * Handles various userObject types:
 * - String: Might be an action ID (in Keymap dialog) - resolve via ActionManager
 * - Group: Extract the group name
 * - QuickList: Extract the display name
 * - Other: Use toString()
 *
 * @param userObject The userObject from a DefaultMutableTreeNode.
 * @return The display text for the userObject.
 */
private fun extractDisplayTextFromUserObject(userObject: Any): String? {
    return when (userObject) {
        is String -> {
            // Could be an action ID (e.g., "Github.PullRequest.Changes.MarkNotViewed")
            // Try to resolve it to a display name via ActionManager
            resolveActionDisplayName(userObject) ?: userObject
        }

        else -> {
            // Try common interfaces/methods for display name extraction
            extractDisplayNameViaReflection(userObject) ?: userObject.toString().takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * Resolves an action ID to its display name via ActionManager.
 *
 * This is used for Keymap dialog tree nodes where the userObject is
 * the raw action ID string, but the display should show the action's
 * human-readable name from its template presentation.
 *
 * @param actionId The action ID to resolve.
 * @return The action's display name, or null if it cannot be resolved.
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
 *
 * Lists often use custom renderers to display human-readable text for objects.
 * This function uses the renderer to get the actual displayed text rather than
 * the raw object's toString().
 *
 * @param list The JList containing the item.
 * @param item The item to extract display text from.
 * @param index The index of the item in the list.
 * @return The rendered display text, or null if not extractable.
 */
fun extractListItemDisplayText(list: JList<*>, item: Any?, index: Int): String? {
    if (item == null) return null

    // Try to get the display text from the renderer
    runCatching {
        val renderer = list.cellRenderer as? ListCellRenderer<Any?>
        if (renderer != null) {
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

    // Fallback: try common interfaces for display name
    extractDisplayNameViaReflection(item, "toString")?.let { return it }

    // Final fallback: use toString()
    return item.toString().takeIf { it.isNotBlank() }
}

/**
 * Extracts the display text from a JTable cell using its renderer.
 *
 * Tables often use custom cell renderers to display human-readable text for objects.
 * This is particularly common in IntelliJ settings panels where cells may contain
 * objects like FileColorConfiguration, Color, etc. that have meaningful visual
 * representations but unhelpful toString() output.
 *
 * @param table The JTable containing the cell.
 * @param row The row index of the cell.
 * @param column The column index of the cell.
 * @return The rendered display text, or null if not extractable.
 */
fun extractTableCellDisplayText(table: JTable, row: Int, column: Int): String? {
    // Get the model value first - we'll use this as a fallback
    val modelValue = runCatching { table.getValueAt(row, column) }.getOrNull()

    // Try to get the display text from the renderer
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

    // Fallback: try common interfaces for display name on the model value
    if (modelValue != null) {
        extractDisplayNameViaReflection(modelValue)?.let { return it }
    }

    // Final fallback: use model value's toString() if it doesn't look like an object reference
    val stringValue = modelValue?.toString()
    return if (stringValue != null && !looksLikeObjectReference(stringValue)) {
        stringValue.takeIf { it.isNotBlank() }
    } else {
        null
    }
}

/**
 * Extracts text from a rendered component (handles various IntelliJ renderer types).
 *
 * This handles common IntelliJ renderer component types:
 * - JLabel: direct text extraction
 * - AbstractButton: checkbox, radio button, and other button text
 * - SimpleColoredComponent: uses getCharSequence() for multi-fragment text
 * - ColoredTableCellRenderer: similar to SimpleColoredComponent
 * - Container: recursively searches for text-containing children
 *
 * @param component The rendered component to extract text from.
 * @return The extracted text, or null if not extractable.
 */
fun extractTextFromRenderedComponent(component: Component): String? {
    return when (component) {
        is JLabel -> component.text?.removeHtmlTags()?.takeIf { it.isNotBlank() }
        is AbstractButton -> component.text?.removeHtmlTags()?.takeIf { it.isNotBlank() }
        is SimpleColoredComponent -> {
            runCatching {
                component.getCharSequence(false).toString().takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        is Container -> {
            // Search for text-containing children
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
 *
 * Default toString() typically produces output like "ClassName@hexAddress"
 * which is not useful for display purposes.
 *
 * @param str The string to check.
 * @return True if the string looks like an object reference.
 */
private fun looksLikeObjectReference(str: String): Boolean {
    return str.matches(RegexPatterns.OBJECT_REFERENCE)
}
