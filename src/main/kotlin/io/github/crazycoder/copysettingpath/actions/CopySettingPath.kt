package io.github.crazycoder.copysettingpath.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.TextTransferable
import io.github.crazycoder.copysettingpath.LOG
import io.github.crazycoder.copysettingpath.PathSeparator
import io.github.crazycoder.copysettingpath.path.PathBuilder
import io.github.crazycoder.copysettingpath.path.PopupPathExtractor
import io.github.crazycoder.copysettingpath.showCopiedBalloon
import io.github.crazycoder.copysettingpath.trimFinalResult

/**
 * Action that copies the full navigation path to the currently focused UI element.
 *
 * This action allows users to copy paths like "Settings | Editor | Code Style | Java"
 * from IDE dialogs and tool windows to the clipboard. It works with:
 * - Settings dialogs (via breadcrumb extraction)
 * - Project Structure dialog (IntelliJ IDEA and EDU products)
 * - Tool windows (Project, Terminal, Run, Debug, etc.)
 * - Tree structures, tabs, titled panels, buttons, and labels
 *
 * The action is triggered via Ctrl+Click (Cmd+Click on macOS).
 */
class CopySettingPath : DumbAwareAction() {

    init {
        isEnabledInModalContext = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val src = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        val hasDialog = src != null && DialogWrapper.findInstance(src) != null
        val hasPopup = src != null && PopupPathExtractor.isInPopupContext(src)
        val hasToolWindow = e.getData(PlatformDataKeys.TOOL_WINDOW) != null
        e.presentation.isEnabled = hasDialog || hasPopup || hasToolWindow
    }

    override fun actionPerformed(e: AnActionEvent) {
        val src = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) ?: return
        val separator = getPathSeparator()

        val path = PathBuilder.buildPath(src, e, separator) ?: return
        val result = trimFinalResult(StringBuilder(path))

        LOG.debug("Selected path: $result")
        e.inputEvent?.consume()
        CopyPasteManager.getInstance().setContents(TextTransferable(result, result))
        showCopiedBalloon(result)
    }

    @Suppress("CompanionObjectInExtension")
    companion object {
        /** Advanced setting ID for path separator configuration. */
        private const val PATH_SEPARATOR_SETTING_ID = "copy.setting.path.separator"

        /**
         * Returns the currently configured path separator from Advanced Settings.
         */
        fun getPathSeparator(): String =
            AdvancedSettings.getEnum(PATH_SEPARATOR_SETTING_ID, PathSeparator::class.java).separator
    }
}
