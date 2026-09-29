# BAL cell segmentation and classification

Automated counting and typing of immune cells in bronchoalveolar lavage (BAL) cytology,
built around QuPath with Cellpose, InstanSeg and a pathology vision transformer.

The slide is `SFS/Image_BAL_#2.vsi`, series **40x_BF_01**: 55481 x 52623 px at
**0.137 µm/px** (7.6 x 7.2 mm), May-Grünwald–Giemsa stained. A converted copy lives in
`SFS/bal2.zarr` (series `2`, level `0`) for direct Python access.

---

## What exists

Two halves that meet at the cells:

| | Where | Does what |
|---|---|---|
| **QuPath scripts** | `Mathilde Data/qupath_scripts/` | Segmentation, measurement, training, classification — driven by one dialog |
| **Python classifier** | `Mathilde Data/bal_classify/` | Embeds cell crops with a transformer, trains/applies a cell-type classifier |
| **Notebooks** | `Mathilde Data/*.ipynb` | The original pipeline the QuPath side was validated against |

Everything is reachable from **one entry point**: `qupath_bal_gui.groovy`.

---

## Getting started

1. **Open QuPath 0.7.0** and the project at `QuPath Project/`.
   The image to use is `Image_BAL_#2.vsi - 40x_BF_01` (not `label` or `overview`).
2. **Automate → User scripts → `qupath_bal_gui`** — one dialog for every task.
   If the menu is empty, restart QuPath; the scripts folder is a junction (see below).
3. Draw a rectangle, select it, and run **Segment cells**.

### The seven tasks

| Task | What it does |
|---|---|
| **Segment cells** | Runs the chosen model on the selected region(s) and measures each cell |
| **Prepare training regions** | Creates Training/Validation rectangles with editable outlines to correct |
| **Export corrections to training corpus** | Adds this project's corrected regions to a shared ground-truth folder |
| **Train model on corrected regions** | Fine-tunes Cellpose on that ground truth |
| **Classify cells: explore** | Clusters cells by transformer embedding; writes a montage to name the clusters |
| **Classify cells: train classifier** | Trains a cell-type classifier from your hand-set classes |
| **Classify cells: predict** | Applies a saved classifier and writes the classes back onto the cells |

---

## Environments

Two conda environments, because cellpose 3 and 4 cannot coexist:

| Environment | Holds | Used for |
|---|---|---|
| `cellpose_seg` | cellpose **4.2.1** (cpsam), torch cu126, transformers, Phikon-v2 | cpsam segmentation, all classification |
| `cellpose3` | cellpose **3.1.1.3**, torch cu126 | cyto2 / cyto3 segmentation and fine-tuning |

Both live under `C:/Users/Labsfs/AppData/Local/miniconda3/envs/`. The scripts find them
automatically and also look under a second Windows account (`lababr`), which has its own copies.

GPU: NVIDIA RTX A2000 12 GB. Cellpose uses it; **InstanSeg runs on CPU** because QuPath
downloaded the CPU build of PyTorch.

---

## Layout

```
Mathilde/
├── README.md                    this file
├── CLAUDE.md                    conventions and traps for anyone (or any agent) editing the code
├── SFS/
│   ├── Image_BAL_#2.vsi         the slide (Olympus VS200)
│   └── bal2.zarr/2/0            same pixels, for Python (1, 3, 1, 52623, 55481) uint8
├── QuPath Project/              the QuPath project
│   ├── models/                  fine-tuned Cellpose models (.cpm) + their training logs
│   ├── classifiers/classes.json where the BAL class list is stored
│   └── scripts/ -> junction to Mathilde Data/qupath_scripts
└── Mathilde Data/
    ├── qupath_scripts/          ALL QuPath scripts live here (the single source)
    ├── bal_classify/            the Python classifier and its outputs
    ├── cellpose_ground_truth/   shared training corpus (image/mask pairs), ~8 GB
    ├── cell_segmentation.ipynb  original notebook pipeline
    └── mathilde_test.ipynb      VSI -> zarr conversion
```

**The scripts folder is published to QuPath through directory junctions**, so
`Mathilde Data/qupath_scripts/` is the only copy. Editing a file there updates what QuPath
runs. (Hard links were tried first and silently went stale — see CLAUDE.md.)

---

## Pipeline

```
     segment                    measure                     classify
  ┌───────────┐            ┌──────────────┐           ┌────────────────┐
  │  cyto3    │  cells     │ 12 notebook  │ features  │  Phikon-v2     │  cell type
  │  +        │──────────▶ │ features +   │─────────▶ │  embeddings +  │──────────▶
  │ InstanSeg │  nuclei    │ nucleus/cyto │           │  logistic reg. │
  └───────────┘            └──────────────┘           └────────────────┘
```

**Default model: `cyto3` cells + InstanSeg nuclei, paired into QuPath cell objects.**
Pairing gives a true `nc_ratio`, nuclear shape on its own, and cytoplasm colour with the
nucleus excluded — the features that actually separate BAL cell types.

### Measurements per cell

Always: `area_um2`, `equiv_diameter_um`, `eccentricity`, `solidity`, `extent`,
`mean_r/g/b`, `std_intensity`, `mean_hue`, `mean_sat`, `nucleus_fraction`
(the notebook's 12, same names, verified to within ~2%).

With a paired nucleus, additionally: `nc_ratio`, `nucleus_area_um2`, `nucleus_diameter_um`,
`nucleus_solidity`, `nucleus_circularity`, `nucleus_eccentricity`, `cyto_area_um2`,
`cyto_mean_r/g/b`, `cyto_r_minus_g`, `cyto_std_intensity`.

---

## Measured facts

Keep these in mind before optimising anything.

| Fact | Value |
|---|---|
| Typical cell | 7.8 µm diameter (58 px), 47 µm² |
| cyto3 vs cpsam, clumpy region | 63 cells vs 40 — cpsam merges clumps |
| Whole-slide segmentation | ~2860 tiles, ~3 s each ≈ **2.4 h** |
| Throughput | ~0.45 MP/s, flat across tile sizes |
| Tissue masking saving | only ~28% (cells are sparse but scattered) |
| Half resolution | 4x faster, counts did not drop (unverified visually) |
| InstanSeg nuclei | 2 s per region on CPU |
| Phikon-v2 embedding | ~1 s per 60 cells on GPU |

---

## Known limitations

- **The stain has no orange/red component anywhere on the slide.** Eosinophils cannot be
  identified by colour here; only by nuclear shape. Worth confirming the Giemsa buffer pH
  with the lab before publishing a differential count.
- **QuPath clips cells at region borders**, so small regions over-count partial cells
  (~11% at 1024 px). Negligible on large regions.
- **No expert-confirmed cell labels exist yet.** Every cell-type statement so far is
  measurement-backed inference, not cytopathology.
- **Fine-tuning has not yet beaten stock cyto3.** The one apparent win turned out to be a
  diameter effect (see CLAUDE.md).
