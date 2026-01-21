package com.intellij.plugin.CopySettingPath

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.util.SystemInfo
import java.awt.AWTEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

/**
 * Intercepts mouse events to prevent component activation (checkbox toggle, button press, etc.)
 * when the user performs Ctrl+Click (or Cmd+Click on macOS) to copy the option path.
 *
 * The IntelliJ Platform processes mouse shortcuts on MOUSE_RELEASED, but by that time
 * the MOUSE_PRESSED event has already been dispatched to the component. This interceptor
 * blocks the MOUSE_PRESSED event when the CopySettingPath shortcut is active, preventing
 * unwanted side effects while still allowing the action to trigger on MOUSE_RELEASED.
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
            LOG.info("MouseEventInterceptor registered for CopySettingPath")
        }
    }

    /**
     * Intercepts mouse events and blocks MOUSE_PRESSED if it matches
     * the CopySettingPath shortcut, preventing component activation.
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
        if (event.id != MouseEvent.MOUSE_PRESSED) return false

        // Check if the modifier keys match our shortcut
        if (!isCopySettingPathShortcut(event)) return false

        // Block the MOUSE_PRESSED event to prevent component activation
        // Return true to tell IdeEventQueue: "I handled this, don't dispatch further"
        // The MOUSE_RELEASED will still trigger our action via the normal shortcut mechanism
        LOG.info("Blocking MOUSE_PRESSED to prevent component activation for CopySettingPath")
        return true
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
        LOG.info("MouseEventInterceptor disposed")
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
