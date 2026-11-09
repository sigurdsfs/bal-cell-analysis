"""Embed every segmented cell with a pathology foundation model.

Why a foundation model and not the 12 hand-crafted features: size, shape and average
colour cannot see nuclear lobation or granule texture, which is exactly what separates
neutrophils from eosinophils from monocytes. Phikon-v2 is a ViT trained on histology
slides; its embeddings capture that texture, and a small classifier on top needs far
fewer labels than training a network from scratch.

Three input modes:
  --manifest a CSV written by qupath_export_cells.groovy: one row per cell, with the path
             to a crop QuPath cut from the OPEN image, the cell's QuPath ID and its class
             if it has one. This is the mode the dialog uses: the pixels come from whatever
             slide is open, so a classifier works on every slide.
  --crops    a folder of PNG crops written by qupath_export_labels.groovy, filed by class:
             crops/<ClassName>/<slide>_<id>.png. Labels only, pooled across slides -- this
             is what the classifier is TRAINED on.
  --geojson  DEPRECATED. Centroids from QuPath, pixels from one hard-coded zarr (ZARR
             below). It is right only for the slide that zarr was made from; on any other
             image the coordinates are clamped into that zarr's bounds and you silently
             embed unrelated tissue. Kept so old command lines still run.
Output: cells.parquet with one row per cell -- centroid, QuPath measurements, and the
        embedding as e000...eNNN columns.

    python embed_cells.py --manifest cells_manifest.csv --out cells.parquet
"""

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
import torch
import zarr
from PIL import Image

ZARR = r"C:\Users\Labsfs\Desktop\Mathilde\SFS\bal2.zarr\2\0"
PIXEL_SIZE_UM = 0.137
MODEL_ID = "owkin/phikon-v2"
# A cell is ~58 px across; 128 px gives it context (neighbouring cells, background)
# without swamping it. The model then sees the crop resized to its own input size.
CROP_PX = 128


def load_cells_from_geojson(path):
    """Read QuPath's GeoJSON export: centroid + whatever measurements travelled with it."""
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    feats = data["features"] if isinstance(data, dict) else data
    rows = []
    for f in feats:
        geom = f.get("geometry")
        if geom is None:
            continue
        # Centroid of the outer ring; good enough for cropping.
        coords = geom["coordinates"]
        ring = coords[0] if geom["type"] == "Polygon" else coords[0][0]
        pts = np.asarray(ring, dtype=float)
        props = f.get("properties", {}) or {}
        meas = props.get("measurements", {}) or {}
        rows.append(
            {
                "id": f.get("id"),
                "x": float(pts[:, 0].mean()),
                "y": float(pts[:, 1].mean()),
                "qupath_class": (props.get("classification") or {}).get("name"),
                **{k: v for k, v in meas.items()},
            }
        )
    return pd.DataFrame(rows)


def crop_stack(z, xs, ys, size=CROP_PX):
    """Cut a fixed-size RGB crop around each centroid, clamped to the slide edges."""
    H, W = z.shape[-2:]
    half = size // 2
    out = np.zeros((len(xs), size, size, 3), dtype=np.uint8)
    for i, (x, y) in enumerate(zip(xs, ys)):
        x0, y0 = int(round(x)) - half, int(round(y)) - half
        x0 = max(0, min(x0, W - size))
        y0 = max(0, min(y0, H - size))
        out[i] = np.moveaxis(z[0, :, 0, y0 : y0 + size, x0 : x0 + size], 0, -1)
    return out


def embed(crops, batch=64, device=None):
    from transformers import AutoImageProcessor, AutoModel

    device = device or ("cuda" if torch.cuda.is_available() else "cpu")
    processor = AutoImageProcessor.from_pretrained(MODEL_ID)
    model = AutoModel.from_pretrained(MODEL_ID).to(device).eval()
    feats = []
    with torch.inference_mode():
        for i in range(0, len(crops), batch):
            imgs = [Image.fromarray(c) for c in crops[i : i + batch]]
            inputs = processor(images=imgs, return_tensors="pt").to(device)
            out = model(**inputs)
            # CLS token: the model's summary of the whole crop.
            feats.append(out.last_hidden_state[:, 0].float().cpu().numpy())
            print(f"  embedded {min(i + batch, len(crops))}/{len(crops)}", flush=True)
    return np.concatenate(feats)


def load_manifest(path, crop_root=None):
    """Read one row per cell from a CSV, and the crop PNG each row points at.

    QuPath writes the crops, so this works for any slide and needs no zarr. 'crop' is a
    path relative to the manifest (or to --crop-root). Everything else in the CSV is
    carried through to the parquet: 'id' is what predictions are joined back on, and the
    QuPath measurements are what the explore montage reports per cluster.
    """
    df = pd.read_csv(path)
    if "crop" not in df.columns:
        raise SystemExit(f"{path} has no 'crop' column -- re-export from QuPath")
    # 'class' is what qupath_export_labels.groovy calls it in labels.csv.
    if "qupath_class" not in df.columns and "class" in df.columns:
        df = df.rename(columns={"class": "qupath_class"})
    if "id" not in df.columns:
        df["id"] = [Path(c).stem for c in df["crop"]]
    root = Path(crop_root) if crop_root else Path(path).parent
    files = [root / str(c) for c in df["crop"]]
    missing = [f for f in files if not f.exists()]
    if missing:
        raise SystemExit(f"{len(missing)} crop(s) in {Path(path).name} are not on disk, "
                         f"e.g. {missing[0]} -- re-export from QuPath")
    images = [np.array(Image.open(f).convert("RGB")) for f in files]
    # Crops at the slide edge come back clipped, so they are not all the same size. The
    # processor resizes each one anyway; stacking is only for convenience, so pad instead
    # of refusing a cell for sitting near the edge.
    sizes = {im.shape[:2] for im in images}
    if len(sizes) > 1:
        h = max(s[0] for s in sizes)
        w = max(s[1] for s in sizes)
        padded = np.zeros((len(images), h, w, 3), dtype=np.uint8)
        for i, im in enumerate(images):
            padded[i, : im.shape[0], : im.shape[1]] = im
        print(f"  {len(sizes)} crop sizes (cells at the image edge are clipped); "
              f"padded to {h}x{w}")
        return df, padded
    return df, np.stack(images)


def load_crops(folder):
    """Read crops/<ClassName>/*.png, taking the class from the folder name."""
    rows, images = [], []
    from PIL import Image as PILImage
    # How each crop was made. The folder accumulates over months and over slides, so it can
    # easily end up holding crops cut two different ways -- which is a mixed training set
    # with nothing to show for it. labels.csv records the style per crop; the folder-level
    # file is the fallback for crops written before that existed.
    style_file = folder / "crop_style.txt"
    folder_style = style_file.read_text().strip() if style_file.exists() else "unknown"
    per_crop = {}
    labels_csv = folder.parent / "labels.csv"
    if labels_csv.exists():
        lab = pd.read_csv(labels_csv)
        if "crop_style" in lab.columns and "crop" in lab.columns:
            per_crop = {str(c): (str(s) if pd.notna(s) else folder_style)
                        for c, s in zip(lab["crop"], lab["crop_style"])}
    for class_dir in sorted(p for p in folder.iterdir() if p.is_dir()):
        for f in sorted(class_dir.glob("*.png")):
            img = np.array(PILImage.open(f).convert("RGB"))
            images.append(img)
            # The slide name is the part of the filename before the id, written by
            # qupath_export_labels.groovy as <project>__<image>_<id8>.png.
            stem = f.stem
            rows.append({"id": stem, "qupath_class": class_dir.name,
                         "crop": str(f.relative_to(folder)),
                         "crop_style": per_crop.get(f.name, folder_style),
                         "image": stem.rsplit("_", 1)[0] if "_" in stem else "",
                         "x": np.nan, "y": np.nan})
    if not rows:
        raise SystemExit(f"no PNG crops under {folder}")
    styles = sorted({r["crop_style"] for r in rows})
    if len(styles) > 1:
        counts = {s: sum(1 for r in rows if r["crop_style"] == s) for s in styles}
        raise SystemExit(
            f"the corpus holds crops made {len(styles)} different ways: {counts}.\n"
            f"Training on the mixture fits a model to two kinds of picture at once. Run\n"
            f"'Export labelled cells' again on each slide so every crop is cut the same way\n"
            f"(the style comes from cropStyle in bal_config.groovy), or move the odd ones out.")
    print(f"crop style: {styles[0]}")
    sizes = {im.shape[:2] for im in images}
    if len(sizes) > 1:
        h = max(s[0] for s in sizes)
        w = max(s[1] for s in sizes)
        padded = np.zeros((len(images), h, w, 3), dtype=np.uint8)
        for i, im in enumerate(images):
            padded[i, : im.shape[0], : im.shape[1]] = im
        print(f"  {len(sizes)} crop sizes (older crops were not clamped at the image edge); "
              f"padded to {h}x{w}")
        return pd.DataFrame(rows), padded
    return pd.DataFrame(rows), np.stack(images)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--geojson", type=Path, help="DEPRECATED: pixels come from --zarr, one slide only")
    ap.add_argument("--manifest", type=Path,
                    help="CSV of cells with a crop path each, from qupath_export_cells.groovy")
    ap.add_argument("--crops", type=Path,
                    help="folder of class subfolders of PNG crops (multi-slide friendly)")
    ap.add_argument("--crop-root", dest="crop_root",
                    help="folder the manifest's crop paths are relative to (default: next to it)")
    ap.add_argument("--out", type=Path, default=Path("cells.parquet"))
    ap.add_argument("--crop", type=int, default=CROP_PX)
    ap.add_argument("--zarr", default=ZARR, help="only used with --geojson")
    ap.add_argument("--limit", type=int, default=0, help="embed only the first N cells")
    args = ap.parse_args()

    if args.manifest:
        cells, crops = load_manifest(args.manifest, args.crop_root)
        if args.limit:
            cells, crops = cells.head(args.limit), crops[:args.limit]
        n_lab = int(cells["qupath_class"].notna().sum()) if "qupath_class" in cells.columns else 0
        print(f"{len(cells)} cells from {args.manifest.name} "
              f"({n_lab} carrying a class), crops cut by QuPath from the open image")
    elif args.crops:
        cells, crops = load_crops(args.crops)
        if args.limit:
            cells, crops = cells.head(args.limit), crops[:args.limit]
        print(f"{len(cells)} crops from {args.crops} "
              f"({cells.qupath_class.nunique()} classes, "
              f"{cells.qupath_class.value_counts().to_dict()})")
    else:
        if not args.geojson:
            raise SystemExit("give --manifest, --crops or --geojson")
        cells = load_cells_from_geojson(args.geojson)
        if args.limit:
            cells = cells.head(args.limit)
        print(f"{len(cells)} cells from {args.geojson.name}")
        # The coordinates are QuPath's, the pixels are this one zarr's. If they are not the
        # same slide, every crop is wrong and nothing says so, because out-of-range
        # coordinates are clamped to the edge rather than raising.
        print(f"  WARNING: --geojson reads pixels from {args.zarr}")
        print( "  That is right ONLY for the slide that zarr was made from. Use --manifest.")
        z = zarr.open(args.zarr, mode="r")
        print("cutting crops...")
        crops = crop_stack(z, cells["x"].values, cells["y"].values, args.crop)
    print(f"embedding with {MODEL_ID}...")
    feats = embed(crops)
    print(f"embeddings: {feats.shape}")

    emb = pd.DataFrame(feats, columns=[f"e{i:04d}" for i in range(feats.shape[1])])
    out = pd.concat([cells.reset_index(drop=True), emb], axis=1)
    out.to_parquet(args.out)
    print(f"wrote {args.out}  ({len(out)} rows, {out.shape[1]} columns)")


if __name__ == "__main__":
    main()
