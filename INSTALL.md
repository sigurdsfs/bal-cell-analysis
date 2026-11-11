# Installing the tools this project needs

What to install, in what order, on a fresh Windows machine. `README.md` says what the
project *is*; `CLAUDE.md` is about editing the code. This file is only about getting it to
run.

Budget about an hour, most of it downloads.

---

## The short way: run the installer

Install **QuPath 0.7.0**, **Miniconda** and the **NVIDIA driver** yourself — those are three
separate installers with their own licence prompts, and silently automating someone else's
installer is how you end up with a broken machine and no error message. Then:

```
install.cmd -DryRun      # report what it would do, change nothing
install.cmd              # do it
```

(Or `.\install.ps1` directly. The `.cmd` wrapper exists because Windows blocks
double-clicked `.ps1` files by default; it bypasses that for one process only and changes
no machine setting.)

It checks everything first and **stops without touching anything** if a prerequisite is
missing, naming what to install. Then it creates both conda environments from the pinned
requirements, confirms PyTorch can actually see the GPU, downloads the Cellpose extension
into QuPath's extensions folder, makes the script junctions, writes
`bal_config.local.groovy` with the paths it just found, pre-fetches the model weights so
your first segmentation is not a 4 GB wait, and finishes by running the project's own test
harnesses.

**It is safe to re-run.** Anything already in place is detected and left alone; nothing is
deleted or replaced without `-Force`, and an existing `bal_config.local.groovy` is backed up
before being rewritten.

| Switch | |
|---|---|
| `-DryRun` | report only, change nothing |
| `-WithCpsam` | also fetch the 1.23 GB Cellpose-SAM weights |
| `-Force` | recreate environments, re-download, replace junctions |
| `-SkipEnvs` `-SkipExtension` `-SkipWeights` `-SkipConfig` `-SkipVerify` | do part of it |
| `-CpuOnly` / `-Cuda` | force the CPU or CUDA build of PyTorch instead of auto-detecting |
| `-ExtensionZip <path>` | install the Cellpose extension from a zip you downloaded by hand |
| `-ExtensionZipUrl <url>` | fetch it from an internal mirror instead of GitHub |
| `-EnvSuffix <text>` | append to both environment names, for a trial install that cannot touch a working one |
| `-CondaExe` `-QuPathDir` `-QuPathUserDir` | override detection |

**No GPU is fine.** The installer detects it and installs the CPU build of PyTorch instead,
which also saves about 2.5 GB of CUDA libraries that could never load. Measured on the
development machine: cyto3 segmentation is **about 7x slower on CPU** — 4.4 s against 0.6 s
for a 1024 px tile. A region you draw by hand still finishes in seconds to a minute, so the
interactive workflow is unaffected; a whole slide goes from roughly 2.4 hours to most of a
day, which is an overnight `qupath_batch_slides.groovy` run rather than a wait. Not measured
on CPU: cpsam (a 304M-parameter transformer — expect considerably worse than 7x) and the
Phikon-v2 classifier, so prefer cyto3 on a machine without a GPU.

**It never downloads the Cellpose extension twice.** An extension already installed through
QuPath's own extension catalog lives under `extensions\catalogs\...` rather than loose in
`extensions\`, and is detected there. The download is the only step that needs the internet;
if your network blocks it, drop the release zip next to `install.ps1` or pass
`-ExtensionZip`, and the installer uses that instead.

Four things it cannot do, and lists for you at the end: restart QuPath, create the project
and pick the image series, set the class list (it belongs to the QuPath *window*, so QuPath
would overwrite anything written headlessly), and the one real end-to-end test — segmenting
a rectangle.

The rest of this file is what the installer does, step by step, for when you want to do it
by hand or work out why something failed.

**Every version below was read off the working machine**, not from documentation, so these
are versions known to work together rather than "latest". The two `requirements-*.txt` files
in the repository root were generated the same way, and both were checked against their own
environment with `pip install --dry-run`: no conflicts, nothing to change. That proves the
pins are mutually consistent — it does **not** prove a fresh machine can resolve them from
the network, so treat a failed pin as a pin to relax, not a wall.

---

## 0. Before you start

| Need | This machine has | Notes |
|---|---|---|
| OS | Windows 11 Pro | The paths in `bal_config.groovy` are Windows. QuPath and Cellpose run on macOS/Linux, but you would be porting the config. |
| GPU | NVIDIA RTX A2000 12 GB | Required in practice. Whole-slide segmentation is ~2 h on this GPU; CPU is not viable. |
| NVIDIA driver | — | **A driver is all you need.** PyTorch ships its own CUDA 12.6 runtime, so do *not* install a CUDA toolkit. |
| Conda | conda 26.7.2 (Miniconda) | Miniconda or Miniforge; Anaconda works too. |
| Disk | — | Measured here: **5.5 GB** `cellpose_seg` + **4.9 GB** `cellpose3` + **3.6 GB** of model weights (2.4 GB Cellpose, 1.2 GB Phikon-v2) = **~14 GB**. The training corpus is another ~8 GB, but that is data, not install. |

You also need the slide itself. No image data is in the repository — `.vsi`, `.ets` and
`.zarr` are git-ignored because they are tens of GB.

---

## 1. QuPath 0.7.0

Download the Windows build of **QuPath 0.7.0** from <https://qupath.github.io> and install
it. On this machine it lives at:

```
C:\Users\<you>\AppData\Local\QuPath-0.7.0\
```

Two executables matter:

- `QuPath-0.7.0.exe` — the normal GUI.
- `QuPath-0.7.0 (console).exe` — the same program with a console attached. **This is the one
  the test harnesses use**, because it can run a script from the command line and print to
  stdout.

Then check where QuPath keeps its user data: **Edit → Preferences → General → QuPath user
directory**. On this machine:

```
C:\Users\<you>\QuPath\v0.7\
```

Everything in the next two steps goes inside that folder. Call it `<userdir>` from here on.

> **Version matters.** The project is pinned to 0.7.0 and `CLAUDE.md` tells anyone editing
> the code to verify against the v0.7.0 source. QuPath's scripting API changes between
> minor releases — 0.7 has no "Set class" in the right-click menu, for instance, which is
> why `qupath_setup_classes.groovy` explains the class list instead.

---

## 2. The Cellpose extension

Two files go into `<userdir>\extensions\`:

```
<userdir>\extensions\qupath-extension-cellpose-0.12.1.jar
<userdir>\extensions\run-cellpose-qc.py
```

1. **The jar** — get `qupath-extension-cellpose-0.12.1.jar` from
   <https://github.com/BIOP/qupath-extension-cellpose/releases>. Drag it onto the QuPath
   window, or copy it into the folder by hand.
2. **`run-cellpose-qc.py`** — from `QC/run-cellpose-qc.py` in that same repository (it is a
   file in the repo, not a release asset). Since extension version 0.4, quality control runs
   *automatically* at the end of Cellpose training and calls this script.
   **Strictly optional**: without it, training still completes and QuPath simply tells you
   the script could not be found and skips the QC step. With it, you get validation metrics.
   It needs `scikit-image`, which both environments below already have.
3. **Restart QuPath.** Extensions are only picked up at startup.

Confirm it loaded: **Extensions** menu should list *Cellpose*.

**You do not need to set the Python paths by hand.** The extension's own instructions tell
you to paste them into Edit → Preferences → Cellpose/Omnipose — it has separate fields for
Cellpose v<4 and for Cellpose-SAM, matching the two environments below. Every script here
sets both programmatically from `bal_config.groovy` instead, so the dialog works on a fresh
QuPath profile. Filling the preferences in as well does no harm, and is worth doing if you
ever want to use the extension's own menu commands rather than these scripts.

### Extensions you do *not* need

This machine also has **InstanSeg v0.1.7** and **OMERO v0.2.5**, installed through QuPath's
own extension catalog. Neither is required: `qupath_instanseg_detection.groovy` is on disk
and still runs standalone, but nothing in the dialog reaches it, and InstanSeg could not be
fine-tuned from QuPath, which is why nuclei come from Cellpose instead. InstanSeg's GPU path
is also blocked on Windows — the DJL build available is CUDA 12.8 only and fails to load.
Skip both.

---

## 3. Two Python environments

**Why two: cellpose 3 and cellpose 4 cannot coexist in one environment.** They are different
packages with the same name. The project needs both, because `cpsam` is a cellpose-4 model
and `cyto2`/`cyto3`/`nuclei` are cellpose-3 models, and any model fine-tuned from one belongs
to that generation.

| Environment | Holds | Used for |
|---|---|---|
| `cellpose_seg` | cellpose **4.2.1.1** + transformers + scikit-learn | cpsam segmentation, **all classification** |
| `cellpose3` | cellpose **3.1.1.3** | cyto2 / cyto3 / nuclei segmentation and fine-tuning |

Both are Python **3.11.16** here, and both use **torch 2.14.0+cu126**.

### 3a. `cellpose_seg`

```bash
conda create -n cellpose_seg python=3.11 -y
conda activate cellpose_seg
pip install -r requirements-cellpose_seg.txt
```

`requirements-cellpose_seg.txt` is in the repository root. It carries the
`--extra-index-url` line that makes the `+cu126` PyTorch wheels findable — those are not on
PyPI, so installing `torch==2.14.0+cu126` without it fails with "no matching distribution".
The file pins only direct dependencies and comments each one.

What each non-obvious one is for:

- **transformers** — loads Phikon-v2, the vision transformer that embeds cell crops.
- **pyarrow** — the embeddings are passed between QuPath and Python as Parquet.
  (Watch out when editing: Parquet round-trips as Arrow-backed columns, and `.values` then
  breaks sklearn indexing. See `CLAUDE.md`.)
- **scikit-learn + joblib** — the logistic regression on top of the embeddings, and the file
  it is saved in.
- **umap-learn, matplotlib** — the cluster montage in the "explore" task.
- **zarr** — only for `embed_cells.py --geojson`, a deprecated path that reads one hard-coded
  slide. You can skip it if you never use that flag.

### 3b. `cellpose3`

Far fewer packages — it only ever runs Cellpose itself — but **not much smaller on disk**:
4.9 GB against 5.5 GB, because torch dominates both. Do not count on this one being cheap.

```bash
conda create -n cellpose3 python=3.11 -y
conda activate cellpose3
pip install -r requirements-cellpose3.txt
```

Note it ends up on **numpy 2.0.2**, while `cellpose_seg` has **2.4.6**. That difference is
cellpose 3's own constraint — another reason the two cannot share an environment. Let pip
resolve it; don't pin numpy yourself.

### 3c. Check both

```bash
conda activate cellpose_seg
python -c "import cellpose, torch; print(cellpose.version, torch.__version__, torch.cuda.is_available(), torch.cuda.get_device_name(0))"
```

You want `True` and your GPU's name. If you get `False`, the NVIDIA driver is the thing to
fix — reinstalling PyTorch will not help, because the CUDA runtime is already in the wheel.

Then the same for `cellpose3`.

---

## 4. Model weights (~4 GB, downloaded on first use)

Nothing to do here in advance — but know where it goes, and that **the first run of each
needs internet**.

**Cellpose** downloads to `C:\Users\<you>\.cellpose\models\`. Measured on this machine:

| File | Size | What |
|---|---|---|
| `cpsam` | 1.23 GB | Cellpose-SAM, the cellpose-4 model (a 304M-parameter transformer) |
| `cyto3` | 25 MB | the default soma model (a 6.6M UNet) |
| `cyto2torch_0` | 25 MB | cyto2 |
| `nucleitorch_0` | 25 MB | the nucleus model |
| | **2.4 GB total** | |

**Phikon-v2** downloads from Hugging Face to
`C:\Users\<you>\.cache\huggingface\hub\models--owkin--phikon-v2\` — **1.2 GB**. The model id
is `owkin/phikon-v2`, hard-coded in `bal_classify/embed_cells.py`.

You will see this on every classification run:

```
Warning: You are sending unauthenticated requests to the HF Hub.
```

It is harmless — a rate-limit notice, not an error. Set `HF_TOKEN` if you want faster
downloads. After the first run the model is cached and works offline.

---

## 5. Publish the scripts to QuPath

`Mathilde Data\qupath_scripts\` is the **single copy** of every script. QuPath reaches it
through **directory junctions**, so editing a file there changes what QuPath runs, with no
copying step to forget.

Two junctions, both pointing at the same folder. In `cmd` (not PowerShell — `mklink` is a
cmd builtin; no administrator rights needed for `/J`):

```cmd
mklink /J "C:\Users\<you>\QuPath\v0.7\scripts" "C:\...\Mathilde\Mathilde Data\qupath_scripts"
mklink /J "C:\...\Mathilde\QuPath Project\scripts" "C:\...\Mathilde\Mathilde Data\qupath_scripts"
```

- The first makes them appear under **Automate → User scripts** in QuPath.
- The second is the project's own scripts folder, which some QuPath features look in.

Verify with `dir /AL` — you should see `<JUNCTION>` next to `scripts` and the target in
brackets.

> **Never use hard links here.** It was tried. Editing a file replaces the inode, the menu
> copy keeps pointing at the old one, and QuPath silently runs stale code — a failure with
> no symptom except wrong behaviour.

If **Automate → User scripts** is empty after making the junction, restart QuPath; the menu
is built at startup.

---

## 6. Point the config at your machine

Every machine-specific path lives in one file, `Mathilde Data\qupath_scripts\bal_config.groovy`.
**Do not edit it.** Copy it instead:

```
Mathilde Data\qupath_scripts\bal_config.local.groovy
```

That name is git-ignored, it overrides `bal_config.groovy`, and **only the keys you set are
overridden** — so delete everything from your copy except what actually differs.

The keys that usually differ on a new machine:

```groovy
[
    pythonCellpose4 : ["C:/Users/<you>/AppData/Local/miniconda3/envs/cellpose_seg/python.exe"],
    pythonCellpose3 : ["C:/Users/<you>/AppData/Local/miniconda3/envs/cellpose3/python.exe"],
    projectRoot     : "C:/Users/<you>/.../Mathilde",
    balClassifyDir  : "C:/Users/<you>/.../Mathilde/Mathilde Data/bal_classify",
    groundTruthDir  : "C:/Users/<you>/.../Mathilde/Mathilde Data/cellpose_ground_truth",
]
```

Notes:

- The Python keys are **lists**, and the first path that exists wins. That is how this machine
  serves two Windows accounts with their own conda installs — add yours to the list rather
  than replacing it, if you are sharing the machine.
- Use forward slashes.
- The file is looked for in two places: beside the running copy of the scripts
  (`<userdir>\QuPath\v0.7\scripts`) and in `Mathilde Data\qupath_scripts`. Because of the
  junction those are the same folder, so one copy covers both.
- A broken local config is ignored rather than fatal; you will see
  `bal_config: ignoring bal_config.local.groovy (...)` in the log. Watch for that line — it
  means your paths are *not* being applied.

A good sign on startup: `bal_config: applied N override(s) from bal_config.local.groovy`.

---

## 7. The QuPath project and the class list

1. **File → Project → Create project**, point it at an empty folder (this repo expects
   `Mathilde\QuPath Project\`).
2. **Add images** → your `.vsi`. When QuPath asks which series, pick the one named like
   **`40x_BF_01`** — *not* `label` and *not* `overview`. Those are the small thumbnail
   series bundled in the same file, and measuring them gives wrong answers at the wrong
   scale.
3. Open the image, then run **`qupath_setup_classes.groovy`** once from
   Automate → User scripts. It adds the seven cell-type classes: Macrophage, Lymphocyte,
   Neutrophil, Eosinophil, Epithelial, Clump/debris and Uncertain.
   (`Training` and `Validation` are not in that list — they appear by themselves the first
   time you run *Prepare training regions*, which is what creates rectangles of those
   classes. A finished project has nine.)
4. **Save the project** (Ctrl+S).

> **Step 4 is not optional.** The class list belongs to the *window*, not the project, and
> QuPath writes window → project on save. Classes persist in
> `<project>\classifiers\classes.json`, not in `project.qpproj`.

Then: **Automate → User scripts → `qupath_bal_gui`** is the single entry point for every
task. Draw a rectangle, select it, and run *Segment cells*.

---

## 8. Check the install

The project tests itself. Run the harnesses that need no image first — these exercise real
code, not just parsing:

```bash
cd /c/Users/<you>/.../Mathilde
Q="/c/Users/<you>/AppData/Local/QuPath-0.7.0/QuPath-0.7.0 (console).exe"

"$Q" script "Mathilde Data/qupath_scripts/tests/compile_only.groovy"
"$Q" script "Mathilde Data/qupath_scripts/tests/test_api_calls.groovy"
"$Q" script "Mathilde Data/qupath_scripts/tests/test_config_block.groovy"
"$Q" script "Mathilde Data/qupath_scripts/tests/test_gui_all.groovy"
"$Q" script "Mathilde Data/qupath_scripts/tests/test_region_boundary.groovy"
"$Q" script "Mathilde Data/qupath_scripts/tests/test_convert_objects.groovy"
"$Q" script "Mathilde Data/qupath_scripts/tests/test_crop_styles.groovy"
```

Expected, as of this commit: `test_api_calls` "All exist.", `test_config_block` 10 correct /
0 broken, `test_gui_all` 40 combinations OK, `test_region_boundary` 38 checks,
`test_convert_objects` 38, `test_crop_styles` 25. `compile_only` reports one `OK` per script
and no `BROKEN` — the total is not a fixed number, because it also compiles
`bal_config.local.groovy` when you have one.
`CLAUDE.md` lists the full set and what each one covers.

A harness failing here is an install problem, not a code problem — most often the hard-coded
`C:/Users/Labsfs/Desktop/Mathilde/...` path inside the test files, which the harnesses use
directly rather than through `bal_config`. Edit those paths, or put the project where they
expect it.

Then prove the GPU path end to end, which no harness can do: open the image, draw a small
rectangle, select it, and run **Segment cells** with cyto3. You should get outlines in
roughly a minute. If that works, so does everything behind it — conda env, extension, GPU,
weights and junction.

---

## Troubleshooting

**"No cellpose_seg environment found for this Windows user."**
`pythonCellpose4` in your config points at a `python.exe` that does not exist. Check the path
exists literally, with forward slashes.

**"'cyto3' is a cellpose 3 model, but no cellpose3 environment was found."**
The second environment is missing or `pythonCellpose3` is wrong. Either fix it, or pick
Cellpose-SAM (cpsam) in the dialog, which uses `cellpose_seg`.

**Automate → User scripts is empty.**
Restart QuPath. If still empty, the junction is wrong — `dir /AL` in `<userdir>\QuPath\v0.7`
should show `<JUNCTION> scripts [<target>]`.

**The Extensions menu has no Cellpose entry.**
The jar is not in `<userdir>\extensions\`, or QuPath has not been restarted since it was put
there. Confirm the user directory in Edit → Preferences — a QuPath that was installed twice
can have two.

**`torch.cuda.is_available()` is False.**
NVIDIA driver, not PyTorch. The CUDA runtime is inside the wheel; a toolkit install will not
change this.

**"Found 0 Training objects", then `ERROR: no files in --dir folder`.**
Not an install problem. Cellpose training reads each image's *saved* data, so unsaved
rectangles do not exist as far as it is concerned. Save the project (Ctrl+S) and run again.

**Everything is slow and the GPU is idle.**
Check which environment the log says it is using. A cellpose-3 model dispatched to the
cellpose-4 environment (or the reverse) is the usual cause.

---

## What is not covered here

- **Getting the slide data.** Not in the repository; `README.md` describes the file and
  series that the measurements were taken from.
- **The training corpus** (`Mathilde Data\cellpose_ground_truth\`, ~8 GB). Hand-corrected
  ground truth, git-ignored, and expensive to recreate — copy it from a backup rather than
  re-deriving it.
- **The notebooks** (`Mathilde Data\*.ipynb`). They need a Jupyter kernel in `cellpose_seg`
  (`pip install jupyter`), which nothing else here requires.
