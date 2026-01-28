package io.github.crazycoder.copysettingpath

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Component
import java.awt.MouseInfo
import java.awt.Point
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Toast notification system for the Copy Setting Path plugin.
 * Provides visual feedback when a path is copied to clipboard.
 *
 * Features:
 * - Rounded toast popup with checkmark icon
 * - Optional bounce-in animation with elastic easing
 * - Optional sequential path segment highlighting
 * - Configurable display duration
 * - Sound notification
 */

// ============================================================================
// Settings Constants
// ============================================================================

/** Advanced setting ID for balloon notification configuration. */
private const val SHOW_BALLOON_SETTING_ID = "copy.setting.path.show.balloon"

/** Advanced setting ID for notification animation configuration. */
private const val ANIMATE_NOTIFICATION_SETTING_ID = "copy.setting.path.animate.notification"

/** Advanced setting ID for sound notification configuration. */
private const val PLAY_SOUND_SETTING_ID = "copy.setting.path.play.sound"

/** Advanced setting ID for notification delay configuration. */
private const val NOTIFICATION_DELAY_SETTING_ID = "copy.setting.path.notification.delay"

/** Advanced setting ID for path separator configuration. */
private const val PATH_SEPARATOR_SETTING_ID = "copy.setting.path.separator"

/** Default notification delay in seconds. */
private const val DEFAULT_NOTIFICATION_DELAY_SECONDS = 1F

// ============================================================================
// Animation Constants
// ============================================================================

/** Fade-in animation duration in milliseconds. */
private const val FADE_IN_DURATION_MS = 150

/** Fade-out animation duration in milliseconds. */
private const val FADE_OUT_DURATION_MS = 200

/** Animation timer step interval in milliseconds. */
private const val ANIMATION_STEP_MS = 15

/** Vertical distance for bounce animation start offset. */
private const val BOUNCE_START_OFFSET = 20

/** Bounce animation duration in milliseconds. */
private const val BOUNCE_DURATION_MS = 300

/** Delay between each segment highlight in milliseconds. */
private const val HIGHLIGHT_DELAY_PER_SEGMENT_MS = 60

/** Corner radius for the toast popup. */
private const val TOAST_CORNER_RADIUS = 12

/** Corner radius for segment highlight. */
private const val SEGMENT_CORNER_RADIUS = 8

/** Alpha value for segment highlight color (0-255). */
private const val HIGHLIGHT_ALPHA = 140

// ============================================================================
// Sound Constants
// ============================================================================

/** Volume reduction in decibels (negative = quieter). */
private const val SOUND_VOLUME_DB = -20f

// ============================================================================
// Public API
// ============================================================================

/**
 * Shows a brief toast notification near the mouse cursor
 * displaying the path that was copied to clipboard.
 *
 * The notification is only shown if the "copy.setting.path.show.balloon" advanced setting is enabled.
 *
 * @param copiedPath The path that was copied to clipboard, to display in the notification.
 * @param sourceComponent The component from which the path was copied (currently unused).
 */
@Suppress("UNUSED_PARAMETER")
fun showCopiedBalloon(copiedPath: String, sourceComponent: Component? = null) {
    if (!AdvancedSettings.getBoolean(SHOW_BALLOON_SETTING_ID)) return

    val mouseLocation = MouseInfo.getPointerInfo()?.location ?: return

    showToast(copiedPath, mouseLocation)
}

// ============================================================================
// Private Implementation
// ============================================================================

/** Currently visible toast window, if any. */
@Volatile
private var currentToast: ToastWindow? = null

/**
 * Gets the notification delay in milliseconds from Advanced Settings.
 */
private fun getNotificationDelayMs(): Long {
    val delayString = AdvancedSettings.getString(NOTIFICATION_DELAY_SETTING_ID)
    val delaySeconds = delayString.toFloatOrNull() ?: DEFAULT_NOTIFICATION_DELAY_SECONDS
    return (delaySeconds * 1000).toLong().coerceAtLeast(100L)
}

/**
 * Gets the currently configured path separator from Advanced Settings.
 */
private fun getConfiguredSeparator(): String =
    AdvancedSettings.getEnum(PATH_SEPARATOR_SETTING_ID, PathSeparator::class.java).separator

/**
 * Plays a notification sound when a path is copied.
 * Only plays if the sound setting is enabled.
 */
private fun playNotificationSound() {
    if (!AdvancedSettings.getBoolean(PLAY_SOUND_SETTING_ID)) return

    try {
        val soundStream = object {}.javaClass.getResourceAsStream("/sounds/switch.wav") ?: return
        val audioStream = AudioSystem.getAudioInputStream(soundStream.buffered())
        val clip = AudioSystem.getClip()
        clip.open(audioStream)

        // Reduce volume
        (clip.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl)?.value = SOUND_VOLUME_DB

        clip.addLineListener { event ->
            if (event.type == LineEvent.Type.STOP) {
                clip.close()
            }
        }
        clip.start()
    } catch (e: Exception) {
        LOG.warn("Failed to play notification sound", e)
    }
}

/**
 * Shows a custom toast notification at the given screen location.
 * Uses a heavyweight JWindow with setAlwaysOnTop to ensure it appears above menus/popups.
 * Features optional bounce-in animation and sequential path segment highlighting.
 * Only one toast is shown at a time - previous toasts are closed when a new one appears.
 */
private fun showToast(text: String, screenLocation: Point) {
    // Close any existing toast
    currentToast?.dispose()

    val separator = getConfiguredSeparator()
    val animationsEnabled = AdvancedSettings.getBoolean(ANIMATE_NOTIFICATION_SETTING_ID)
    val toast = ToastWindow(text, separator)
    currentToast = toast

    // Position above the mouse cursor
    val toastSize = toast.preferredSize
    val x = screenLocation.x - toastSize.width / 2
    val targetY = screenLocation.y - toastSize.height - 10

    if (animationsEnabled) {
        // Start below target for bounce animation
        toast.setLocation(x, targetY + BOUNCE_START_OFFSET)
        toast.setTargetY(targetY)
        toast.showWithBounce()
    } else {
        // Simple fade-in without bounce
        toast.setLocation(x, targetY)
        toast.showWithFade()
    }

    playNotificationSound()

    // Auto-hide after delay with fade-out animation
    val delayMs = getNotificationDelayMs()
    AppExecutorUtil.getAppScheduledExecutorService().schedule(
        {
            ApplicationManager.getApplication().invokeLater {
                // Only fade out if this is still the current toast
                if (currentToast === toast) {
                    toast.fadeOutAndDispose()
                }
            }
        },
        delayMs,
        TimeUnit.MILLISECONDS
    )
}

// ============================================================================
// Toast Window Implementation
// ============================================================================

/**
 * A lightweight toast window that appears on top of all other windows.
 * Features bounce-in animation and sequential path segment highlighting.
 * Any mouse click anywhere dismisses it.
 * Also dismisses when another window is activated or a dialog opens.
 *
 * @param text The path text to display.
 * @param separator The separator string used to split the path into segments.
 */
private class ToastWindow(text: String, private val separator: String) : javax.swing.JWindow() {
    private var mouseHandler: java.awt.event.AWTEventListener? = null
    private var windowHandler: java.awt.event.AWTEventListener? = null
    private var animationTimer: Timer? = null
    private var highlightTimer: Timer? = null
    private val segmentLabels = mutableListOf<HighlightableLabel>()
    private var targetY: Int = 0
    private val normalBackground = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.backgroundColor()

    /** Semi-transparent highlight color derived from theme's border color. */
    @Suppress("UseJBColor")
    private val highlightColor: java.awt.Color
        get() {
            val border = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.borderColor()
            return java.awt.Color(border.red, border.green, border.blue, HIGHLIGHT_ALPHA)
        }

    /**
     * Custom label that properly paints semi-transparent highlight backgrounds.
     * Shows a rounded highlight sweep effect.
     */
    private inner class HighlightableLabel(text: String) : javax.swing.JLabel(text) {
        var highlighted: Boolean = false
            set(value) {
                field = value
                repaint()
            }

        init {
            isOpaque = false  // We'll paint background ourselves
            foreground = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.foregroundColor()
            border = javax.swing.BorderFactory.createEmptyBorder(3, 5, 3, 5)
        }

        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )

            // First fill with normal background to clear any previous content
            g2.color = normalBackground
            g2.fillRoundRect(0, 0, width, height, SEGMENT_CORNER_RADIUS, SEGMENT_CORNER_RADIUS)

            // Then paint highlight on top if active (with rounded corners)
            if (highlighted) {
                g2.color = highlightColor
                g2.fillRoundRect(0, 0, width, height, SEGMENT_CORNER_RADIUS, SEGMENT_CORNER_RADIUS)
            }

            g2.dispose()
            super.paintComponent(g)
        }
    }

    init {
        // Set window type to POPUP - this allows displaying without stealing focus
        type = Type.POPUP

        // Make window background transparent for rounded corners
        @Suppress("UseJBColor")
        background = java.awt.Color(0, 0, 0, 0)

        // Custom panel with rounded corners
        val panel = object : javax.swing.JPanel(java.awt.BorderLayout()) {
            init {
                isOpaque = false
                border = javax.swing.BorderFactory.createEmptyBorder(5, 12, 5, 12)
            }

            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(
                    java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON
                )

                // Draw rounded background
                g2.color = normalBackground
                g2.fillRoundRect(0, 0, width, height, TOAST_CORNER_RADIUS, TOAST_CORNER_RADIUS)

                // Draw rounded border
                g2.color = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.borderColor()
                g2.drawRoundRect(0, 0, width - 1, height - 1, TOAST_CORNER_RADIUS, TOAST_CORNER_RADIUS)

                g2.dispose()
                super.paintComponent(g)
            }
        }

        // Create segmented path display using BoxLayout for stable horizontal layout
        val pathPanel = javax.swing.JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
            isOpaque = false
        }

        // Add checkmark icon
        val iconLabel = javax.swing.JLabel(com.intellij.icons.AllIcons.General.GreenCheckmark).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 6)
        }
        pathPanel.add(iconLabel)

        // Parse path into segments and create labels using the configured separator
        val trimmedSeparator = separator.trim()
        val segments = text.split(trimmedSeparator).map { it.trim() }.filter { it.isNotEmpty() }

        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                // Add separator label (use the configured separator)
                val separatorLabel = javax.swing.JLabel(separator).apply {
                    foreground = com.intellij.util.ui.JBUI.CurrentTheme.NotificationInfo.foregroundColor()
                }
                pathPanel.add(separatorLabel)
            }

            // Add segment label with highlight capability
            val segmentLabel = HighlightableLabel(segment)
            segmentLabels.add(segmentLabel)
            pathPanel.add(segmentLabel)
        }

        panel.add(pathPanel, java.awt.BorderLayout.CENTER)
        contentPane = panel

        // Make content pane transparent for rounded corners to show
        (contentPane as? javax.swing.JComponent)?.isOpaque = false
        pack()

        isAlwaysOnTop = true

        // Dismiss on any mouse click anywhere
        mouseHandler = java.awt.event.AWTEventListener { event ->
            if (event is java.awt.event.MouseEvent && event.id == java.awt.event.MouseEvent.MOUSE_PRESSED) {
                dismissAndCleanup()
            }
        }
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(mouseHandler, java.awt.AWTEvent.MOUSE_EVENT_MASK)

        // Dismiss when another window is activated (e.g., dialog opens)
        windowHandler = java.awt.event.AWTEventListener { event ->
            if (event is java.awt.event.WindowEvent) {
                when (event.id) {
                    java.awt.event.WindowEvent.WINDOW_ACTIVATED,
                    java.awt.event.WindowEvent.WINDOW_OPENED -> {
                        // Another window was activated/opened - dismiss toast
                        if (event.window !== this) {
                            dismissAndCleanup()
                        }
                    }
                }
            }
        }
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(windowHandler, java.awt.AWTEvent.WINDOW_EVENT_MASK)
    }

    /**
     * Sets the target Y position for the bounce animation.
     */
    fun setTargetY(y: Int) {
        targetY = y
    }

    /**
     * Shows the toast with a simple fade-in animation (no bounce or highlight).
     */
    fun showWithFade() {
        opacity = 0f
        isVisible = true

        val steps = FADE_IN_DURATION_MS / ANIMATION_STEP_MS
        val opacityStep = 1.0f / steps

        animationTimer = Timer(ANIMATION_STEP_MS) {
            opacity = (opacity + opacityStep).coerceAtMost(1.0f)
            if (opacity >= 1.0f) {
                animationTimer?.stop()
                animationTimer = null
            }
        }.apply { start() }
    }

    /**
     * Elastic ease-out function for bounce effect.
     * Creates a spring-like overshoot and settle animation.
     */
    private fun elasticEaseOut(t: Float): Float {
        if (t == 0f) return 0f
        if (t == 1f) return 1f
        val p = 0.4f
        val s = p / 4
        return (2.0.pow(-10.0 * t) * sin((t - s) * (2 * PI) / p) + 1).toFloat()
    }

    /**
     * Shows the toast with a bounce-in animation and path highlight trail.
     */
    fun showWithBounce() {
        opacity = 0f
        // Start below target position
        setLocation(x, targetY + BOUNCE_START_OFFSET)
        isVisible = true

        val startTime = System.currentTimeMillis()
        val fadeInDuration = FADE_IN_DURATION_MS.toLong()
        val bounceDuration = BOUNCE_DURATION_MS.toLong()

        animationTimer = Timer(ANIMATION_STEP_MS) {
            val elapsed = System.currentTimeMillis() - startTime

            // Opacity animation (linear fade-in)
            val opacityProgress = (elapsed.toFloat() / fadeInDuration).coerceIn(0f, 1f)
            opacity = opacityProgress

            // Position animation (elastic bounce)
            val bounceProgress = (elapsed.toFloat() / bounceDuration).coerceIn(0f, 1f)
            val easedProgress = elasticEaseOut(bounceProgress)
            val currentY = targetY + BOUNCE_START_OFFSET - (BOUNCE_START_OFFSET * easedProgress).toInt()
            setLocation(x, currentY)

            // Stop animation when bounce is complete
            if (elapsed >= bounceDuration) {
                animationTimer?.stop()
                animationTimer = null
                setLocation(x, targetY)
                // Start highlight trail after bounce completes
                startHighlightTrail()
            }
        }.apply { start() }
    }

    /**
     * Animates highlight through path segments sequentially.
     */
    private fun startHighlightTrail() {
        if (segmentLabels.isEmpty()) return

        var currentIndex = 0
        highlightTimer = Timer(HIGHLIGHT_DELAY_PER_SEGMENT_MS) {
            // Clear previous highlight
            if (currentIndex > 0) {
                segmentLabels[currentIndex - 1].highlighted = false
            }

            // Highlight current segment
            if (currentIndex < segmentLabels.size) {
                segmentLabels[currentIndex].highlighted = true
                currentIndex++
            } else {
                // Clear last highlight and stop
                segmentLabels.lastOrNull()?.highlighted = false
                highlightTimer?.stop()
                highlightTimer = null
            }
        }.apply { start() }
    }

    /**
     * Fades out the toast and disposes it when complete.
     */
    fun fadeOutAndDispose() {
        animationTimer?.stop()
        highlightTimer?.stop()

        // Clear any remaining highlights
        segmentLabels.forEach { it.highlighted = false }

        val steps = FADE_OUT_DURATION_MS / ANIMATION_STEP_MS
        val opacityStep = 1.0f / steps

        animationTimer = Timer(ANIMATION_STEP_MS) {
            opacity = (opacity - opacityStep).coerceAtLeast(0f)
            if (opacity <= 0f) {
                animationTimer?.stop()
                animationTimer = null
                cleanupListeners()
                if (currentToast === this@ToastWindow) {
                    currentToast = null
                }
                super.dispose()
            }
        }.apply { start() }
    }

    private fun cleanupListeners() {
        mouseHandler?.let {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(it)
            mouseHandler = null
        }
        windowHandler?.let {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(it)
            windowHandler = null
        }
    }

    private fun dismissAndCleanup() {
        // Use fade-out animation for smooth dismissal
        fadeOutAndDispose()
    }

    override fun dispose() {
        animationTimer?.stop()
        animationTimer = null
        highlightTimer?.stop()
        highlightTimer = null
        cleanupListeners()
        super.dispose()
    }
}
