package io.github.crazycoder.copysettingpath.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.ui.TextTransferable
import io.github.crazycoder.copysettingpath.LOG
import io.github.crazycoder.copysettingpath.PathSeparator
import io.github.crazycoder.copysettingpath.path.PathBuilder
import io.github.crazycoder.copysettingpath.trimFinalResult

/**
 * Action that copies the full navigation path to the currently focused UI setting.
 *
 * This action allows users to copy paths like "Settings | Editor | Code Style | Java"
 * from IDE dialogs to the clipboard. It works with:
 * - Settings dialogs (via breadcrumb extraction)
 * - Project Structure dialog (IntelliJ IDEA and EDU products)
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
        e.presentation.isEnabled = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val src = e.getData(PlatformDataKeys.CONTEXT_COMPONENT) ?: return
        val separator = getPathSeparator()

        val path = PathBuilder.buildPath(src, e, separator) ?: return
        val result = trimFinalResult(StringBuilder(path))

        LOG.debug("Selected path: $result")
        e.inputEvent?.consume()
        CopyPasteManager.getInstance().setContents(TextTransferable(result, result))
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
