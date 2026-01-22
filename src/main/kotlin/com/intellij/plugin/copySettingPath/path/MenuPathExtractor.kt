package com.intellij.plugin.copySettingPath.path

import com.intellij.openapi.actionSystem.impl.ActionMenu
import com.intellij.openapi.actionSystem.impl.ActionMenuItem
import com.intellij.plugin.copySettingPath.removeHtmlTags
import java.awt.Component
import javax.swing.JMenu
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
                component is JMenuItem ||
                component is JMenu
    }

    /**
     * Builds the full menu path from a menu component to the menu bar root.
     *
     * @param component The menu component (ActionMenuItem, ActionMenu, JMenuItem, or JMenu).
     * @param separator The separator to use between path segments.
     * @return The built path string, or null if path cannot be determined.
     */
    fun buildMenuPath(component: Component, separator: String): String? {
        if (!isMenuComponent(component)) return null

        val pathSegments = mutableListOf<String>()

        // Get the text of the target component
        val targetText = getMenuComponentText(component)
        if (!targetText.isNullOrBlank()) {
            pathSegments.add(targetText)
        }

        // Walk up the menu hierarchy
        var current: Component? = component.parent
        while (current != null) {
            when (current) {
                is JMenuBar -> {
                    // Reached the top - we're done
                    break
                }

                is JPopupMenu -> {
                    // Get the invoker (the menu that opened this popup)
                    val invoker = current.invoker
                    if (invoker != null) {
                        val invokerText = getMenuComponentText(invoker)
                        if (!invokerText.isNullOrBlank()) {
                            pathSegments.add(invokerText)
                        }
                        current = invoker.parent
                    } else {
                        current = current.parent
                    }
                }

                is JMenu, is ActionMenu -> {
                    // This shouldn't happen in normal flow (menus are inside popups)
                    // but handle it just in case
                    val menuText = getMenuComponentText(current)
                    if (!menuText.isNullOrBlank()) {
                        pathSegments.add(menuText)
                    }
                    current = current.parent
                }

                else -> {
                    current = current.parent
                }
            }
        }

        if (pathSegments.isEmpty()) return null

        // Reverse to get root-to-leaf order and join
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

            is JMenu -> {
                component.text?.removeHtmlTags()?.trim()
            }

            else -> null
        }
    }

    /**
     * Finds the menu component at the deepest level in the hierarchy.
     * Used when we have a mouse event and need to find the actual menu item.
     *
     * @param component The component to start from.
     * @return The deepest menu component, or null if not found.
     */
    fun findDeepestMenuComponent(component: Component?): Component? {
        var current = component
        while (current != null) {
            if (isMenuComponent(current)) {
                return current
            }
            current = current.parent
        }
        return null
    }
}
