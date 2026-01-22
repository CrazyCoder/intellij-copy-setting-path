package com.intellij.plugin.copySettingPath

import com.intellij.ui.TitledSeparator
import com.intellij.ui.border.IdeaTitledBorder
import com.intellij.ui.tabs.JBTabs
import java.awt.Component
import java.awt.Container
import java.util.*
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JRadioButton
import javax.swing.JTabbedPane

/**
 * Utility functions for extracting middle path segments from the component hierarchy.
 *
 * Middle path segments include:
 * - Tab names from JBTabs and JTabbedPane
 * - Titled separator group names
 * - Titled border text
 * - Radio button group labels and hierarchy
 */

/**
 * Extracts the middle path segments (tabs, titled borders, and titled separators) from the component hierarchy.
 *
 * This function collects UI elements that provide navigation context within a configurable panel:
 * - Tab names from JBTabs and JTabbedPane (but only within ConfigurableEditor boundary)
 * - Titled separator group names (e.g., "Java" section in Auto Import settings)
 * - Titled border text from panel borders
 *
 * Important: We only collect tabs that are within the ConfigurableEditor boundary to avoid
 * picking up tabs from the outer Settings dialog structure (which would add irrelevant paths
 * like "Project" from the main settings navigation).
 *
 * @param src The source component.
 * @param path StringBuilder to append path segments to.
 * @param separator The separator to use between path components.
 */
fun getMiddlePath(src: Component, path: StringBuilder, separator: String = PathConstants.SEPARATOR) {
    // Find the ConfigurableEditor boundary - we only collect tabs within this boundary
    val configurableEditor = findParentByClassName(src, PathConstants.CONFIGURABLE_EDITOR_CLASS)

    // Collect tabs and titled borders from src up to ConfigurableEditor (exclusive)
    val middlePathItems = ArrayDeque<String>()
    var component: Component? = src

    while (component != null && component !== configurableEditor) {
        collectTabName(component, middlePathItems)
        collectTitledBorder(component, middlePathItems)
        component = component.parent
    }

    // Add collected items in correct order
    for (item in middlePathItems) {
        appendItem(path, item, separator)
    }

    // Add titled separator group name if present
    findPrecedingTitledSeparator(src, configurableEditor)?.let { titledSeparator ->
        appendItem(path, titledSeparator.text, separator)
    }

    // Add radio button group label and parent radio buttons if the source is a radio button
    if (src is JRadioButton) {
        findRadioButtonGroupLabel(src, configurableEditor)?.let { groupLabel ->
            appendItem(path, groupLabel, separator)
        }

        findParentRadioButtons(src, configurableEditor).forEach { parentText ->
            appendItem(path, parentText, separator)
        }
    }
}

/**
 * Collects tab name from JBTabs or JTabbedPane component.
 */
private fun collectTabName(component: Component, items: ArrayDeque<String>) {
    when (component) {
        is JBTabs -> {
            component.selectedInfo?.text?.takeIf { it.isNotEmpty() }?.let {
                items.addFirst(it)
            }
        }

        is JTabbedPane -> {
            val selectedIndex = component.selectedIndex
            if (selectedIndex >= 0 && selectedIndex < component.tabCount) {
                component.getTitleAt(selectedIndex)?.takeIf { it.isNotEmpty() }?.let {
                    items.addFirst(it)
                }
            }
        }
    }
}

/**
 * Collects titled border text from a JComponent.
 */
private fun collectTitledBorder(component: Component, items: ArrayDeque<String>) {
    if (component is JComponent) {
        val border = component.border
        if (border is IdeaTitledBorder) {
            border.title?.takeIf { it.isNotEmpty() }?.let {
                items.addFirst(it)
            }
        }
    }
}

/**
 * Finds the TitledSeparator that visually precedes the given component.
 *
 * TitledSeparators are used in Settings panels to group related options
 * (e.g., "Java" section in Auto Import settings). This function searches
 * only within the boundary component to find the separator that appears
 * before the target component in the visual layout.
 *
 * @param component The component to find the preceding separator for.
 * @param boundary The boundary component (typically ConfigurableEditor) to limit the search.
 * @return The TitledSeparator that precedes the component, or null if not found.
 */
private fun findPrecedingTitledSeparator(component: Component, boundary: Component?): TitledSeparator? {
    val componentY = getAbsoluteY(component)
    val searchContainer = (boundary as? Container) ?: component.parent ?: return null

    var bestSeparator: TitledSeparator? = null
    var bestSeparatorY = Int.MIN_VALUE

    // Use sequence for lazy evaluation - stop early if we find a good match
    findAllComponentsOfType<TitledSeparator>(searchContainer).forEach { separator ->
        if (!separator.isShowing) return@forEach

        val separatorY = getAbsoluteY(separator)
        if (separatorY <= componentY && separatorY > bestSeparatorY) {
            bestSeparator = separator
            bestSeparatorY = separatorY
        }
    }

    return bestSeparator
}

/**
 * Finds the group label for a radio button.
 *
 * In Kotlin UI DSL, radio button groups created with `buttonsGroup(title)` have
 * a JLabel positioned above the radio buttons that serves as the group title.
 *
 * @param radioButton The radio button to find the group label for.
 * @param boundary The boundary component (typically ConfigurableEditor) to limit the search.
 * @return The group label text, or null if not found.
 */
private fun findRadioButtonGroupLabel(radioButton: JRadioButton, boundary: Component?): String? {
    val radioButtonY = getAbsoluteY(radioButton)
    val radioButtonX = getAbsoluteX(radioButton)
    val searchContainer = (boundary as? Container) ?: radioButton.parent ?: return null

    var bestLabel: JLabel? = null
    var bestLabelY = Int.MIN_VALUE

    findAllComponentsOfType<JLabel>(searchContainer).forEach { label ->
        if (!label.isShowing) return@forEach

        // Skip labels that have labelFor set to a non-radio-button component
        val labelFor = label.labelFor
        if (labelFor != null && labelFor !is JRadioButton) return@forEach

        val labelText = label.text?.removeHtmlTags()?.trim()
        if (labelText.isNullOrEmpty()) return@forEach

        val labelY = getAbsoluteY(label)
        val labelX = getAbsoluteX(label)

        // The group label must be above the radio button
        if (labelY >= radioButtonY) return@forEach

        // The group label should be roughly aligned horizontally
        val horizontalDistance = kotlin.math.abs(labelX - radioButtonX)
        if (horizontalDistance > LayoutConstants.MAX_HORIZONTAL_DISTANCE) return@forEach

        if (labelY > bestLabelY) {
            bestLabel = label
            bestLabelY = labelY
        }
    }

    return bestLabel?.text?.removeHtmlTags()?.trim()
}

/**
 * Finds parent radio buttons in a hierarchical structure.
 *
 * In some Settings panels, radio buttons can be nested in a tree-like structure
 * where child options depend on a parent radio button being selected.
 *
 * @param radioButton The radio button to find parents for.
 * @param boundary The boundary component (typically ConfigurableEditor) to limit the search.
 * @return List of parent radio button texts, ordered from top-most to closest parent.
 */
private fun findParentRadioButtons(radioButton: JRadioButton, boundary: Component?): List<String> {
    val radioButtonY = getAbsoluteY(radioButton)
    val radioButtonX = getAbsoluteX(radioButton)
    val searchContainer = (boundary as? Container) ?: radioButton.parent ?: return emptyList()

    val parentCandidates = mutableListOf<Pair<JRadioButton, Int>>()

    findAllComponentsOfType<JRadioButton>(searchContainer).forEach { rb ->
        if (rb === radioButton || !rb.isShowing) return@forEach

        val rbY = getAbsoluteY(rb)
        val rbX = getAbsoluteX(rb)

        // Parent must be above (smaller Y) and less indented (smaller X)
        if (rbY < radioButtonY && rbX < radioButtonX - LayoutConstants.MIN_INDENT_DIFF) {
            parentCandidates.add(Pair(rb, rbY))
        }
    }

    if (parentCandidates.isEmpty()) return emptyList()

    // Sort by Y coordinate (top to bottom)
    parentCandidates.sortBy { it.second }

    // Build the hierarchy
    val result = mutableListOf<String>()
    var currentX = radioButtonX

    for ((rb, _) in parentCandidates.reversed()) {
        val rbX = getAbsoluteX(rb)
        if (rbX < currentX - LayoutConstants.MIN_INDENT_DIFF) {
            val text = rb.text?.removeHtmlTags()?.trim()
            if (!text.isNullOrEmpty()) {
                result.add(0, text)
                currentX = rbX
            }
        }
    }

    return result
}

/**
 * Finds all components of a specific type within a container using lazy sequence.
 */
private inline fun <reified T : Component> findAllComponentsOfType(container: Container): Sequence<T> {
    return findComponentsSequence(container, T::class.java)
}

/**
 * Internal helper for recursive sequence generation (non-inline to allow recursion).
 */
@Suppress("UNCHECKED_CAST")
private fun <T : Component> findComponentsSequence(container: Container, targetClass: Class<T>): Sequence<T> =
    sequence {
        for (comp in container.components) {
            if (targetClass.isInstance(comp)) yield(comp as T)
            if (comp is Container) yieldAll(findComponentsSequence(comp, targetClass))
        }
    }

/**
 * Gets the absolute coordinate of a component on screen using the provided extractor.
 *
 * First attempts to use locationOnScreen (which requires the component to be showing).
 * Falls back to manually summing coordinates up the parent hierarchy.
 *
 * @param component The component to get the coordinate for.
 * @param screenCoordinate Extracts the coordinate from a Point (e.g., Point::x or Point::y).
 * @param localCoordinate Extracts the local coordinate from a Component (e.g., Component::getX or Component::getY).
 */
private inline fun getAbsoluteCoordinate(
    component: Component,
    screenCoordinate: (java.awt.Point) -> Int,
    localCoordinate: (Component) -> Int
): Int {
    return runCatching {
        screenCoordinate(component.locationOnScreen)
    }.getOrElse {
        var sum = 0
        var current: Component? = component
        while (current != null) {
            sum += localCoordinate(current)
            current = current.parent
        }
        sum
    }
}

private fun getAbsoluteY(component: Component): Int =
    getAbsoluteCoordinate(component, { it.y }, { it.y })

private fun getAbsoluteX(component: Component): Int =
    getAbsoluteCoordinate(component, { it.x }, { it.x })
