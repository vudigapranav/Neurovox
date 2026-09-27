#!/usr/bin/env python3
"""Measure the orb silhouette in screenshots.

For each image, threshold the bright particles, close small gaps and take the
largest outer contour. Reports its equivalent radius (relative to the layout
radius) and solidity (area / convex-hull area; lower means a spikier outline). Used to show that
voice input changes the orb's *shape*, not just its brightness.

    python3 scripts/orb_shape_metric.py results_dir
Reads results.json written by visual-check.mjs (scenario "voice-synthetic").
"""
import json
import math
import sys
from pathlib import Path

import cv2
import numpy as np


def profile(path, cx, cy, r, threshold=38):
    """Silhouette of the orb: bright-pixel mask -> closed -> largest contour.

    equivalent_radius: sqrt(area / pi) relative to the layout radius (overall size).
    solidity: contour area / convex hull area (1.0 = smooth convex blob, lower = spikier outline).
    """
    img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    pad = int(r * 1.7)
    x0, y0 = max(0, int(cx - pad)), max(0, int(cy - pad))
    crop = img[y0:int(cy + pad), x0:int(cx + pad)]
    crop = cv2.GaussianBlur(crop, (5, 5), 0)
    mask = (crop > threshold).astype(np.uint8) * 255
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    c = max(contours, key=cv2.contourArea)
    area = cv2.contourArea(c)
    hull = cv2.contourArea(cv2.convexHull(c))
    return {"equivalent_radius": float(math.sqrt(area / math.pi) / r), "solidity": float(area / hull)}


def main(folder):
    folder = Path(folder)
    results = json.loads((folder / "results.json").read_text())
    voice = results["scenarios"]["voice-synthetic"]
    orb = voice["orb"]
    out = {}
    for group in ("quietShots", "loudShots"):
        rows = [profile(folder / name, orb["x"], orb["y"], orb["r"]) for name in voice[group]]
        out[group] = {"frames": rows,
                      "equivalent_radius": float(np.mean([r["equivalent_radius"] for r in rows])) if rows else None,
                      "solidity": float(np.mean([r["solidity"] for r in rows])) if rows else None}
    print(json.dumps(out, indent=2))
    (folder / "shape-metric.json").write_text(json.dumps(out, indent=2))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "visual-check-output")
