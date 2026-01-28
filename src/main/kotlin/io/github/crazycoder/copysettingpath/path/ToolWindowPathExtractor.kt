package io.github.crazycoder.copysettingpath.path

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import io.github.crazycoder.copysettingpath.appendItem
import java.awt.Component

/**
 * Extracts navigation paths from tool windows.
 *
 * This extractor handles path building for tool windows like Project, Terminal,
 * Run, Debug, Problems, etc. It uses the public IntelliJ Platform APIs to access
 * tool window information.
 *
 * Example paths produced:
 * - `Project | intellij-copy-options-path | src | main | kotlin`
 * - `Terminal | Local`
 * - `Run | MyApp | Console`
 * - `Debug | MyApp | Variables`
 */
object ToolWindowPathExtractor {

    /**
     * Builds the path for a component within a tool window.
     *
     * The path is constructed in layers, from outermost to innermost:
     * 1. Tool window name (e.g., "Project", "Terminal", "Run")
     * 2. Content tab name (e.g., session name in Terminal, run config name in Run)
     * 3. Middle path segments from UI hierarchy (tabs within the content, titled borders)
     * 4. Tree/table/list selection path (e.g., file path in Project tree)
     * 5. Component label (for specific UI elements like buttons or fields)
     *
     * @param src The source UI component that was clicked/focused.
     * @param e The action event containing the tool window data context.
     * @param separator The separator to use between path components.
     * @return The built path string, or null if not in a tool window context.
     */
    fun buildPath(src: Component, e: AnActionEvent?, separator: String): String? {
        // Get tool window from data context - this is the public API entry point
        val toolWindow = e?.getData(PlatformDataKeys.TOOL_WINDOW) ?: return null

        val path = StringBuilder()

        // 1. Add tool window name (the title shown in the stripe button on the side)
        appendItem(path, toolWindow.stripeTitle, separator)

        // 2. Add selected content tab name if present and different from tool window name
        //    Tool windows can have multiple content tabs (e.g., Terminal has "Local", "SSH" tabs;
        //    Run tool window has tabs for each run configuration)
        val selectedContent = toolWindow.contentManager.selectedContent
        val contentTabName = selectedContent?.tabName?.takeIf { it.isNotBlank() }
            ?: selectedContent?.displayName?.takeIf { it.isNotBlank() }
        if (!contentTabName.isNullOrBlank() && contentTabName != toolWindow.stripeTitle) {
            appendItem(path, contentTabName, separator)
        }

        // 3. Add middle path segments from UI hierarchy (JBTabs, JTabbedPane, TitledBorder)
        //    These are nested tabs or panels within the tool window content
        SettingsPathExtractor.appendMiddlePath(src, null, path, separator)

        // 4. Add tree/table/list selection path (e.g., Project tree file path, table row)
        TreeTablePathExtractor.appendPath(src, e, path, separator)

        // 5. Add component label if the source is a labeled UI element
        ComponentLabelExtractor.appendComponentLabel(src, path, separator)

        return if (path.isEmpty()) null else path.toString()
    }
}
