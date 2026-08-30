# -*- coding: utf-8 -*-
"""OpenWorktree icon pipeline.

Takes the two raw AI-generated logo images (light / dark), removes the
background and produces, per theme:
  - badge variant: everything inside the big disc kept, transparent outside
  - mark  variant: strokes only (luminance keyed), fully transparent
plus standard sizes and a Windows .ico per theme.

Usage: python brand/tools/make_icons.py
"""
import os
import numpy as np
from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
SOURCES = {
    "light": os.path.normpath(os.path.join(ROOT, "source", "openworktree-light-raw.png")),
    "dark": os.path.normpath(os.path.join(ROOT, "source", "openworktree-dark-raw.png")),
}
SIZES = [512, 256, 128, 64, 48, 32, 16]
MASTER = 1024
FILL_RATIO = 0.88          # disc diameter as share of the master canvas
FEATHER = 2.0              # px, edge softness of the circle cut


def detect_disc(arr):
    """Locate the big disc by scanning the center row/column against the
    corner background colour. Returns (cx, cy, r)."""
    h, w = arr.shape[:2]
    corners = np.stack([
        arr[0:20, 0:20], arr[0:20, w - 20:w],
        arr[h - 20:h, 0:20], arr[h - 20:h, w - 20:w],
    ]).reshape(-1, 3)
    bg = np.median(corners.astype(int), axis=0)
    diff = np.abs(arr[:, :, :3].astype(int) - bg).sum(axis=2)
    mask = diff > 18  # ~6 levels per channel

    def edges(line):
        idx = np.where(line)[0]
        if len(idx) == 0:
            return None
        return idx.min(), idx.max()

    cy, cx = h // 2, w // 2
    e_x, e_y = edges(mask[cy, :]), edges(mask[:, cx])
    if not e_x or not e_y:
        raise RuntimeError("disc not found")
    x0, x1 = e_x
    y0, y1 = e_y
    px = (x1 - x0) / (x1 - x0 + 1e-9)
    disc_w, disc_h = x1 - x0, y1 - y0
    if not (0.80 * w < disc_w <= w and 0.80 * h < disc_h <= h):
        print("  ! suspicious disc bounds: w=%d h=%d (image %dx%d)" % (disc_w, disc_h, w, h))
    return (x0 + x1) / 2.0, (y0 + y1) / 2.0, (disc_w + disc_h) / 4.0


def circle_alpha(shape, cx, cy, r):
    """255 inside the disc, 0 outside, feathered at the rim."""
    h, w = shape
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    a = np.clip((r + FEATHER - d) / (2 * FEATHER), 0.0, 1.0)
    return (a * 255).astype(np.uint8)


def badge_version(arr, cx, cy, r):
    rgba = np.dstack([arr[:, :, :3], circle_alpha(arr.shape[:2], cx, cy, r)])
    return Image.fromarray(rgba, "RGBA")


def mark_version(arr, cx, cy, r, theme):
    rgb = arr[:, :, :3].astype(float)
    lum = 0.299 * rgb[:, :, 0] + 0.587 * rgb[:, :, 1] + 0.114 * rgb[:, :, 2]
    sat = rgb.max(axis=2) - rgb.min(axis=2)  # colourfulness, keeps the green accent
    if theme == "light":
        a = np.clip((238.0 - lum) / (238.0 - 100.0), 0.0, 1.0)
        a = np.maximum(a, (sat > 30).astype(float))          # green accents stay solid
    else:
        a = np.clip((lum - 35.0) / (130.0 - 35.0), 0.0, 1.0)
    alpha = (a * 255).astype(np.uint8)
    alpha = np.minimum(alpha, circle_alpha(arr.shape[:2], cx, cy, r))
    out = np.dstack([arr[:, :, :3], alpha])
    return Image.fromarray(out, "RGBA")


def pad_to_master(img):
    """Crop to content bbox and centre it on a padded square master canvas."""
    bbox = img.getbbox()
    img = img.crop(bbox)
    side = int(round(MASTER * FILL_RATIO))
    img = img.resize((side, side), Image.LANCZOS)
    canvas = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    off = ((MASTER - side) // 2, (MASTER - side) // 2)
    canvas.paste(img, off, img)
    return canvas


def export(img, outdir, stem):
    os.makedirs(outdir, exist_ok=True)
    img.save(os.path.join(outdir, "%s.png" % stem))
    paths = [os.path.join(outdir, "%s.png" % stem)]
    for s in SIZES:
        p = os.path.join(outdir, "%s-%d.png" % (stem, s))
        img.resize((s, s), Image.LANCZOS).save(p)
        paths.append(p)
    if stem.endswith("badge"):
        img.save(os.path.join(outdir, stem.replace("badge", "favicon") + ".ico"),
                 sizes=[(16, 16), (32, 32), (48, 48)])
        paths.append(os.path.join(outdir, stem.replace("badge", "favicon") + ".ico"))
    return paths


def main():
    for theme, src in SOURCES.items():
        print("[%s] %s" % (theme, src))
        img = Image.open(src).convert("RGB")
        arr = np.asarray(img)
        cx, cy, r = detect_disc(arr)
        print("  disc: center=(%.0f, %.0f) r=%.0f" % (cx, cy, r))
        outdir = os.path.join(ROOT, theme)
        made = []
        made += export(pad_to_master(badge_version(arr, cx, cy, r)), outdir, "ow-%s-badge" % theme)
        made += export(pad_to_master(mark_version(arr, cx, cy, r, theme)), outdir, "ow-%s-mark" % theme)
        for p in made:
            print("  ->", os.path.relpath(p, ROOT))


if __name__ == "__main__":
    main()
