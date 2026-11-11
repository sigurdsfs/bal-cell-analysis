# BAL cell segmentation and classification

Automated counting and typing of immune cells in bronchoalveolar lavage (BAL) cytology,
built around QuPath with Cellpose and a pathology vision transformer.

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

On a machine where this is already set up:

1. **Open QuPath 0.7.0** and the project at `QuPath Project/`.
   The image to use is `Image_BAL_#2.vsi - 40x_BF_01` (not `label` or `overview`).
2. **Automate → User scripts → `qupath_bal_gui`** — one dialog for every task.
   If the menu is empty, restart QuPath; the scripts folder is a junction (see below).
3. Draw a rectangle, select it, and run **Segment cells**.

On a new machine: install QuPath 0.7.0, Miniconda and the NVIDIA driver, then run
**`install.cmd`** (try `install.cmd -DryRun` first). It does the conda environments, the
Cellpose extension, the script junctions, the config and the model weights, and ends by
running the test harnesses. **[INSTALL.md](INSTALL.md)** has the same thing step by step,
for doing it by hand or working out why something failed.

### The tasks

| Task | What it does |
|---|---|
| **Segment cells** | Runs the chosen model on the selected region(s) and measures each cell |
| **Pair soma + nuclei into cells** | Turns outlines already in the image into cell objects: anything not a region rectangle is a soma, a `Nucleus` annotation inside it becomes its nucleus |
| **Convert selected objects** | Annotation ⇄ detection in place. Annotations are the only editable type, detections the only classifiable one, and class, measurements and object ID all survive the trip |
| **Prepare training regions** | Creates Training/Validation rectangles with editable outlines to correct |
| **Train model on corrected regions** | Fine-tunes Cellpose on that ground truth |
| **Classify cells: explore** | Clusters cells by transformer embedding; writes a montage to name the clusters |
| **Classify cells: train classifier** | Trains a cell-type classifier from the classes you set in this image |
| **Classify cells: train on labelled corpus** | Trains on every labelled crop from every slide — the model to use on a slide it has not seen |
| **Classify cells: predict** | Applies a saved classifier and writes the classes back onto the cells. Classes you set by hand are kept unless you ask for them to be re-predicted |
| **Export labelled cells** | The one export: one labelled set → Cellpose masks **and/or** per-class crops for the classifier, a tick box and a folder for each |

The two training tasks write to **Save classifier as**, and predict reads from **Classifier to
predict with** — two separate boxes, so naming a new model does not redirect the next
prediction at a file that has not been written. Either may be a bare name: `corpus_v2`
becomes `bal_classify/corpus_v2.joblib`. Leave a box empty for `bal_classify/classifier.joblib`.
Training always refits from scratch over everything in the corpus, so it **replaces** the
file it writes; keep separate names if you want to compare two models.

Two scripts run outside the dialog:
- **`qupath_batch_slides.groovy`** — runs the pipeline over every image in the project,
  unattended, saving each one and writing `batch_summary.csv`. Built for the 39-slide batch.
  Already-processed images are skipped, so an interrupted run resumes.
- **`tests/test_notebook_parity.groovy`** — checks QuPath and the notebook still measure the
  same things. Run it after changing any detection or feature code.

---

## Environments

Two conda environments, because cellpose 3 and 4 cannot coexist:

| Environment | Holds | Used for |
|---|---|---|
| `cellpose_seg` | cellpose **4.2.1** (cpsam), torch cu126, transformers, Phikon-v2 | cpsam segmentation, all classification |
| `cellpose3` | cellpose **3.1.1.3**, torch cu126 | cyto2 / cyto3 segmentation and fine-tuning |

Both live under `C:/Users/Labsfs/AppData/Local/miniconda3/envs/`. The scripts find them
automatically and also look under a second Windows account (`lababr`), which has its own copies.

GPU: NVIDIA RTX A2000 12 GB; Cellpose uses it for both the soma and the nucleus pass.

---

## Layout

```
Mathilde/
├── README.md                    this file
├── INSTALL.md                   setting the tools up on a new machine
├── install.cmd                  double-click entry point for the installer
├── install.ps1                  one-shot setup: envs, extension, junctions, config, weights
├── requirements-cellpose_seg.txt   pip requirements for the cellpose 4 / classifier env
├── requirements-cellpose3.txt      pip requirements for the cellpose 3 env
├── .gitignore                   excludes slide data, the corpus, models and project data
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
    │   ├── bal_config.groovy    every machine-specific path, in one place
    │   ├── bal_regions.groovy   which region owns a cell at a boundary
    │   ├── bal_crops.groovy     how a cell becomes a crop for the classifier
    │   └── tests/               harnesses -- run these after changing anything
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
  │  cyto3    │  soma      │ 12 notebook  │ features  │  Phikon-v2     │  cell type
  │  +        │──────────▶ │ features +   │─────────▶ │  embeddings +  │──────────▶
  │  nuclei   │  nuclei    │ nucleus/cyto │           │  logistic reg. │
  └───────────┘            └──────────────┘           └────────────────┘
```

**Default: `cyto3` soma outlines with Cellpose's `nuclei` model paired inside them.**
Pairing gives a true `nc_ratio`, nuclear shape on its own, and cytoplasm colour with the
nucleus excluded — the features that actually separate BAL cell types.

The soma model (cyto2 / cyto3 / cpsam / a fine-tune) and the optional nucleus pass are
chosen separately: they are different targets with their own ground truth, kept in
`<corpus>/train|test` and `<corpus>/nuclei/train|test`. Both go through the same
correct → export → fine-tune loop, which is why nuclei come from Cellpose and not
InstanSeg — InstanSeg cannot be trained from QuPath.

**`Output type = Cells` is only offered when the nucleus pass is on.** Without a real
nucleus, QuPath's inner boundary is the soma and the outer an invented ring, which turns
`nc_ratio` into a function of cell diameter running backwards against the real one.

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
| Cells cut by a block edge, unpadded | 2.8% at 4096 px blocks, 5.6% at 2048, 11.0% at 1024 |
| Edge-cut bias | 2.9x more likely for the largest macrophage than for a lymphocyte |
| Tissue blocks, 4096 px, unpadded | 136 blocks, 2176 tiles, ~109 min |
| Same, padded and refitted to 3804 px | 162 blocks, 2592 tiles, ~130 min, ~0% cut |
| Cost of forgetting to refit | 3400 tiles, ~170 min for the same result |

| Phikon-v2 embedding | ~1 s per 60 cells on GPU |

---

## Known limitations

- **The stain has no orange/red component anywhere on the slide.** Eosinophils cannot be
  identified by colour here; only by nuclear shape. Worth confirming the Giemsa buffer pH
  with the lab before publishing a differential count.
- **A cell crossing a region border is now segmented whole**, not clipped to the border and
  then measured as if whole. Each region is padded so Cellpose can see past its edge, and
  each cell goes to the one region holding its centroid. Two things this does *not* cover:
  a cell whose centroid lands in a block skipped as glass is still lost, and a classifier
  crop at a region edge still keeps its neighbours, since neighbours outside the region were
  never segmented and so cannot be painted out.
- **No expert-confirmed cell labels exist yet.** Every cell-type statement so far is
  measurement-backed inference, not cytopathology.
- **Fine-tuning has not yet beaten stock cyto3.** The one apparent win turned out to be a
  diameter effect (see CLAUDE.md).
