@file:Suppress("UNCHECKED_CAST")

package com.intellij.plugin.copySettingPath

import com.intellij.ui.SimpleColoredComponent
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.*
import javax.swing.text.JTextComponent
import kotlin.math.min

/**
 * Utility functions for finding adjacent components and extracting their values.
 *
 * In Settings dialogs, labels ending with ":" are typically followed by input components
 * like combo boxes, text fields, or checkboxes. These functions help locate and extract
 * values from such adjacent components.
 */

/**
 * Finds the adjacent component that follows the given source component.
 *
 * In typical Settings panels, labels ending with ":" are followed by input components
 * like combo boxes, text fields, or checkboxes. This function traverses the parent
 * container to find such adjacent components.
 *
 * @param src The source component (typically a JLabel ending with ":").
 * @return The adjacent component, or null if not found.
 */
fun findAdjacentComponent(src: Component): Component? {
    val parent = src.parent ?: return null

    // Priority 1: If source is a JLabel with labelFor set, return that component
    if (src is JLabel) {
        val labelFor = src.labelFor
        if (labelFor != null && isValueComponent(labelFor)) {
            return labelFor
        }
    }

    // Get source bounds for spatial alignment checks
    val srcScreenBounds = getScreenBounds(src)

    // Priority 2: Look for the next visible value component among siblings
    val components = parent.components
    val srcIndex = components.indexOf(src)
    if (srcIndex >= 0 && srcScreenBounds != null) {
        for (i in (srcIndex + 1) until components.size) {
            val nextComponent = components[i]
            if (!nextComponent.isVisible) continue

            if (isValueComponent(nextComponent)) {
                if (isOnSameRow(srcScreenBounds, nextComponent)) {
                    return nextComponent
                }
            }

            if (nextComponent is Container) {
                val valueComp = findValueComponentIn(nextComponent)
                if (valueComp != null && isOnSameRow(srcScreenBounds, valueComp)) {
                    return valueComp
                }
            }
        }
    }

    // Priority 3: Try to find a component immediately to the right based on spatial position
    return findNearbyValueComponent(src, parent)
}

/**
 * Extracts the display value from a component.
 *
 * Supports various component types commonly found in Settings dialogs:
 * - JComboBox: returns the rendered display text (not raw value)
 * - JButton (ComboBoxButton): returns the button text
 * - JTextField: returns the text content (empty string if empty)
 * - JCheckBox/JRadioButton: returns the selection state or text
 * - JSpinner: returns the current value
 *
 * @param component The component to extract value from.
 * @return The display value as a string, or null if not extractable.
 */
fun extractComponentValue(component: Component): String? {
    return when (component) {
        is JComboBox<*> -> extractComboBoxDisplayText(component)
        is JButton -> component.text?.takeIf { it.isNotBlank() }
        is JTextField -> component.text ?: ""
        is JCheckBox -> {
            if (component.text.isNullOrBlank()) {
                if (component.isSelected) "Enabled" else "Disabled"
            } else {
                component.text
            }
        }

        is JRadioButton -> component.text?.takeIf { component.isSelected }
        is JSpinner -> component.value?.toString()
        is JSlider -> component.value.toString()
        is JTextComponent -> component.text ?: ""
        else -> extractValueViaReflection(component)
    }
}

// ============================================================================
// Private Helper Functions
// ============================================================================

/**
 * Recursively searches a container for a value component.
 */
private fun findValueComponentIn(container: Component): Component? {
    if (isValueComponent(container)) return container

    if (container is Container) {
        for (child in container.components) {
            if (!child.isVisible) continue
            if (isValueComponent(child)) return child
            if (child is Container) {
                val found = findValueComponentIn(child)
                if (found != null) return found
            }
        }
    }
    return null
}

/**
 * Checks if a component is a value-bearing component (not just a label or spacer).
 * Excludes large components like panels, text areas, and descriptions.
 */
private fun isValueComponent(component: Component): Boolean {
    // Exclude large text components
    if (component.height > LayoutConstants.MAX_VALUE_COMPONENT_HEIGHT ||
        component.width > LayoutConstants.MAX_VALUE_COMPONENT_WIDTH
    ) {
        val className = component.javaClass.simpleName
        if (!className.contains("ComboBox", ignoreCase = true) &&
            !className.contains("TextField", ignoreCase = true)
        ) {
            return false
        }
    }

    // Exclude text areas and editor panes (used for descriptions)
    if (component is JTextArea || component is JEditorPane) {
        return false
    }

    return when (component) {
        is JComboBox<*> -> true
        is JTextField -> true
        is JCheckBox -> true
        is JRadioButton -> true
        is JSpinner -> true
        is JSlider -> true
        is JButton -> component.javaClass.simpleName.contains("ComboBoxButton", ignoreCase = true)
        else -> {
            val className = component.javaClass.simpleName
            (className.contains("ComboBoxButton", ignoreCase = true) ||
                    className.contains("ComboBox", ignoreCase = true)) &&
                    !className.contains("Panel", ignoreCase = true)
        }
    }
}

/**
 * Finds a nearby value component based on spatial proximity.
 */
private fun findNearbyValueComponent(src: Component, parent: Container): Component? {
    val srcScreenBounds = getScreenBounds(src) ?: return null

    var bestCandidate: Component? = null
    var bestDistance = Int.MAX_VALUE

    var currentContainer: Container? = parent
    var searchDepth = 0

    while (currentContainer != null && searchDepth < LayoutConstants.MAX_SEARCH_DEPTH) {
        val candidate = findBestCandidateInContainer(src, srcScreenBounds, currentContainer, bestDistance)
        if (candidate != null && candidate.second < bestDistance) {
            bestCandidate = candidate.first
            bestDistance = candidate.second
        }

        if (bestCandidate != null && bestDistance < LayoutConstants.GOOD_CANDIDATE_DISTANCE) {
            break
        }

        currentContainer = currentContainer.parent
        searchDepth++
    }

    return bestCandidate
}

/**
 * Gets the screen bounds of a component, or null if not available.
 */
private fun getScreenBounds(component: Component): Rectangle? {
    return runCatching {
        val location = component.locationOnScreen
        Rectangle(location.x, location.y, component.width, component.height)
    }.getOrNull()
}

/**
 * Checks if a component is on the same visual row as the source.
 */
private fun isOnSameRow(srcBounds: Rectangle, component: Component): Boolean {
    val compBounds = getScreenBounds(component) ?: return false

    val srcCenterY = srcBounds.y + srcBounds.height / 2
    val compCenterY = compBounds.y + compBounds.height / 2
    val maxCenterYDiff = min(srcBounds.height, compBounds.height) / 2 + LayoutConstants.ROW_ALIGNMENT_TOLERANCE

    return kotlin.math.abs(srcCenterY - compCenterY) <= maxCenterYDiff
}

/**
 * Finds the best value component candidate within a container.
 */
private fun findBestCandidateInContainer(
    src: Component,
    srcScreenBounds: Rectangle,
    container: Container,
    currentBestDistance: Int
): Pair<Component, Int>? {
    var bestCandidate: Component? = null
    var bestDistance = currentBestDistance

    val srcRight = srcScreenBounds.x + srcScreenBounds.width
    val srcTop = srcScreenBounds.y
    val srcBottom = srcScreenBounds.y + srcScreenBounds.height

    for (component in container.components) {
        if (component === src || !component.isVisible) continue

        val valueComponent = if (isValueComponent(component)) {
            component
        } else if (component is Container) {
            findValueComponentIn(component)
        } else {
            null
        }

        if (valueComponent != null) {
            val compScreenBounds = getScreenBounds(valueComponent) ?: continue
            val compLeft = compScreenBounds.x
            val compTop = compScreenBounds.y
            val compBottom = compScreenBounds.y + compScreenBounds.height
            val compHeight = compScreenBounds.height

            // Component must be to the right of the source
            if (compLeft >= srcRight - LayoutConstants.HORIZONTAL_OVERLAP_TOLERANCE) {
                val horizontalDist = compLeft - srcRight

                val srcCenterY = (srcTop + srcBottom) / 2
                val compCenterY = (compTop + compBottom) / 2
                val srcHeight = srcBottom - srcTop
                val maxCenterYDiff = min(srcHeight, compHeight) / 2 + LayoutConstants.ROW_ALIGNMENT_TOLERANCE
                val centerYDiff = kotlin.math.abs(srcCenterY - compCenterY)

                if (centerYDiff <= maxCenterYDiff) {
                    if (horizontalDist < bestDistance) {
                        bestDistance = horizontalDist
                        bestCandidate = valueComponent
                    }
                }
            }
        }

        // Recursively search in child containers
        if (component is Container && valueComponent == null && component !== src.parent) {
            val childResult = findBestCandidateInContainer(src, srcScreenBounds, component, bestDistance)
            if (childResult != null && childResult.second < bestDistance) {
                bestCandidate = childResult.first
                bestDistance = childResult.second
            }
        }
    }

    return bestCandidate?.let { Pair(it, bestDistance) }
}

/**
 * Extracts the display text from a JComboBox using its renderer.
 */
private fun extractComboBoxDisplayText(comboBox: JComboBox<*>): String? {
    val selectedItem = comboBox.selectedItem ?: return null
    val selectedIndex = comboBox.selectedIndex

    runCatching {
        val renderer = comboBox.renderer as? ListCellRenderer<Any?>
        if (renderer != null) {
            val renderedComponent = renderer.getListCellRendererComponent(
                JList<Any?>(),
                selectedItem,
                selectedIndex,
                false,
                false
            )

            val text = extractComboBoxRenderedText(renderedComponent)
            if (!text.isNullOrBlank()) {
                return text
            }
        }
    }

    // Fallback: try common interfaces
    extractDisplayNameViaReflection(selectedItem, "getPresentableText")?.let { return it }

    return selectedItem.toString()
}

/**
 * Extracts text from a rendered combo box component.
 */
private fun extractComboBoxRenderedText(component: Component): String? {
    return when (component) {
        is JLabel -> component.text?.takeIf { it.isNotBlank() }
        is AbstractButton -> component.text?.takeIf { it.isNotBlank() }
        is SimpleColoredComponent -> {
            runCatching {
                component.getCharSequence(false).toString().takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        is Container -> {
            for (child in component.components) {
                val text = extractComboBoxRenderedText(child)
                if (!text.isNullOrBlank()) return text
            }
            null
        }

        else -> null
    }
}

/**
 * Attempts to extract a value from a component using reflection.
 */
private fun extractValueViaReflection(component: Component): String? {
    val methodsToTry = listOf("getSelectedItem", "getText", "getValue", "getSelectedValue")

    for (methodName in methodsToTry) {
        runCatching {
            val method = component.javaClass.getMethod(methodName)
            method.isAccessible = true
            val result = method.invoke(component)
            result?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }

    return null
}
