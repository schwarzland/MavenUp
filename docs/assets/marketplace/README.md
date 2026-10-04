# Marketplace Screenshots & Visual Assets Guide

This guide describes how to create, edit, and maintain high-quality annotated screenshots for the **JetBrains Marketplace** and project documentation.

The visual assets use a **vector overlay approach** based on Draw.io / Diagrams.net (`.drawio.svg`), combining raw screenshots with editable callouts, highlight frames, and numbered badges conforming to the JetBrains UI design language.

---

## Directory Structure

```text
docs/assets/marketplace/
├── README.md             # This guide
├── raw/                  # Unprocessed raw UI screenshots (HiDPI / 2x resolution)
│   ├── main_withoutVulnerabilities.png
│   ├── main_selectNewVersion.png
│   └── ...
├── src/                  # Editable vector source diagrams (.drawio.svg)
│   ├── template.drawio.svg              # Master template & component palette
│   ├── 01_dependency_management.drawio.svg
│   └── ...
└── dist/                 # Exported production images for Marketplace & README (.png)
    ├── 01_dependency_management.png
    └── ...
```

---

## Prerequisites

1. In IntelliJ IDEA: Open **Settings / Preferences** (`Ctrl+Alt+S` or `Cmd+,`) → **Plugins** → **Marketplace** tab.
2. Search for and install **Diagrams.net Integration** (by David Benson / draw.io).
3. Once installed, double-clicking any `.drawio.svg` file opens the visual diagram editor directly inside IntelliJ IDEA.
   *(Alternatively, files can be edited in a web browser at [app.diagrams.net](https://app.diagrams.net)).*

---

## Step-by-Step Tutorial: Creating an Annotated Screenshot

### Step 1: Copy the Master Template

Do **not** modify `template.drawio.svg` directly. Instead, create a copy for each new screenshot:

1. Copy `docs/assets/marketplace/src/template.drawio.svg`.
2. Rename the copy to match the intended screenshot name, for example:  
   `docs/assets/marketplace/src/01_dependency_management.drawio.svg`.

### Step 2: Open the File in IntelliJ IDEA

Open `01_dependency_management.drawio.svg` in IntelliJ IDEA.  
You will see two areas on the canvas:
- **Left (Screenshot Canvas / 1280×800):** The bounding area where the UI screenshot is placed.
- **Right (Component Palette):** Ready-to-use callout bubbles, badges, highlight frames, and arrows in the JetBrains color theme.

### Step 3: Insert the Raw Screenshot

1. Take a clean screenshot of the MavenUp plugin UI in IntelliJ (e.g. at 2x / Retina resolution).
2. Save the raw PNG image into `docs/assets/marketplace/raw/` (e.g. `raw/dependencies_raw.png`).
3. Drag and drop the PNG file onto the open diagram canvas (or use the menu: `+` / `Insert` → `Image`).
4. Align and scale the image inside the left 1280×800 canvas area.
5. **Tip (Lock Image):** Right-click the screenshot image and select **Lock** (or press `Ctrl+L` / `Cmd+L`). This prevents the background image from moving while arranging callouts.

### Step 4: Add Callouts and Badges

1. Select the required callout, badge, or frame from the palette on the right side.
2. Copy (`Ctrl+C` / `Cmd+C`) and paste (`Ctrl+V` / `Cmd+V`) it onto the screenshot area.
3. **Edit text:** Double-click the text inside the callout to edit the title and description.
4. **Targeting arrow:** Click on the callout bubble and drag the orange handle point at the tip of the arrow directly to the button, table column, or icon being explained.

#### Color Palette Guidelines

| Element Type | Background Color | Text Color | Usage |
| :--- | :--- | :--- | :--- |
| **Primary Highlight** | `#3574F0` (JetBrains Blue) | `#FFFFFF` | Core feature callouts and main workflows |
| **Update / Version** | `#F28B25` (Orange) | `#FFFFFF` | Available version updates & upgrade notices |
| **CVE / Security** | `#E55765` (Red) | `#FFFFFF` | Vulnerability warnings, CVSS scores, CVE IDs |
| **Context / Info** | `#2B2D30` (Dark Gray) | `#DFE1E5` | General UI descriptions, tool window explanations |
| **Numbered Badges** | `#3574F0` / `#2B2D30` | `#FFFFFF` | Sequential step markers (❶, ❷, ❸, ❹) |

### Step 5: Export to Production PNG

1. Select only the screenshot area on the left (the screenshot and its overlay callouts).  
   *(Or optionally delete the palette elements on the right before exporting).*
2. In the Draw.io menu, select **File** → **Export as** → **PNG...**.
3. In the export dialog:
   - Check **Selection only** (if you highlighted the canvas area).
   - Set **Zoom** to `200%` (or `300%` for crisp HiDPI / 2x rendering).
   - Leave background as white or transparent as needed.
4. Click **Export** and save the file to `docs/assets/marketplace/dist/01_dependency_management.png`.

---

## Maintaining Screenshots when UI Changes

When the plugin UI changes in future versions:
1. Capture a new raw screenshot and place it in `raw/`.
2. Open the corresponding `.drawio.svg` file in `src/`.
3. Unlock the old background image, replace it with the new screenshot, and re-lock.
4. Adjust callout positions if buttons or columns have moved slightly.
5. Re-export to `dist/*.png`.

No callouts, text formatting, or arrows need to be recreated from scratch.

---

## Best Practices for Marketplace Graphics

- **Clarity over quantity:** Limit annotations to 2–4 key callouts per screenshot to avoid visual noise.
- **High resolution:** Always export at 2x resolution (`200%` zoom) so text and icons remain crisp on high-DPI displays.
- **Consistent aspect ratio:** Maintain a standard 16:10 (e.g. 1280×800, 2560×1600) or 16:9 aspect ratio.
- **Version control:** Always commit both the editable `.drawio.svg` source and the exported `.png` image into Git.
