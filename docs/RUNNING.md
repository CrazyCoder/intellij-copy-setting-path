# Running and Debugging the Plugin

This guide explains how to run the Copy Setting Path plugin in a sandboxed IDE instance for testing and how to enable
debug logging.

## Prerequisites

- IntelliJ IDEA 2025.1+ (for development)
- JDK 21+
- Gradle (bundled with the project)

## Running the Plugin

### Method 1: Using Run Configuration (Recommended)

1. Open the project in IntelliJ IDEA
2. Select **"Run Plugin"** from the run configuration dropdown in the toolbar
3. Click the **Run** button (green triangle) or press `Shift+F10`

This launches a sandboxed IntelliJ IDEA Community instance with the plugin pre-installed.

### Method 2: Using Gradle Tasks

From the terminal or Gradle tool window:

```bash
./gradlew runIde
```

Or on Windows:

```cmd
gradlew.bat runIde
```

### Method 3: Using Gradle Tool Window

1. Open **View → Tool Windows → Gradle**
2. Navigate to **Tasks → intellij platform → runIde**
3. Double-click to run

## Debugging the Plugin

### Starting a Debug Session

1. Select **"Run Plugin"** from the run configuration dropdown
2. Click the **Debug** button (bug icon) or press `Shift+F9`
3. Set breakpoints in your code before or during the debug session

### Setting Breakpoints

Place breakpoints in key locations:

- `CopySettingPath.kt:actionPerformed()` - Main action entry point
- `CopySettingPath.kt:update()` - Action visibility/enablement logic
- `CopyActionUtil.kt` - Path building utilities

## Enabling Debug Logging

The plugin uses IntelliJ's diagnostic logger with the category `#com.intellij.plugin.copySettingPath`.

### Method 1: Debug Log Settings (Sandboxed IDE)

In the **sandboxed IDE** that launches when you run the plugin:

1. Go to **Help → Diagnostic Tools → Debug Log Settings...**
2. Add the following line:
   ```
   #com.intellij.plugin.CopySettingPath
   ```
3. Click **OK**
4. Use the plugin - debug messages will appear in the IDE log

### Method 2: Enable via idea.log

View the log file in the sandboxed IDE:

1. Go to **Help → Show Log in Explorer/Finder**
2. Open `idea.log`
3. Search for `CopySettingPath` to find plugin-related messages

### Method 3: Log File Location

The sandboxed IDE log is located at:

- **Windows:** `build/idea-sandbox/system/log/idea.log`
- **macOS:** `build/idea-sandbox/system/log/idea.log`
- **Linux:** `build/idea-sandbox/system/log/idea.log`

You can tail the log in real-time:

```bash
# Windows (PowerShell)
Get-Content -Path "build/idea-sandbox/system/log/idea.log" -Wait -Tail 50

# macOS/Linux
tail -f build/idea-sandbox/system/log/idea.log
```

### Method 4: Adding Custom Debug Output

The plugin already includes debug logging. Key log statements:

```kotlin
// In CopyActionUtil.kt
LOG.debug("Selected path: $result")
LOG.debug("Error trying to get 'myText' field from $it: ${e.message}")
LOG.debug("Exception when appending path: ${e.message}")
LOG.warn("Can not get project structure path: " + e.message)
```

To add more debug output, use the existing logger:

```kotlin
import com.intellij.plugin.CopySettingPath.LOG

// Debug level (only shown when debug logging enabled)
LOG.debug("Debug message: $variable")

// Info level (always shown)
LOG.info("Info message")

// Warning level (always shown, highlighted)
LOG.warn("Warning message")
```

## Testing the Plugin Functionality

Once the sandboxed IDE is running:

1. **Open Settings Dialog:** `Ctrl+Alt+S` (Windows/Linux) or `Cmd+,` (macOS)
2. **Navigate** to any setting (e.g., Editor → Code Style → Java)
3. **Ctrl+Click** (or **Cmd+Click** on macOS) on any setting
4. The full path is copied to your clipboard (e.g., `Settings | Editor | Code Style | Java`)

### Testing Locations

Test the plugin in these dialogs:

- **Settings/Preferences** - Main settings dialog
- **Project Structure** (`Ctrl+Alt+Shift+S`) - For IDEA/EDU products
- Any modal dialog with tree structures, tabs, or titled panels

## Troubleshooting

### Plugin Not Loading

1. Check **Help → About** in the sandboxed IDE for "Copy Setting Path" in the plugin list
2. Verify build succeeded without errors
3. Check the log for plugin loading errors

### Debug Logging Not Appearing

1. Ensure you added the logger category in the **sandboxed** IDE, not the development IDE
2. Restart the sandboxed IDE after changing debug settings
3. Verify the log level is set correctly

### Action Not Available

The "Copy Setting Path" action only appears when:

- A UI component is focused in a dialog
- The dialog is modal (Settings, Project Structure, etc.)

### Build Issues

If the build fails, try:

```bash
./gradlew clean build
```
