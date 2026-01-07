# qupath-detection-measurement-pipeline
<img width="1299" height="821" alt="333" src="https://github.com/user-attachments/assets/132c811b-1913-42a7-b7b6-6d7b82284f44" />

Converts QuPath object detections into annotation-level measurements and generates publication-ready heatmap visualizations.

This repository provides a research-oriented QuPath Groovy pipeline for aggregating object-level detections (cells, nuclei, cytoplasm) into annotation-level quantitative measurements, and visualizing these measurements as unbiased heatmaps with correct hierarchical handling.

---

## Overview

The pipeline consists of three scripts:

1. Detection → Annotation-level measurement aggregation
2. Measurement → Heatmap visualization
3. Viewer / overlay reset utility

Each script exposes a clearly defined **user-parameter block** at the top of the file, allowing reproducible and configurable analysis without modifying core logic.

---

## Scripts

---

## 1. `mean_intensity_measurement.groovy`

### Purpose

* Converts **cell-level detections** into **annotation-level measurements**
* Computes both **non-exclusive** and **exclusive** statistics for hierarchical annotations
* Supports compartment-aware quantification:

  * Whole region
  * Nucleus
  * Cytoplasm

---

### User Parameters

#### Channel selection and sampling

| Parameter       | Description                                                                                                                  |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `CHANNEL_INDEX` | Zero-based index of the image channel used for intensity measurement (e.g. `0` for first channel)                            |
| `CHANNEL_LABEL` | Human-readable channel name used to identify and store measurements in QuPath (must match the channel label shown in QuPath) |
| `DOWNSAMPLE`    | Downsample factor used when computing intensity measurements                                                                 |

> **Note**
> Internally, intensity values are extracted based on `CHANNEL_INDEX`, while
> `CHANNEL_LABEL` is used to locate or create the corresponding measurement keys
> in the QuPath measurement table.
> Both must refer to the same channel to avoid inconsistent results.

---

#### Area calibration

Pixel calibration is automatically read from image metadata.
If pixel size in microns is unavailable, a fallback value is used to ensure
the script remains executable.

---

### Measurements Generated

For each annotation, the script writes the following measurements into the QuPath
measurement table:

* **Region-level**

  * `Region area (mm^2)`
  * `Region mean (<CHANNEL_LABEL>)`

* **Cell-based**

  * `Cell count (cells)`
  * `Cell density (cells/mm^2)`

* **Compartment-based (area-weighted)**

  * `[Whole] inside mean (<CHANNEL_LABEL>)`
  * `[Whole] outside mean (<CHANNEL_LABEL>)`
  * `[Nucleus] inside mean (<CHANNEL_LABEL>)`
  * `[Nucleus] outside mean (<CHANNEL_LABEL>)`
  * `[Cytoplasm] inside mean (<CHANNEL_LABEL>)`
  * `[Cytoplasm] outside mean (<CHANNEL_LABEL>)`

* **Exclusive counterparts**

  * `Exclusive region mean (<CHANNEL_LABEL>)`
  * `Exclusive cell count`
  * `Exclusive cell density`
  * `Exclusive [Whole] / [Nucleus] / [Cytoplasm]` means and areas

Exclusive measurements are computed as:

```
exclusive region = parent annotation − direct child annotations
```

This ensures that parent annotation values reflect only uncovered regions
when child annotations do not fully tile the parent.

---

## 2. `color_mask.groovy`

### Purpose

* Converts annotation-level measurements into **heatmap-style overlays**
* Produces visualization suitable for direct export (figures / slides / manuscripts)

---

### User Parameters

#### Viewer control

| Parameter           | Description                                          |
| ------------------- | ---------------------------------------------------- |
| `TARGET_DOWNSAMPLE` | Viewer zoom level applied when rendering the overlay |

---

#### Measurement selection

| Parameter   | Description                                             |
| ----------- | ------------------------------------------------------- |
| `MEAS_NAME` | Base measurement name to visualize (non-exclusive name) |

The script automatically resolves measurements in the following priority order:

```
Exclusive <MEAS_NAME> → <MEAS_NAME>
```

This allows the same visualization script to work with or without exclusive measurements.

---

#### Overlay resolution

| Parameter         | Description                                                                         |
| ----------------- | ----------------------------------------------------------------------------------- |
| `MASK_DOWNSAMPLE` | Resolution of the generated overlay (must be ≥ 1.0 to avoid excessive memory usage) |

---

#### Color scaling

| Parameter         | Description                                           |
| ----------------- | ----------------------------------------------------- |
| `SCALE_MODE`      | Color scaling mode: `data`, `percentile`, or `manual` |
| `P_LOW`, `P_HIGH` | Percentile bounds used when `SCALE_MODE = percentile` |
| `V_MIN`, `V_MAX`  | Fixed range used when `SCALE_MODE = manual`           |
| `COLORMAP_NAME`   | Name of the QuPath colormap used for rendering        |

Supported colormaps (queried from runtime):

```
"Blue", "Cyan", "Gray", "Green", "Inferno", "Jet",
"Magenta", "Magma", "Plasma", "Red", "Svidro2",
"Viridis", "Yellow"
```

---

#### Annotation rendering

| Parameter      | Description                         |
| -------------- | ----------------------------------- |
| `DRAW_OUTLINE` | Whether to draw annotation borders  |
| `OUTLINE_PX`   | Stroke width of annotation outlines |

---

#### Background and visibility

| Parameter             | Description                                                       |
| --------------------- | ----------------------------------------------------------------- |
| `SIMULATE_HIDE_IMAGE` | Draws a white background to decouple visualization from raw image |
| `BACKGROUND_COLOR`    | Background color used when hiding the image                       |
| `HIDE_QP_ANNOS`       | Hides native QuPath annotation rendering                          |

---

#### Scale bar

| Parameter                | Description                                                              |
| ------------------------ | ------------------------------------------------------------------------ |
| `SCALEBAR_UM`            | Physical length of scale bar (automatically converts to mm if ≥ 1000 µm) |
| `SCALEBAR_BAR_HEIGHT_PX` | Height of scale bar in overlay pixels                                    |
| `SCALEBAR_MARGIN_PX`     | Margin from image edge                                                   |
| `SCALEBAR_COLOR`         | Scale bar color                                                          |
| `SCALEBAR_TEXT_COLOR`    | Scale bar label color                                                    |
| `SCALEBAR_FONT_NAME`     | Font used for scale bar label                                            |
| `SCALEBAR_FONT_SIZE`     | Font size for scale bar label                                            |
| `DRAW_SCALEBAR_TEXT`     | Whether to draw the scale bar label                                      |

The scale bar is always rendered at a **fixed position in the lower-left corner**
to ensure consistent figure layout.

---

## 3. `clear_mask_view.groovy`

### Purpose

* Restores the QuPath viewer to its default state after visualization

---

### User Parameters

This script does not expose user-adjustable parameters.

It safely:

* Removes all `BufferedImageOverlay` layers
* Restores annotation visibility
* Can be executed repeatedly without side effects

---

## Typical Workflow

1. Perform cell detection in QuPath
2. Create hierarchical annotations
3. Run `mean_intensity_measurement.groovy`
4. Select a measurement of interest
5. Run `color_mask.groovy` to generate heatmap
6. Export view
7. Run `clear_mask_view.groovy` to reset the viewer

---

## Notes

* Designed for datasets where **partial region coverage is expected**
* Avoids implicit assumptions about annotation tiling
* All derived values are stored as QuPath measurements for reproducibility

---

## License

MIT License

---

## Author

Developed as part of a research-oriented QuPath image analysis workflow.
