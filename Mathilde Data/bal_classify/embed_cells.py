"""Embed every segmented cell with a pathology foundation model.

Why a foundation model and not the 12 hand-crafted features: size, shape and average
colour cannot see nuclear lobation or granule texture, which is exactly what separates
neutrophils from eosinophils from monocytes. Phikon-v2 is a ViT trained on histology
slides; its embeddings capture that texture, and a small classifier on top needs far
fewer labels than training a network from scratch.

Input : cells exported from QuPath as GeoJSON (export_cells.groovy writes this),
        or --scan to segment fresh regions with Cellpose directly from the zarr.
Output: cells.parquet with one row per cell -- centroid, QuPath measurements, and the
        embedding as e000...eNNN columns.

    python embed_cells.py --geojson cells.geojson --out cells.parquet
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--geojson", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=Path("cells.parquet"))
    ap.add_argument("--crop", type=int, default=CROP_PX)
    ap.add_argument("--limit", type=int, default=0, help="embed only the first N cells")
    args = ap.parse_args()

    cells = load_cells_from_geojson(args.geojson)
    if args.limit:
        cells = cells.head(args.limit)
    print(f"{len(cells)} cells from {args.geojson.name}")

    z = zarr.open(ZARR, mode="r")
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
