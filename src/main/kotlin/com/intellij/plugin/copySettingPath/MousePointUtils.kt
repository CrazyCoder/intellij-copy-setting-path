package com.intellij.plugin.copySettingPath

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.ui.treeStructure.treetable.TreeTable
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.JTable
import javax.swing.SwingUtilities

/**
 * Utility functions for detecting mouse position relative to UI components.
 *
 * These functions are used to determine which row, column, or item
 * the user clicked on when triggering the Copy Setting Path action.
 */

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
 * Detects the row at the mouse pointer position in a JTable.
 *
 * @param table The JTable component.
 * @param e The action event containing mouse information.
 * @return The row index at the mouse position, or -1 if not found.
 */
fun detectRowFromMousePoint(table: JTable, e: AnActionEvent): Int {
    val point = getConvertedMousePoint(e, table) ?: return -1
    val rowAtPoint = table.rowAtPoint(point)
    return if (rowAtPoint in 0 until table.rowCount) rowAtPoint else -1
}

/**
 * Detects the column at the mouse pointer position in a JTable.
 *
 * @param table The JTable component.
 * @param e The action event containing mouse information.
 * @return The column index at the mouse position, or -1 if not found.
 */
fun detectColumnFromMousePoint(table: JTable, e: AnActionEvent): Int {
    val point = getConvertedMousePoint(e, table) ?: return -1
    val columnAtPoint = table.columnAtPoint(point)
    return if (columnAtPoint in 0 until table.columnCount) columnAtPoint else -1
}

/**
 * Detects the list item index at the mouse pointer position in a JList.
 *
 * @param list The JList component.
 * @param e The action event containing mouse information.
 * @return The list index at the mouse position, or -1 if not found.
 */
fun detectListIndexFromMousePoint(list: JList<*>, e: AnActionEvent): Int {
    val point = getConvertedMousePoint(e, list) ?: return -1
    val indexAtPoint = list.locationToIndex(point)
    // locationToIndex returns the closest index, so we need to verify the point is actually within the cell
    if (indexAtPoint >= 0 && indexAtPoint < list.model.size) {
        val cellBounds = list.getCellBounds(indexAtPoint, indexAtPoint)
        if (cellBounds != null && cellBounds.contains(point)) {
            return indexAtPoint
        }
    }
    return -1
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
