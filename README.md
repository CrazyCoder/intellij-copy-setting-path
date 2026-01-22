# Copy Setting Path

An IntelliJ Platform plugin that adds a **Copy Setting Path** action to JetBrains IDEs. This action copies the full
navigation path to any UI setting in IDE dialogs (such as Settings, Project Structure, etc.) to the clipboard.

## Features

- Copy full path to any option in Settings dialogs (e.g., `Settings | Editor | Code Style | Java`)
- Copy full path from main menu items (e.g., `File | Manage IDE Settings | Export Settings`)
- Works with Project Structure dialog in IntelliJ IDEA
- Supports copying via **Ctrl+Click** (or **Cmd+Click** on macOS)
- Handles tree structures, tabs, titled panels, buttons, and labels

## Installation

### From JetBrains Marketplace

1. Open your JetBrains IDE
2. Go to **Settings | Plugins | Marketplace**
3. Search for "Copy Setting Path"
4. Click **Install**

### Manual Installation

1. Download the plugin ZIP from [Releases](https://github.com/CrazyCoder/intellij-copy-options-path/releases)
2. Go to **Settings | Plugins | ⚙️ | Install Plugin from Disk...**
3. Select the downloaded ZIP file

## Usage

### Quick Copy (Recommended)

**Ctrl+Click** (or **Cmd+Click** on macOS) on any option, label, button, or setting in a dialog to instantly copy its
full path to the clipboard.

### Context Menu

1. Open any IDE dialog (Settings, Project Structure, etc.)
2. Navigate to the desired option
3. Right-click on the option
4. Select **Copy Setting Path** from the context menu

### Example Output

**Settings Dialog:**
When you Ctrl+Click on the "Insert imports on paste" dropdown in the Java section of Auto Import settings:

```
Settings | Editor | General | Auto Import | Java | Insert imports on paste: Ask
```

The plugin automatically detects when a label ends with ":" and appends the current value of the adjacent component (
combo box, text field, etc.).

**Main Menu:**
When you Ctrl+Click on a menu item (requires mouse interception enabled):

```
File | Manage IDE Settings | Export Settings
```

The menu path is copied without executing the menu action.

## Compatibility

- **Minimum IDE Version:** 2025.1+
- **Supported IDEs:** All JetBrains IDEs (IntelliJ IDEA, PyCharm, WebStorm, GoLand, etc.)

## Configuration

### Advanced Settings

The plugin provides settings in **Settings | Advanced Settings** under the **Copy Setting Path** group:

| Setting                                                 | Default  | Description                                                                                                                                             |
|---------------------------------------------------------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Intercept Ctrl/Cmd+Click for Copy Setting Path**      | Disabled | When enabled, Ctrl+Click (or Cmd+Click on macOS) in dialogs will copy the setting path **without activating UI components** like checkboxes or buttons. |
| **Include adjacent value for labels ending with colon** | Enabled  | When enabled, labels ending with `:` will include the value of the adjacent component (combo box, text field, etc.) in the copied path.                 |
| **Path separator style**                                | Pipe     | Choose the separator character used between path components in the copied setting path.                                                                 |

#### Path Separator Styles

You can customize how path components are separated in the copied text:

| Style              | Example Output                     |
|--------------------|------------------------------------|
| **Pipe** (default) | `Settings \| Editor \| Code Style` |
| **Arrow**          | `Settings > Editor > Code Style`   |
| **Unicode Arrow**  | `Settings → Editor → Code Style`   |
| **Guillemet**      | `Settings » Editor » Code Style`   |
| **Triangle**       | `Settings ▸ Editor ▸ Code Style`   |

#### When to Enable Mouse Interception

Enable the mouse interception setting if you want Ctrl/Cmd+Click to **only** copy the path without triggering the
underlying UI element. This is useful when:

- You frequently Ctrl+Click on checkboxes and don't want them to toggle
- You want to copy paths from buttons without activating them
- **You want to copy main menu paths** — Menu path copying only works when interception is enabled

#### When to Keep Mouse Interception Disabled (Default)

Keep the default (disabled) if:

- You rarely use Ctrl/Cmd+Click on interactive elements
- You prefer the standard IDE behavior where Ctrl/Cmd+Click may also trigger the component
- You want to minimize any potential interference with other IDE features that use Ctrl/Cmd+Click

## Recent Fixes

This fork includes important fixes for compatibility with modern IDE versions:

- **Main menu path copying** — When mouse interception is enabled, you can now Ctrl+Click (Cmd+Click on macOS) on any
  main menu item to copy its full path to the clipboard. For example, clicking on "Export Settings" in the File menu
  copies `File | Manage IDE Settings | Export Settings`. The menu action is not executed, only the path is copied.

- **Adjacent component value detection** — When clicking on a label that ends with ":" (colon), the plugin now
  automatically finds the adjacent value component (combo box, text field, spinner, etc.) and appends its current value
  to the path. For example, clicking on "Logger:" with an adjacent combo box set to "Unspecified" produces
  `Settings | Languages & Frameworks | JVM Logging | Java | Logger: Unspecified`.

- **JTable support for Registry and similar dialogs** — The plugin now supports copying values from JTable-based dialogs
  like the Registry dialog. Clicking on any table cell copies the value of that specific cell. For example, clicking on
  a registry key name in the Registry dialog (`Help | Find Action... | Registry...`) copies the key name to the
  clipboard.

- **Optional non-intrusive Ctrl/Cmd+Click** — When enabled in Advanced Settings, Ctrl+Click (Cmd+Click on macOS) copies
  the setting path **without activating the UI component**. Previously, clicking on a checkbox would toggle it, and
  clicking on a button would activate it. This feature is disabled by default to avoid interfering with standard IDE
  behavior.

- **Added settings group detection** — The plugin now captures titled separator groups (like "Java", "Kotlin", "Groovy"
  sections) that appear in Settings panels. For example, clicking on "Insert imports on paste:" in the Java section of
  Auto Import settings now produces `Settings | Editor | General | Auto Import | Java | Insert imports on paste:`
  instead of omitting the "Java" group name.

- **Fixed Settings dialog breadcrumb extraction** — The plugin now correctly copies the full path in Settings dialogs (
  e.g., `Settings | Editor | General | Auto Import`) instead of just the final element. This was broken due to internal
  API changes in recent IDE versions.

- **Fixed Project Structure dialog paths** — Added support for extracting section names (like "Project Settings", "
  Platform Settings") in the Project Structure dialog, providing complete paths such as
  `Project Structure | Project Settings | Project | Language level:`.

- **Updated for 2025.1+ compatibility** — Refactored internal reflection-based code to work with the latest IntelliJ
  Platform architecture changes.

## Use Cases

- **Documentation:** Quickly reference exact settings paths in documentation or tutorials
- **Support:** Share precise setting locations when helping colleagues or reporting issues
- **Configuration:** Document your IDE setup for team onboarding or backup purposes

## License

This project is open source. See the repository for license details.

## Contributors

- **Serge Baranov** — Current maintainer
- **Andrey Dernov** — Original author (Copy Option Path plug-in)
