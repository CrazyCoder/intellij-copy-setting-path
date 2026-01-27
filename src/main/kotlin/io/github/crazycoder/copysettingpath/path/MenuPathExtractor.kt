package io.github.crazycoder.copysettingpath.path

import com.intellij.openapi.actionSystem.impl.ActionMenu
import com.intellij.openapi.actionSystem.impl.ActionMenuItem
import io.github.crazycoder.copysettingpath.removeHtmlTags
import java.awt.Component
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * Extracts navigation paths from main menu components.
 *
 * Handles:
 * - ActionMenuItem: Individual menu items (e.g., "Export Settings")
 * - ActionMenu: Submenus (e.g., "Manage IDE Settings")
 * - JMenu/JMenuItem: Standard Swing menu components
 *
 * Path building algorithm:
 * 1. Start from the target menu component
 * 2. Walk up via parent -> JPopupMenu -> getInvoker() -> ActionMenu/JMenu
 * 3. Continue until reaching JMenuBar
 * 4. Collect menu texts, reverse, and join with separator
 *
 * Example output: "File | Manage IDE Settings | Export Settings"
 */
object MenuPathExtractor {

    /**
     * Checks if the component is a menu item or menu that we can extract a path from.
     */
    fun isMenuComponent(component: Component?): Boolean {
        return component is ActionMenuItem ||
                component is ActionMenu ||
                component is JMenuItem
    }

    /**
     * Builds the full menu path from a menu component to the menu bar root.
     *
     * Menu hierarchy structure in IntelliJ/Swing:
     * ```
     * JMenuBar
     *   └── ActionMenu ("File")           <- top-level menu in menu bar
     *         └── JPopupMenu              <- popup shown when menu clicked
     *               ├── ActionMenuItem    <- leaf menu items
     *               └── ActionMenu        <- submenu
     *                     └── JPopupMenu  <- nested popup
     *                           └── ...
     * ```
     *
     * The key insight is that JPopupMenu.getInvoker() returns the ActionMenu that
     * opened the popup, allowing us to traverse up through nested submenus.
     *
     * @param component The menu component (ActionMenuItem, ActionMenu, JMenuItem, or JMenu).
     * @param separator The separator to use between path segments.
     * @return The built path string, or null if path cannot be determined.
     */
    fun buildMenuPath(component: Component, separator: String): String? {
        if (!isMenuComponent(component)) return null

        val pathSegments = mutableListOf<String>()

        // Get the text of the target component (the menu item that was clicked)
        val targetText = getMenuComponentText(component)
        if (!targetText.isNullOrBlank()) {
            pathSegments.add(targetText)
        }

        // Walk up the menu hierarchy using the invoker chain:
        // MenuItem -> JPopupMenu -> (invoker) ActionMenu -> JPopupMenu -> ... -> JMenuBar
        var current: Component? = component.parent
        while (current != null) {
            when (current) {
                is JMenuBar -> {
                    // Reached the menu bar - this is the root, we're done
                    break
                }

                is JPopupMenu -> {
                    // JPopupMenu is the container for menu items when a menu is open.
                    // Its invoker is the ActionMenu/JMenu that opened this popup.
                    val invoker = current.invoker
                    if (invoker != null) {
                        val invokerText = getMenuComponentText(invoker)
                        if (!invokerText.isNullOrBlank()) {
                            pathSegments.add(invokerText)
                        }
                        // Continue from the invoker's parent (skip to next level up)
                        current = invoker.parent
                    } else {
                        current = current.parent
                    }
                }

                is ActionMenu -> {
                    // Direct ActionMenu parent (rare - menus are usually inside JPopupMenu)
                    val menuText = getMenuComponentText(current)
                    if (!menuText.isNullOrBlank()) {
                        pathSegments.add(menuText)
                    }
                    current = current.parent
                }

                else -> {
                    // Skip other container types (panels, etc.)
                    current = current.parent
                }
            }
        }

        if (pathSegments.isEmpty()) return null

        // Segments were collected leaf-to-root, reverse to get root-to-leaf order
        pathSegments.reverse()
        return pathSegments.joinToString(separator)
    }

    /**
     * Extracts the display text from a menu component.
     */
    private fun getMenuComponentText(component: Component?): String? {
        return when (component) {
            is ActionMenuItem -> {
                component.text?.removeHtmlTags()?.trim()
            }

            is ActionMenu -> {
                component.text?.removeHtmlTags()?.trim()
            }

            is JMenuItem -> {
                component.text?.removeHtmlTags()?.trim()
            }

            else -> null
        }
    }

}
