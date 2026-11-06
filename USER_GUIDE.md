# BAL cell analysis — user guide

How to segment, label, train and classify cells in bronchoalveolar lavage slides using
QuPath. Written for day-to-day use at the microscope rather than for editing the code; for
that, see `CLAUDE.md`.

> **Screenshots.** There are 19 slots, each a line reading *[Screenshot: …]*. Save your
> image into `Screenshots/` and replace that whole line with, for example:
> `![The Automate menu](Screenshots/03-automate-menu.png)`
>
> `USER_GUIDE.docx` is the same guide in Word, with an empty frame at each slot to paste
> into directly.

---

## Contents

1. [Before you start](#1-before-you-start)
2. [Opening the project](#2-opening-the-project)
3. [The dialog](#3-the-dialog)
4. [Segmenting cells](#4-segmenting-cells)
5. [Labelling cells by type](#5-labelling-cells-by-type)
6. [Improving the segmentation (fine-tuning)](#6-improving-the-segmentation-fine-tuning)
7. [Classifying cell types](#7-classifying-cell-types)
8. [Processing many slides](#8-processing-many-slides)
9. [Reading the results](#9-reading-the-results)
10. [When something goes wrong](#10-when-something-goes-wrong)

---

## 1. Before you start

You need QuPath 0.7.0 with two extensions installed — **Cellpose** and **InstanSeg** — and
the two conda environments the scripts call (`cellpose_seg` and `cellpose3`). These are
already set up on this machine.

A quick check that everything is in place: open QuPath and look under
**Extensions → Manage extensions**. Cellpose and InstanSeg should both be listed.

*[Screenshot: QuPath's Extensions → Manage extensions window, showing Cellpose and InstanSeg installed]*


---

## 2. Opening the project

1. **File → Open Project** and choose `QuPath Project/project.qpproj`.
2. Double-click the image you want. **Pick the `40x_BF_01` series**, not `label` or
   `overview` — those are the small thumbnail images stored inside the same `.vsi` file.

*[Screenshot: the QuPath project pane with the image list, showing which series to pick]*


The slide is about 55,000 × 52,000 pixels at 0.137 µm per pixel, so a typical cell is
roughly 58 pixels across. QuPath opens it as a pyramid, so zooming is quick.

---

## 3. The dialog

Everything runs from one window: **Automate → User scripts → `qupath_bal_gui`**.

If the menu is empty, restart QuPath once — the scripts folder is linked in, and QuPath
builds that menu at startup.

*[Screenshot: the Automate menu, showing User scripts → qupath_bal_gui]*


The dialog has one **Task** dropdown at the top. Everything below it enables or disables
itself according to the task you pick, and the grey text under each choice explains what it
will do. Your settings are remembered between runs.

*[Screenshot: the BAL cell segmentation dialog, with the Task dropdown open showing all eight tasks]*


### The eight tasks

| Task | Use it when |
|---|---|
| **Segment cells** | You want cells found and measured in a region |
| **Prepare training regions** | You are about to correct outlines to improve the model |
| **Export labelled cells** | You have labelled cells and want to use them for training |
| **Export corrections to training corpus** | Adding this project's corrections to the shared pool |
| **Train model on corrected regions** | Fine-tuning Cellpose on your corrections |
| **Classify cells: explore** | You have cells but no labels yet, and want to see the types present |
| **Classify cells: train classifier** | You have labelled cells and want a cell-type classifier |
| **Classify cells: predict** | Applying a trained classifier to new cells |

---

## 4. Segmenting cells

1. Draw a **rectangle** over the area you want (the rectangle tool in the toolbar).
2. Make sure it is **selected** — it turns yellow.
3. Open the dialog, choose **Segment cells**, and press OK.

*[Screenshot: a rectangle drawn over a cell-rich area, selected and highlighted yellow]*


### Which model to choose

The dropdown explains each one, but in short:

| Model | What it gives you |
|---|---|
| **cyto3 cells + InstanSeg nuclei** *(default)* | Cell outline **and** nucleus — needed for cell typing |
| **Cellpose cyto3** | Cell outlines only; the best at separating touching cells |
| **Cellpose-SAM (cpsam)** | Cell outlines only; merges dense clumps more than cyto3 |
| **InstanSeg brightfield** | Nuclei only |

**Use the default** unless you have a reason not to. Pairing the cell with its nucleus is
what makes cell typing possible: it gives a true nucleus-to-cell ratio, the nuclear shape on
its own, and the cytoplasm colour measured without the nucleus.

A fifth entry, **Custom Cellpose model…**, runs a model you fine-tuned yourself (section 6).
Picking it **opens the file chooser straight away**, so you can see which model you are
about to run while the rest of the settings are still in front of you. Cancel the chooser
and the dropdown goes back to the model you had. The **Browse…** button beside the dropdown
changes the file later; it is enabled only for this entry.

Once a file is chosen, the grey text under the dropdown names it and says which cellpose
version it will run in — taken from the filename, since a model whose name contains `cpsam`
needs the cellpose 4 environment. The rest of the dialog then follows: cell diameter greys
out for a cpsam fine-tune, and the training settings fill in that model's learning rate.
Your choice is remembered between runs, so you are not asked again next time.

### The settings

Only the settings this task uses stay enabled; the rest grey out when you pick **Segment
cells**. The defaults are right for these slides, so the short answer is *change nothing*.

| Setting | Default | What it does | When to change it |
|---|---|---|---|
| **Output type** | Detections | The kind of object created (see below) | Switch to Annotations when you want to edit outlines by hand |
| **Run on** | Selected objects only | Segment inside what you selected, or inside every annotation in the image | Use *All annotations* to do several regions in one go, unattended |
| **Cell expansion (µm)** | 2.0 | Only for Output type **Cells**: how far the outline grows outwards to form the cell boundary | Leave it; the default model does not use it |
| **Pixel size (µm/px)** | 0.137 | The resolution the model works at. Larger = coarser and faster | 0.274 halves the resolution and runs ~4× faster. Greyed out for InstanSeg, which fixes its own |
| **Tile size (px)** | 1024 | How the region is cut up for the GPU | Leave it. Throughput was flat across tile sizes, so this buys nothing |
| **Cell diameter (px)** | 58 | What cyto2/cyto3 expect a cell to measure; the image is rescaled by `30 ÷ diameter` to match | Only if your cells really are a different size. **0** means "use the model's own value" — the right choice for a model you fine-tuned |
| **Measure the notebook's 12 features** | on | Adds all the per-cell measurements after segmentation | Turn off only if you want outlines and nothing else; the classifier needs these |
| **Flag oversized objects as Clump/debris above … µm²** | on, 300 | Classifies anything bigger as `Clump/debris` so you can exclude it | Raise it if genuine cells are being flagged. The largest genuine cell measured here was 263 µm² |

Values are checked before anything runs, and a bad one stops the dialog with a message
naming the fix. The ranges are: pixel size 0.001–10, tile size 64–4096, cell expansion
0–100, clump area 1–1,000,000.

**Cell diameter is greyed out for cpsam and InstanSeg, and that is deliberate.** cpsam is
scale-free — cellpose 4 marks the setting deprecated and ignores it — and InstanSeg works at
its own fixed resolution. The field is only live for cyto2, cyto3 and models fine-tuned from
them.

### Output type

| Choice | What you get | Use it for |
|---|---|---|
| **Detections** *(default)* | Lightweight, read-only objects | Big runs, measurement export, the object classifier |
| **Annotations** | Editable objects — brush, wand and split all work on them | Checking and correcting outlines by hand. Slow once there are many thousands |
| **Cells** | Cell objects with an inner and an outer boundary | Nucleus-segmenting models only |

> **Output type and Cell expansion do nothing with the default model.** The paired
> cyto3 + InstanSeg mode builds its own cell objects — a real cell outline with a real
> nucleus inside it — so it overrides both settings. They apply to the single-model choices.
> On a whole-cell model such as cyto3 alone, **Cells** would put the whole cell in the
> nucleus slot and merely add a ring around it, which is rarely what you want.

### What you get

Objects in the image, plus a line in the log for each stage. With the default model you get
**cell objects**: an outer cell boundary with its nucleus inside, which is what section 7's
classifier reads. Cells where no nucleus was found are kept as plain detections rather than
discarded.

Each object carries **24 measurements**:

| Group | Measurements |
|---|---|
| **Shape** | `area_um2`, `equiv_diameter_um`, `eccentricity`, `solidity`, `extent` |
| **Colour** | `mean_r`, `mean_g`, `mean_b`, `std_intensity`, `mean_hue`, `mean_sat` |
| **Nucleus estimate** | `nucleus_fraction` — the dark share of the cell by thresholding; a rough stand-in when there is no real nucleus |
| **With a paired nucleus** | `nc_ratio`, `nucleus_area_um2`, `nucleus_diameter_um`, `nucleus_solidity`, `nucleus_circularity`, `nucleus_eccentricity` |
| **Cytoplasm only** | `cyto_area_um2`, `cyto_mean_r`, `cyto_mean_g`, `cyto_mean_b`, `cyto_r_minus_g`, `cyto_std_intensity` |

The four that do most of the work when telling cell types apart:

- **`nc_ratio`** — nucleus area ÷ cell area. High (0.8+) for lymphocytes, low (0.3–0.5) for
  macrophages. The single most useful number. It is a true measured ratio, unlike
  `nucleus_fraction`.
- **`nucleus_circularity`** — low for the lobed nuclei of neutrophils and eosinophils.
- **`cyto_r_minus_g`** — eosin and granules in the cytoplasm, with the nucleus excluded.
  Measured as red minus green, because eosin renders magenta in MGG.
- **`equiv_diameter_um`** — cell size in micrometres.

*[Screenshot: segmented cells with outlines visible, and the measurement table open below]*


### What the log tells you

Worth reading rather than skipping — it is how you find out that pairing went wrong:

```
Step 1/3: cell outlines with cyto3...   63 cells
Step 2/3: nuclei with InstanSeg...      61 nuclei
Step 3/3: pairing...
  58 cells paired with a nucleus
  5 cells with no nucleus found (kept as plain detections)
  2 cells held more than one nucleus -- the largest was used
Done: measured 63 cells in 4.2s
Flagged 1 object(s) as 'Clump/debris' (area > 300.0 um2)
```

A large "no nucleus found" count means the nuclei are being missed, and every one of those
cells is left without `nc_ratio` or any nuclear shape — so the classifier has much less to
go on. A large "fell outside every cell" count means the opposite: nuclei are being found
where the cell model saw nothing.

> **Timing.** About 3 seconds per 1024-pixel tile. A small region is a few seconds; a whole
> slide at full resolution is roughly 2.4 hours. See [section 8](#8-processing-many-slides)
> before starting anything that large.

---

## 5. Labelling cells by type

Labelling is how you teach both the segmentation model and the cell-type classifier. The
same labels serve both.

### Setting up the class list (once per project)

Run **`qupath_setup_classes`** from Automate → User scripts. It adds Macrophage, Lymphocyte,
Neutrophil, Eosinophil, Epithelial, Clump/debris and Uncertain to the class list, in
colours that stand out against the blue-violet stain.

*[Screenshot: the Annotations tab with the BAL class list populated and colour-coded]*


### Labelling a cell

QuPath 0.7 has **no "Set class" in the right-click menu**. Instead:

1. **Click a cell** in the viewer to select it (Ctrl+click to add more).
2. In the **Annotations tab**, click the class you want in the list.
3. **Right-click that class → "Set selected".**

The cell takes the class colour, so labelled cells are obvious as you work.

*[Screenshot: the class list with "Set selected" shown in the right-click menu]*


> If you cannot click cells, turn detections on: **View → Show detections**, or press **D**.

### How to tell the types apart

| Type | Size | Nucleus | Cytoplasm | The giveaway |
|---|---|---|---|---|
| **Macrophage** | 15–40 µm | round, off-centre | plentiful, grey-blue, often **vacuoles** | Biggest cell, lots of cytoplasm |
| **Lymphocyte** | 7–12 µm | round, dense, fills the cell | barely a rim | Small, almost all nucleus |
| **Neutrophil** | 10–14 µm | **2–5 lobes** joined by strands | pale | The lobes |
| **Eosinophil** | 12–17 µm | **two lobes** | coarse granules | Two lobes plus granular cytoplasm |
| **Epithelial** | elongated | at one end | columnar, may show cilia | Not round |
| **Clump/debris** | varies | none distinct | none — uniform magenta | No pale rim at all |

*[Screenshot: a gallery of example cells, one of each type, labelled]*


Park anything you cannot confidently call as **Uncertain** rather than guessing. A wrong
label does more damage than a missing one.

> **A caution specific to these slides.** No orange or red was found anywhere on this slide,
> so eosinophil granules do not show their usual colour. Eosinophils must be identified by
> their two-lobed nucleus instead. This may be a staining-buffer issue worth confirming with
> the lab.

---

## 6. Improving the segmentation (fine-tuning)

Only worth doing if you can point at something the model gets wrong repeatedly — merged
clumps, missed pale cells. If the outlines already look right, your effort is better spent
on labelling for the classifier.

The loop is always the same four moves:

> **pick a region → predict the cells as annotations → correct them by hand → save them
> into the ground-truth folder.** Repeat until the folder holds enough, then train on all
> of it at once.

Nothing is thrown away between rounds: the ground-truth folder accumulates, across regions
and across slides, and every training run uses everything in it.

### Step 1 — pick a region and class it

Draw a **rectangle** over an area worth teaching from, then class it (select it, then in the
Annotations tab right-click the class → **Set selected**, as in section 5):

| Class the rectangle | Where its data lands | What it is for |
|---|---|---|
| `Training` | `<corpus>/train/` | What the model learns from |
| `Validation` | `<corpus>/test/` | Scored but never trained on — how you know whether it worked |

Make at least one of each. Without a Validation region you cannot tell whether training
helped, only that it finished.

Choose regions that **show the model failing** — the clumps it merges, the pale cells it
misses — plus some ordinary areas, so it does not forget the easy cases. About 512 px square
is a good size: big enough to hold a few dozen cells, small enough to correct properly.

> **Many small regions beat one big one.** Training draws one crop per image per epoch, so
> ten rectangles give ten times the training signal of one of the same total area.

### Step 2 — predict the cells as annotations

Select the rectangle, open the dialog and run **Segment cells** with two settings that
matter more here than anywhere else:

- **Output type = Annotations.** Only annotations can be edited with the brush, and the
  exporter reads annotations — detections are invisible to it. This is the single setting
  that makes the rest of the pipeline work.
- **Model = a plain Cellpose model** (`Cellpose cyto3` or `Cellpose-SAM`). The default
  paired mode always produces detections, which you cannot correct, and it cannot be
  fine-tuned either.

Leave **Run on** at *Selected objects only* so it works inside your rectangle and nowhere
else.

*[Screenshot: the dialog set to Segment cells with Output type Annotations and a plain Cellpose model]*


### Step 3 — correct every outline

This is the part that decides whether training helps.

- **Brush (B)** and **wand (W)** to reshape an outline
- **Delete** outlines around things that are not cells
- **Draw** the cells the model missed entirely

**Correct densely.** Every cell inside the rectangle must end up outlined. A cell you leave
out keeps its pixels in the image but not in the mask, so the model is taught that a cell
there is background — a half-corrected rectangle teaches worse than no rectangle at all.

While you are here you can also **class each cell by type** (section 5). The same corrected
cells then serve both jobs: their outlines train Cellpose, their classes train the cell-type
classifier. Classifying a cell does **not** remove it from the segmentation masks.

*[Screenshot: before and after correcting outlines with the brush inside a training rectangle]*


### Step 4 — save them into the ground-truth folder

**Press Ctrl+S first.** Both the export and the training read the image's *saved* data, so
unsaved corrections simply are not there — the symptom is "Found 0 Training objects".

Then run **Export labelled cells (segmentation + classifier)** with **Training data folder**
set to your corpus folder. For every Training/Validation rectangle in the image it writes:

```
<corpus>/train/<project>__<image>_region_1.tif          the pixels
<corpus>/train/<project>__<image>_region_1_masks.tif    one label per cell
<corpus>/test/...                                       the same, for Validation rectangles
bal_classify/crops/<ClassName>/...png                   one crop per classified cell
```

The filenames carry the project and image, so **regions from different slides never collide**.
The export **adds to what is already there and never deletes**, so repeat steps 1–4 on as
many regions and slides as you like; the corpus just grows.

Run **`qupath_check_training_setup`** if anything looks wrong — it reports exactly what the
trainer will see and catches the silent failures: a class spelled wrong, unsaved work, a
region that is not a rectangle.

*[Screenshot: the Export labelled cells task with the ground-truth folder set, and the log listing the regions written]*


### Step 5 — train on everything banked so far

Run **Train model on corrected regions** with the **same corpus folder** and a plain
Cellpose model. It trains on the pooled data from every slide in that folder, not just this
image.

Training settings follow the model automatically: cyto3 trains at learning rate 0.1 with
SGD, cpsam at 0.00001 with AdamW. **Do not mix these** — the dialog refuses combinations
that would destroy the model. The finished model is saved into the project's `models/`
folder under a name recording what it came from.

*[Screenshot: the dialog on the Train task, with the corpus folder and the training settings]*


### Step 6 — check it actually helped

When training finishes, the log reports where the validation loss bottomed and whether the
saved model ended better than it started.

**Always compare the new model against the stock one on a region you did not train on.** A
fine-tune that looks better is often just a diameter effect; if the new model matches stock
at a matched diameter, it learned nothing.

To use the result, pick **Custom Cellpose model…** in the model list and choose the file
(section 4) — then set **Cell diameter** to 0, so the model uses the size it learned.

> **How much ground truth?** A few hundred corrected cells, spread over several regions and
> ideally several slides, before expecting a difference.

> **Shortcut for the first round.** **Prepare training regions** does steps 1 and 2 in one
> go: give it region centres as `x,y; x,y` (read the coordinates off QuPath's status bar)
> and it places the Training/Validation rectangles and segments inside them as annotations,
> ready to correct.

---

## 7. Classifying cell types

### If you have no labels yet

Run **Classify cells: explore**. It embeds every cell with a pathology vision transformer
and groups them, writing `bal_classify/clusters.png` — each row shows the most typical cells
of one group. Use it to see which types are on the slide before labelling anything.

*[Screenshot: clusters.png, the montage of cells grouped by cluster]*


### Training the classifier

1. Label 40–60 cells per type ([section 5](#5-labelling-cells-by-type)).
2. Run **Classify cells: train classifier**.

The log reports cross-validated scores. **Read the per-class recall, not the accuracy.**
BAL is macrophage-dominant, so a model that never predicts "eosinophil" can still look 90%
accurate while being useless for the rare types you care about.

*[Screenshot: the classification report in the log, with per-class precision and recall]*


### Applying it

**Classify cells: predict** applies a saved classifier to every cell and writes the classes
back into QuPath, so you can see them in colour, correct the wrong ones, and retrain. Each
round of corrections makes the next one better.

Predictions below 0.6 confidence are counted in the log — correcting those first is the
quickest way to improve the model.

*[Screenshot: cells coloured by predicted class in the viewer]*


---

## 8. Processing many slides

`qupath_batch_slides` runs the whole pipeline over every image in the project, unattended.

Run it from the QuPath window (not the command line, where QuPath would start it once per
image). It:

- skips the `label` and `overview` series automatically
- **skips images already processed**, so an interrupted run can simply be restarted
- keeps going if one slide fails
- saves each image as it finishes
- writes `batch_summary.csv` next to the project

*[Screenshot: batch_summary.csv open in Excel, showing cells per slide]*


> **Plan the time.** Roughly 2.4 hours per slide at full resolution — about four days for 39
> slides. Before committing to that, either segment only tissue regions, or try half
> resolution (pixel size 0.274), which is four times faster and gave the same cell counts in
> testing. Run two or three slides first and check the results are what you want.

---

## 9. Reading the results

**In QuPath:** Measure → Show detection measurements for the table; Measure → Show
measurement maps to colour cells by any single measurement. Colouring by `nc_ratio` makes
the lymphocyte/macrophage split visible at a glance.

*[Screenshot: the measurement map coloured by nc_ratio]*


**Outside QuPath:** `batch_summary.csv` holds per-slide counts;
`bal_classify/predictions.csv` holds each cell's predicted type and confidence.

### Two things to know before reporting numbers

- **Cells at the edge of a region are counted whole but measured partially.** On a small
  region that is about 11% of cells; on a large one it is negligible.
- **Objects over 300 µm² are flagged `Clump/debris`.** The largest genuine cell measured
  here was 263 µm². Keep them out of differential counts, or re-segment them.

---

## 10. When something goes wrong

| What you see | What it means |
|---|---|
| **"Found 0 Training objects"** | The project is not saved. Press Ctrl+S and run again. |
| **Masks look completely black** | They are fine. Masks store cell *numbers* (1, 2, 3…), which look black. In Fiji use Image → Adjust → Brightness/Contrast → Auto. |
| **The User scripts menu is empty** | Restart QuPath; the menu is built at startup. |
| **"No cells found"** | Segment a region first, and check View → Show detections. |
| **Training loss goes up** | Learning rate too high for that model, or too little ground truth. Check that the model and its settings match (section 6, step 4). |
| **A fine-tuned model behaves oddly** | Set the cell diameter to 0 — a fine-tuned model stores its own and overriding it undoes part of the training. |
| **Classes do not appear in the list** | Run `qupath_setup_classes`, then reopen the project. |

*[Screenshot: the QuPath log window, where all script messages appear]*


The log is where every script reports what it did. If something looks wrong, read it from
the top — the scripts are written to say what happened, what it means, and what to do next.
