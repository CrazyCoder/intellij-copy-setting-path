package io.github.crazycoder.copysettingpath

import com.intellij.ide.IdeEventQueue
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.KeymapManagerListener
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.keymap.impl.ui.EditKeymapsDialog
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsChangeListener
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.TextTransferable
import io.github.crazycoder.copysettingpath.actions.CopySettingPath
import io.github.crazycoder.copysettingpath.path.MenuPathExtractor
import java.awt.AWTEvent
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Intercepts mouse events to prevent component activation (checkbox toggle, button press, etc.)
 * when the user performs the configured mouse shortcut to copy the setting path.
 *
 * The IntelliJ Platform processes mouse shortcuts on MOUSE_RELEASED, but by that time
 * the MOUSE_PRESSED event has already been dispatched to the component. This interceptor
 * blocks the MOUSE_PRESSED event when the CopySettingPath shortcut is active, preventing
 * unwanted side effects while still allowing the action to trigger on MOUSE_RELEASED.
 *
 * For menu items, the interceptor also handles MOUSE_RELEASED to copy the menu path
 * before the menu action is performed and the menu closes.
 *
 * The interceptor dynamically detects the user's configured shortcut from the keymap and
 * automatically updates when the shortcut is changed. It supports custom shortcuts with
 * different or additional modifiers (e.g., Ctrl+Shift+Click, Ctrl+Alt+Shift+Click).
 *
 * Registered via [CopySettingPathAppLifecycleListener] when the application frame is created,
 * ensuring it's initialized early before any dialogs can be opened.
 */
@Service(Service.Level.APP)
class MouseEventInterceptor : Disposable {

    companion object {
        private const val COPY_OPTIONS_PATH_ACTION_ID = "CopySettingPath"
        private const val MOUSE_INTERCEPT_SETTING_ID = "copy.setting.path.mouse.intercept"
        private const val NOTIFICATION_GROUP_ID = "Copy Setting Path"

        @JvmStatic
        fun getInstance(): MouseEventInterceptor = service()
    }

    @Volatile
    private var isRegistered = false

    /**
     * Cached mouse shortcut for the CopySettingPath action.
     * Updated when the keymap changes or when the action's shortcuts are modified.
     * Only the first mouse shortcut is cached; additional mouse shortcuts are ignored.
     */
    @Volatile
    private var cachedMouseShortcut: MouseShortcut? = null

    /**
     * Tracks whether we blocked a MOUSE_PRESSED event on a menu component.
     * If true, we should handle the subsequent MOUSE_RELEASED to copy the path.
     *
     * Uses AtomicReference for thread-safe get-and-set operations between
     * handleMousePressed and handleMouseReleased calls.
     */
    private val pendingMenuCopy = AtomicReference<Component?>(null)

    /**
     * Strong reference to the keymap listener to prevent garbage collection.
     * The listener is registered with addWeakListener, so we need to keep this reference.
     */
    private var keymapListener: KeymapManagerListener? = null

    /**
     * Registers the event interceptor with the IDE event queue and sets up listeners
     * for keymap and advanced settings changes.
     * Called from [CopySettingPathAppLifecycleListener.appFrameCreated].
     */
    fun register() {
        if (isRegistered) return
        synchronized(this) {
            if (isRegistered) return
            isRegistered = true

            // Initialize the cached shortcut
            updateMouseShortcut()

            // Register event dispatcher
            IdeEventQueue.getInstance().addDispatcher(
                { event -> interceptMouseEvent(event) },
                this
            )

            // Listen for keymap changes - keep strong reference to prevent GC
            keymapListener = object : KeymapManagerListener {
                override fun activeKeymapChanged(keymap: Keymap?) {
                    LOG.debug { "Active keymap changed, updating mouse shortcut" }
                    updateMouseShortcutAndWarn()
                }

                override fun shortcutsChanged(keymap: Keymap, actionIds: Collection<String>, fromSettings: Boolean) {
                    LOG.debug { "Shortcuts changed for actions: $actionIds, fromSettings: $fromSettings" }
                    if (COPY_OPTIONS_PATH_ACTION_ID in actionIds) {
                        LOG.debug { "CopySettingPath shortcut changed, updating cached shortcut" }
                        updateMouseShortcutAndWarn()
                    }
                }
            }
            KeymapManagerEx.getInstanceEx().addWeakListener(keymapListener!!)

            // Listen for advanced settings changes to show warning when mouse intercept is enabled
            ApplicationManager.getApplication().messageBus.connect(this)
                .subscribe(AdvancedSettingsChangeListener.TOPIC, object : AdvancedSettingsChangeListener {
                    override fun advancedSettingChanged(id: String, oldValue: Any, newValue: Any) {
                        if (id == MOUSE_INTERCEPT_SETTING_ID && newValue == true) {
                            checkAndWarnDefaultShortcut()
                        }
                    }
                })

            LOG.debug { "MouseEventInterceptor registered for CopySettingPath" }
        }
    }

    /**
     * Updates the cached mouse shortcut from the active keymap.
     * Only the first mouse shortcut for the action is cached.
     */
    private fun updateMouseShortcut() {
        val keymap = KeymapManagerEx.getInstanceEx().activeKeymap
        cachedMouseShortcut = keymap.getShortcuts(COPY_OPTIONS_PATH_ACTION_ID)
            .filterIsInstance<MouseShortcut>()
            .firstOrNull()

        LOG.debug { "Updated mouse shortcut for CopySettingPath: ${cachedMouseShortcut?.let { formatShortcut(it) } ?: "none"}" }
    }

    /**
     * Updates the cached mouse shortcut and shows a warning if it's the default shortcut
     * and mouse interception is enabled.
     */
    private fun updateMouseShortcutAndWarn() {
        updateMouseShortcut()
        if (AdvancedSettings.getBoolean(MOUSE_INTERCEPT_SETTING_ID)) {
            checkAndWarnDefaultShortcut()
        }
    }

    /**
     * Formats a mouse shortcut for display in log messages.
     */
    private fun formatShortcut(shortcut: MouseShortcut): String {
        return KeymapUtil.getMouseShortcutText(shortcut)
    }

    /**
     * Checks if the current shortcut is the default (Ctrl+Click or Cmd+Click) and shows
     * a warning notification if so, because the default shortcut conflicts with multiple selection.
     */
    private fun checkAndWarnDefaultShortcut() {
        val shortcut = cachedMouseShortcut ?: return

        if (isDefaultShortcut(shortcut)) {
            showDefaultShortcutWarning(shortcut)
        }
    }

    /**
     * Checks if the given shortcut is the default Ctrl+Click (Windows/Linux) or Cmd+Click (macOS).
     * The default shortcut only has the primary modifier without additional modifiers like Shift or Alt.
     */
    private fun isDefaultShortcut(shortcut: MouseShortcut): Boolean {
        if (shortcut.button != MouseEvent.BUTTON1 || shortcut.clickCount != 1) {
            return false
        }

        val modifiers = shortcut.modifiers
        val expectedDefaultModifier = if (SystemInfo.isMac) {
            InputEvent.META_DOWN_MASK
        } else {
            InputEvent.CTRL_DOWN_MASK
        }

        // Check if only the primary modifier is set (no Shift, Alt, etc.)
        return modifiers == expectedDefaultModifier
    }

    /**
     * Shows a warning notification that the default shortcut conflicts with multiple selection.
     */
    private fun showDefaultShortcutWarning(shortcut: MouseShortcut) {
        val currentShortcutText = formatShortcut(shortcut)

        // Suggest an alternative shortcut with an additional modifier
        val suggestedModifier = if (SystemInfo.isMac) {
            InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
        } else {
            InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
        }
        val suggestedShortcut = MouseShortcut(MouseEvent.BUTTON1, suggestedModifier, 1)
        val suggestedShortcutText = formatShortcut(suggestedShortcut)

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                CopySettingPathBundle.message("notification.default.shortcut.warning.title"),
                CopySettingPathBundle.message(
                    "notification.default.shortcut.warning.content",
                    currentShortcutText,
                    suggestedShortcutText
                ),
                NotificationType.WARNING
            )
            .addAction(object : com.intellij.notification.NotificationAction(
                CopySettingPathBundle.message("notification.default.shortcut.warning.action")
            ) {
                override fun actionPerformed(
                    e: com.intellij.openapi.actionSystem.AnActionEvent,
                    notification: com.intellij.notification.Notification
                ) {
                    notification.expire()
                    EditKeymapsDialog(e.project, COPY_OPTIONS_PATH_ACTION_ID).show()
                }
            })

        notification.notify(null)
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
            pendingMenuCopy.set(null)
            return false
        }

        // Find the component at the mouse location
        val component = findComponentAt(event)

        // Check if this is a menu component
        if (MenuPathExtractor.isMenuComponent(component)) {
            // Record the menu component for path extraction on MOUSE_RELEASED
            pendingMenuCopy.set(component)
            LOG.debug {
                "Blocking MOUSE_PRESSED on menu component for CopySettingPath (shortcut: ${
                    cachedMouseShortcut?.let {
                        formatShortcut(
                            it
                        )
                    }
                })"
            }
            return true
        }

        // For non-menu components, just block MOUSE_PRESSED as before
        // The MOUSE_RELEASED will still trigger our action via the normal shortcut mechanism
        pendingMenuCopy.set(null)
        LOG.debug {
            "Blocking MOUSE_PRESSED to prevent component activation for CopySettingPath (shortcut: ${
                cachedMouseShortcut?.let {
                    formatShortcut(
                        it
                    )
                }
            })"
        }
        return true
    }

    /**
     * Handles MOUSE_RELEASED events.
     * For menu components where we blocked MOUSE_PRESSED, this extracts and copies the menu path.
     */
    private fun handleMouseReleased(event: MouseEvent): Boolean {
        // Atomically get and clear the pending menu component
        // If no pending menu copy, let normal processing continue
        val menuComponent = pendingMenuCopy.getAndSet(null) ?: return false

        // Check if the modifier keys still match our shortcut
        if (!isCopySettingPathShortcut(event)) {
            return false
        }

        // Extract and copy the menu path
        copyMenuPath(menuComponent)

        // Consume the event to prevent the menu action from executing
        LOG.debug {
            "Blocking MOUSE_RELEASED on menu component - path copied (shortcut: ${
                cachedMouseShortcut?.let {
                    formatShortcut(
                        it
                    )
                }
            })"
        }
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
            showCopiedBalloon(result)
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
     * Compares the event against the cached mouse shortcut from the keymap.
     */
    private fun isCopySettingPathShortcut(event: MouseEvent): Boolean {
        val shortcut = cachedMouseShortcut ?: return false

        // Check button
        if (event.button != shortcut.button) return false

        // Check click count (we intercept single clicks)
        if (shortcut.clickCount != 1) return false

        // Extract relevant modifiers from the event (excluding button down masks)
        val eventModifiers = event.modifiersEx and MODIFIER_MASK

        // Compare with the shortcut's modifiers
        return eventModifiers == shortcut.modifiers
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
