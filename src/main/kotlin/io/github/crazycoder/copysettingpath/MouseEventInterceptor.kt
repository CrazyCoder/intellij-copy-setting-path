package io.github.crazycoder.copysettingpath

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.TextTransferable
import io.github.crazycoder.copysettingpath.actions.CopySettingPath
import io.github.crazycoder.copysettingpath.path.MenuPathExtractor
import java.awt.AWTEvent
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Intercepts mouse events to prevent component activation (checkbox toggle, button press, etc.)
 * when the user performs Ctrl+Click (or Cmd+Click on macOS) to copy the setting path.
 *
 * The IntelliJ Platform processes mouse shortcuts on MOUSE_RELEASED, but by that time
 * the MOUSE_PRESSED event has already been dispatched to the component. This interceptor
 * blocks the MOUSE_PRESSED event when the CopySettingPath shortcut is active, preventing
 * unwanted side effects while still allowing the action to trigger on MOUSE_RELEASED.
 *
 * For menu items, the interceptor also handles MOUSE_RELEASED to copy the menu path
 * before the menu action is performed and the menu closes.
 *
 * Registered via [CopySettingPathAppLifecycleListener] when the application frame is created,
 * ensuring it's initialized early before any dialogs can be opened.
 */
@Service(Service.Level.APP)
class MouseEventInterceptor : Disposable {

    companion object {
        private const val COPY_OPTIONS_PATH_ACTION_ID = "CopySettingPath"
        private const val MOUSE_INTERCEPT_SETTING_ID = "copy.setting.path.mouse.intercept"

        @JvmStatic
        fun getInstance(): MouseEventInterceptor = service()
    }

    @Volatile
    private var isRegistered = false

    /**
     * Tracks whether we blocked a MOUSE_PRESSED event on a menu component.
     * If true, we should handle the subsequent MOUSE_RELEASED to copy the path.
     */
    @Volatile
    private var pendingMenuCopy: Component? = null

    /**
     * Registers the event interceptor with the IDE event queue.
     * Called from [CopySettingPathAppLifecycleListener.appFrameCreated].
     */
    fun register() {
        if (isRegistered) return
        synchronized(this) {
            if (isRegistered) return
            isRegistered = true

            IdeEventQueue.getInstance().addDispatcher(
                { event -> interceptMouseEvent(event) },
                this
            )
            LOG.debug { "MouseEventInterceptor registered for CopySettingPath" }
        }
    }

    /**
     * Intercepts mouse events and blocks MOUSE_PRESSED if it matches
     * the CopySettingPath shortcut, preventing component activation.
     *
     * For menu components, also intercepts MOUSE_RELEASED to copy the menu path
     * before the normal menu action is performed.
     *
     * This behavior is controlled by the "copy.setting.path.mouse.intercept" advanced setting.
     * When disabled (default), the interceptor does not block any events.
     *
     * @param event The AWT event to process.
     * @return true if the event should be blocked (not dispatched further), false otherwise.
     */
    private fun interceptMouseEvent(event: AWTEvent): Boolean {
        // Check if the feature is enabled in Advanced Settings
        if (!AdvancedSettings.getBoolean(MOUSE_INTERCEPT_SETTING_ID)) return false

        if (event !is MouseEvent) return false

        return when (event.id) {
            MouseEvent.MOUSE_PRESSED -> handleMousePressed(event)
            MouseEvent.MOUSE_RELEASED -> handleMouseReleased(event)
            else -> false
        }
    }

    /**
     * Handles MOUSE_PRESSED events.
     * Blocks the event if it matches the CopySettingPath shortcut.
     * For menu components, also records that we need to copy the path on release.
     */
    private fun handleMousePressed(event: MouseEvent): Boolean {
        // Check if the modifier keys match our shortcut
        if (!isCopySettingPathShortcut(event)) {
            pendingMenuCopy = null
            return false
        }

        // Find the component at the mouse location
        val component = findComponentAt(event)

        // Check if this is a menu component
        if (MenuPathExtractor.isMenuComponent(component)) {
            // Record the menu component for path extraction on MOUSE_RELEASED
            pendingMenuCopy = component
            LOG.debug { "Blocking MOUSE_PRESSED on menu component for CopySettingPath" }
            return true
        }

        // For non-menu components, just block MOUSE_PRESSED as before
        // The MOUSE_RELEASED will still trigger our action via the normal shortcut mechanism
        pendingMenuCopy = null
        LOG.debug { "Blocking MOUSE_PRESSED to prevent component activation for CopySettingPath" }
        return true
    }

    /**
     * Handles MOUSE_RELEASED events.
     * For menu components where we blocked MOUSE_PRESSED, this extracts and copies the menu path.
     */
    private fun handleMouseReleased(event: MouseEvent): Boolean {
        val menuComponent = pendingMenuCopy
        pendingMenuCopy = null

        if (menuComponent == null) {
            // No pending menu copy - let normal processing continue
            return false
        }

        // Check if the modifier keys still match our shortcut
        if (!isCopySettingPathShortcut(event)) {
            return false
        }

        // Extract and copy the menu path
        copyMenuPath(menuComponent)

        // Consume the event to prevent the menu action from executing
        LOG.debug { "Blocking MOUSE_RELEASED on menu component - path copied" }
        return true
    }

    /**
     * Extracts the menu path and copies it to the clipboard.
     */
    private fun copyMenuPath(component: Component) {
        val separator = CopySettingPath.getPathSeparator()
        val path = MenuPathExtractor.buildMenuPath(component, separator)

        if (path != null) {
            val result = trimFinalResult(StringBuilder(path))
            LOG.debug { "Copying menu path: $result" }
            CopyPasteManager.getInstance().setContents(TextTransferable(result, result))
        } else {
            LOG.debug { "Could not extract menu path from component: $component" }
        }
    }

    /**
     * Finds the component at the mouse event location.
     */
    private fun findComponentAt(event: MouseEvent): Component? {
        val source = event.source as? Component ?: return null
        return SwingUtilities.getDeepestComponentAt(source, event.x, event.y)
    }

    /**
     * Checks if the mouse event matches the CopySettingPath action shortcut.
     */
    private fun isCopySettingPathShortcut(event: MouseEvent): Boolean {
        // Only handle left mouse button
        if (event.button != MouseEvent.BUTTON1) return false

        val modifiersEx = event.modifiersEx

        // Check for Ctrl (Windows/Linux) or Cmd (macOS) modifier
        val expectedModifier = if (SystemInfo.isMac) {
            InputEvent.META_DOWN_MASK
        } else {
            InputEvent.CTRL_DOWN_MASK
        }

        // The modifiersEx includes BUTTON1_DOWN_MASK during press, so we need to mask it out
        val relevantModifiers = modifiersEx and (
                InputEvent.CTRL_DOWN_MASK or
                        InputEvent.META_DOWN_MASK or
                        InputEvent.SHIFT_DOWN_MASK or
                        InputEvent.ALT_DOWN_MASK
                )

        if (relevantModifiers != expectedModifier) return false

        // Verify that the shortcut is actually registered for CopySettingPath action
        return isShortcutRegisteredForAction(event)
    }

    /**
     * Verifies that the current mouse shortcut is registered for the CopySettingPath action.
     */
    private fun isShortcutRegisteredForAction(event: MouseEvent): Boolean {
        val keymapManager = KeymapManager.getInstance() ?: return false
        val keymap = keymapManager.activeKeymap

        // Create a shortcut matching the event (single click, with modifiers)
        val shortcut = MouseShortcut(event.button, event.modifiersEx and MODIFIER_MASK, 1)

        val actionIds = keymap.getActionIds(shortcut)
        return COPY_OPTIONS_PATH_ACTION_ID in actionIds
    }

    override fun dispose() {
        // Dispatcher is automatically removed when this Disposable is disposed
        LOG.debug { "MouseEventInterceptor disposed" }
    }
}

/**
 * Mask for extracting modifier keys (excluding button down masks).
 */
private const val MODIFIER_MASK = InputEvent.CTRL_DOWN_MASK or
        InputEvent.META_DOWN_MASK or
        InputEvent.SHIFT_DOWN_MASK or
        InputEvent.ALT_DOWN_MASK or
        InputEvent.ALT_GRAPH_DOWN_MASK
