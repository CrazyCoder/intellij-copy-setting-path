package com.intellij.plugin.copySettingPath.path

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ex.SingleConfigurableEditor
import com.intellij.openapi.options.newEditor.SettingsDialog
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.plugin.copySettingPath.*
import java.awt.Component

/**
 * Main orchestration class for building setting paths.
 *
 * This class detects the dialog type and delegates to the appropriate handler:
 * - Settings dialog: Uses SettingsPathExtractor
 * - Project Structure dialog: Uses ProjectStructurePathHandler
 * - Generic dialogs: Uses GenericDialogPathHandler
 *
 * The path building process follows IntelliJ's CopySettingsPathAction pattern:
 * 1. Get base path from SettingsEditor.getPathNames()
 * 2. Walk up hierarchy for tabs and titled borders
 * 3. Add component label via labeledBy property
 */
object PathBuilder {

    /**
     * Builds the complete setting path for the given source component.
     *
     * @param src The source UI component.
     * @param e The action event containing context information.
     * @param separator The separator to use between path components.
     * @return The built path string, or null if path cannot be determined.
     */
    fun buildPath(src: Component, e: AnActionEvent?, separator: String): String? {
        val dialog = DialogWrapper.findInstance(src) ?: return null
        val path = StringBuilder()

        // Build base path based on dialog type
        when (dialog) {
            is SettingsDialog -> SettingsPathExtractor.appendSettingsPath(src, path, separator)
            else -> appendGenericDialogPath(dialog, src, path, separator)
        }

        // Add tree/table/list path if applicable
        appendTreeOrTablePath(src, e, path, separator)

        // Add source component label
        ComponentLabelExtractor.appendComponentLabel(src, path)

        return if (path.isEmpty()) null else path.toString()
    }

    /**
     * Appends path information from a generic (non-Settings) dialog.
     */
    private fun appendGenericDialogPath(
        dialog: DialogWrapper,
        src: Component,
        path: StringBuilder,
        separator: String
    ) {
        appendItem(path, dialog.title, separator)

        if (dialog is SingleConfigurableEditor && ProjectStructurePathHandler.isSupported()) {
            ProjectStructurePathHandler.appendPath(dialog.configurable, path, separator)
        }

        // Add middle path (tabs, titled borders)
        SettingsPathExtractor.appendMiddlePath(src, null, path, separator)
    }

    /**
     * Appends tree/table/list path information if the source component is applicable.
     */
    private fun appendTreeOrTablePath(
        src: Component,
        e: AnActionEvent?,
        path: StringBuilder,
        separator: String
    ) {
        TreeTablePathExtractor.appendPath(src, e, path, separator)
    }
}
