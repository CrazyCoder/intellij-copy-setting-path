@file:Suppress("UNCHECKED_CAST")

package com.intellij.plugin.copySettingPath.path

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.plugin.copySettingPath.LayoutConstants
import com.intellij.plugin.copySettingPath.removeHtmlTags
import com.intellij.ui.SimpleColoredComponent
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import javax.swing.*
import javax.swing.text.JTextComponent
import kotlin.math.min

/**
 * Simplified label extraction matching IntelliJ's CopySettingsPathAction pattern.
 *
 * Gets component labels using:
 * 1. labeledBy client property (Kotlin UI DSL standard)
 * 2. Component's own text (for buttons, labels)
 * 3. Adjacent value component for labels ending with ":"
 */
object ComponentLabelExtractor {

    /** Advanced setting ID for including adjacent value after colon labels. */
    private const val INCLUDE_ADJACENT_VALUE_SETTING_ID = "copy.setting.path.include.adjacent.value"

    /**
     * Appends the component label to the path.
     *
     * If the component text ends with ":" (colon), this indicates there is likely
     * an adjacent value component (combo box, text field, etc.). In such cases,
     * we find the adjacent component and append its current value.
     *
     * Special case: For toggle buttons (radio buttons, checkboxes) where the label
     * ends with ":" (either from labeledBy or from a sibling's group label),
     * the value is the button's own text, not an adjacent component.
     *
     * @param component The component to extract label from.
     * @param path StringBuilder to append label to.
     */
    fun appendComponentLabel(component: Component, path: StringBuilder) {
        val label = getComponentLabel(component) ?: return

        // Append the label
        path.append(label)

        // If label ends with ":", try to append adjacent value
        if (label.endsWith(":") && isAdjacentValueIncluded()) {
            // Special case: for toggle buttons where label came from labeledBy or group label,
            // the value is the button's own text, not an adjacent component
            if (component is JToggleButton && isToggleButtonGroupLabel(component, label)) {
                val buttonText = component.text?.removeHtmlTags()?.trim()
                if (!buttonText.isNullOrBlank()) {
                    path.append(" ")
                    path.append(buttonText)
                }
            } else {
                findAdjacentValue(component)?.let { value ->
                    if (value.isNotBlank()) {
                        path.append(" ")
                        path.append(value)
                    }
                }
            }
        }
    }

    /**
     * Checks if the label for a toggle button came from labeledBy or a sibling's group label.
     * This is used to determine if the button's own text should be used as the value.
     */
    private fun isToggleButtonGroupLabel(component: JToggleButton, label: String): Boolean {
        // Check if component has labeledBy
        val labeledBy = component.getClientProperty("labeledBy")
        if (labeledBy is JLabel) {
            return true
        }

        // Check if label came from a sibling's group label (which means it ends with ":")
        // If findGroupLabelFromSiblings would return this label, then it's a group label
        val groupLabel = findGroupLabelFromSiblings(component)
        return groupLabel == label
    }

    /**
     * Gets the label for a component using standard patterns.
     *
     * Follows IntelliJ's CopySettingsPathAction pattern:
     * 1. Check labeledBy client property (standard Swing/Kotlin UI DSL pattern)
     * 2. For toggle buttons without labeledBy, check sibling buttons for a shared group label
     * 3. Fall back to component's own text
     *
     * @param component The component to get label for.
     * @return The label text, or null if not found.
     */
    fun getComponentLabel(component: Component): String? {
        // Check labeledBy first (standard Swing/Kotlin UI DSL pattern)
        if (component is JComponent) {
            val labeledBy = component.getClientProperty("labeledBy")
            if (labeledBy is JLabel) {
                val text = labeledBy.text?.removeHtmlTags()?.trim()
                if (!text.isNullOrEmpty()) {
                    return text
                }
            }

            // For toggle buttons without labeledBy, check if sibling buttons have a shared group label
            if (component is JToggleButton) {
                val groupLabel = findGroupLabelFromSiblings(component)
                if (groupLabel != null) {
                    return groupLabel
                }
            }
        }

        // Fall back to component's own text
        return when (component) {
            is JToggleButton -> component.text?.removeHtmlTags()?.trim()?.takeIf { it.isNotEmpty() }
            is JLabel -> component.text?.removeHtmlTags()?.trim()?.takeIf { it.isNotEmpty() }
            is AbstractButton -> component.text?.removeHtmlTags()?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    /**
     * For a toggle button without labeledBy, looks for a sibling toggle button
     * that has a labeledBy label ending with ":". This handles cases where
     * only the first button in a group has the labeledBy property set.
     *
     * Only returns a group label if the sibling with that label is on the same
     * visual row as the target button. This prevents labels for horizontal
     * checkbox groups from being applied to checkboxes on different rows.
     *
     * @param button The toggle button to find the group label for.
     * @return The group label text if found, or null.
     */
    private fun findGroupLabelFromSiblings(button: JToggleButton): String? {
        val parent = button.parent ?: return null
        val buttonBounds = getScreenBounds(button) ?: return null

        // Look for sibling toggle buttons with labeledBy that ends with ":"
        for (sibling in parent.components) {
            if (sibling === button) continue
            if (sibling !is JToggleButton) continue

            val labeledBy = sibling.getClientProperty("labeledBy")
            if (labeledBy is JLabel) {
                val labelText = labeledBy.text?.removeHtmlTags()?.trim()
                if (!labelText.isNullOrEmpty() && labelText.endsWith(":")) {
                    // Only use this group label if the sibling is on the same row
                    if (isOnSameRow(buttonBounds, sibling)) {
                        return labelText
                    }
                }
            }
        }

        return null
    }

    /**
     * Finds the adjacent value component for a label ending with ":".
     *
     * @param src The source component (typically a JLabel ending with ":").
     * @return The extracted value string, or null if not found.
     */
    private fun findAdjacentValue(src: Component): String? {
        val adjacent = findAdjacentComponent(src) ?: return null
        return extractComponentValue(adjacent)
    }

    /**
     * Returns whether adjacent value should be included for labels ending with colon.
     */
    private fun isAdjacentValueIncluded(): Boolean =
        AdvancedSettings.getBoolean(INCLUDE_ADJACENT_VALUE_SETTING_ID)

    // ========================================================================
    // Adjacent Component Detection (simplified from AdjacentComponentUtils)
    // ========================================================================

    /**
     * Finds the adjacent component that follows the given source component.
     */
    private fun findAdjacentComponent(src: Component): Component? {
        val parent = src.parent ?: return null

        // Priority 1: If source is a JLabel with labelFor set, return that component
        if (src is JLabel) {
            val labelFor = src.labelFor
            if (labelFor != null && isValueComponent(labelFor)) {
                return labelFor
            }
        }

        val srcScreenBounds = getScreenBounds(src) ?: return null

        // Priority 2: Look for the next visible value component among siblings
        val components = parent.components
        val srcIndex = components.indexOf(src)
        if (srcIndex >= 0) {
            for (i in (srcIndex + 1) until components.size) {
                val nextComponent = components[i]
                if (!nextComponent.isVisible) continue

                if (isValueComponent(nextComponent) && isOnSameRow(srcScreenBounds, nextComponent)) {
                    return nextComponent
                }

                if (nextComponent is Container) {
                    val valueComp = findValueComponentIn(nextComponent)
                    if (valueComp != null && isOnSameRow(srcScreenBounds, valueComp)) {
                        return valueComp
                    }
                }
            }
        }

        return null
    }

    /**
     * Extracts the display value from a component.
     */
    private fun extractComponentValue(component: Component): String? {
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
     * Checks if a component is a value-bearing component.
     */
    private fun isValueComponent(component: Component): Boolean {
        // Exclude large components
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

        if (component is JTextArea || component is JEditorPane) {
            return false
        }

        return when (component) {
            is JComboBox<*>, is JTextField, is JCheckBox,
            is JRadioButton, is JSpinner, is JSlider -> true

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
}
