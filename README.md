# qupath-detection-measurement-pipeline

A QuPath Groovy script pipeline that converts **object-level detections** (cells, nuclei, cytoplasm)
into **annotation-level quantitative measurements**, and generates **publication-ready heatmap
visualizations** with correct hierarchical and exclusive-region handling.

This project was developed to address a common limitation in QuPath workflows:
annotation measurements that unintentionally mix parent and child regions, leading to
misleading statistics and incorrect visualizations.

---

## Overview

The pipeline consists of **three scripts**, designed to be run sequentially or independently
depending on the use case:

1. **Detection → Annotation Measurement**
2. **Measurement → Heatmap Visualization**
3. **Overlay / Viewer Reset Utility**

Together, they enable a robust workflow from cell detection to quantitative region-level analysis
and figure generation.

---

## Scripts

### 1. `mean_intensity_measurement.groovy`

**Purpose**

- Aggregates **cell-level detections** into **annotation-level measurements**
- Computes both **non-exclusive** and **exclusive** region statistics
- Supports compartment-level analysis:
  - Nucleus
  - Cytoplasm
  - Whole cell / region

**Key features**

- Computes:
  - Region area
  - Cell count & density
  - Area-weighted mean intensities
- Handles **annotation hierarchy correctly**:
  - Parent annotations are split into:
    - Full region (non-exclusive)
    - Exclusive region = parent − direct child annotations
- Generates parallel measurement sets:
  - `[Whole]`, `[Nucleus]`, `[Cytoplasm]`
  - `Exclusive [Whole]`, `Exclusive [Nucleus]`, `Exclusive [Cytoplasm]`
- Designed to prevent double-counting when annotations do not fully tile the image

**Typical use case**

> Quantifying signal intensity and cell density in hierarchical brain regions
> where subregions only partially cover the parent ROI.

---

### 2. `color_mask.groovy`

**Purpose**

- Converts annotation-level measurements into **heatmap-style overlays**
- Produces figures suitable for **direct export** (paper / presentation)

**Key features**

- Exclusive-first measurement resolution:
  - Automatically uses `Exclusive <measurement>` if available
  - Falls back to non-exclusive measurements otherwise
- Centralized user-parameter block:
  - Measurement selection
  - Color map
  - Scale mode (data / percentile / manual)
  - Overlay resolution
- Handles large images safely (prevents Java heap overflow)
- Generates:
  - White background mask (image-independent visualization)
  - Fixed-position scale bar (µm → mm auto-conversion)
- Supports all QuPath runtime colormaps

**Typical use case**

> Creating unbiased regional heatmaps that reflect the *true* value of a region,
> even when child annotations only partially cover the parent.

---

### 3. `clear_mask_view.groovy`

**Purpose**

- Restores the QuPath viewer to its default state after visualization

**What it does**

- Removes custom `BufferedImageOverlay` layers
- Restores annotation visibility and fill settings
- Safe to run repeatedly

**Typical use case**

> Quickly switching between raw image inspection and heatmap visualization
> during exploratory analysis.

---

## Design Principles

- **Exclusive-region correctness**
  - Parent annotation values reflect only uncovered regions when children exist
- **Measurement reproducibility**
  - All derived quantities are stored as QuPath measurements
- **Visualization integrity**
  - Heatmaps reflect actual quantitative values, not rendering artifacts
- **Parameter centralization**
  - All user-adjustable settings are grouped at the top of each script
- **Fail-safe defaults**
  - Prevents common pitfalls such as memory overflow from oversized overlays

---

## Requirements

- QuPath **0.6.x**
- Existing cell detections with:
  - Cell ROI
  - (Optional) nucleus ROI
  - Intensity measurements per channel

---

## Typical Workflow

1. Run cell detection in QuPath
2. Draw hierarchical annotations (parent / child regions)
3. Run `mean_intensity_measurement.groovy`
4. Select a measurement of interest
5. Run `color_mask.groovy` to generate heatmap
6. Export view
7. Run `clear_mask_view.groovy` to reset viewer

---

## Notes

- This pipeline intentionally avoids hard assumptions about annotation tiling
- Designed for research settings where **partial region coverage is the norm**
- Particularly suitable for neuroanatomy, histology, and spatial biology data

---

## License

MIT License (or specify if different)

---

## Author

Developed as part of a research-oriented QuPath analysis workflow.
