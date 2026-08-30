# -*- coding: utf-8 -*-
"""OpenWorktree theme-switch animation pipeline.

Takes the raw AI-generated transition video (light logo -> dark logo on a
black background) and produces transparent assets for the in-app badge:
  - ow-theme-switch.webp  animated WebP, alpha, primary (Chrome/Edge/
                          Firefox/Safari 14+)
  - ow-theme-switch.apng  universal fallback

Approach: the badge is centred and size-stable for the whole clip, so a
single feathered circular matte (detected on frame 1) removes the black
background without per-frame keying flicker. A static tail is trimmed.
WebM/VP9 is deliberately not produced: the local ffmpeg build's libvpx
pipeline drops the alpha plane (verified), and animated WebP has equal or
better browser coverage for this use case anyway.

Usage: python brand/tools/make_theme_switch.py
"""
import glob
import os
import shutil
import subprocess

import numpy as np
from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "source", "ow-theme-switch-raw.mp4")
OUT_DIR = os.path.join(ROOT, "animation")
TMP_RAW = os.path.join(ROOT, "tools", ".tmp_raw")
TMP_PROC = os.path.join(ROOT, "tools", ".tmp_proc")
FPS = 31
OUT_SIZE = 256
# margin chosen so the disc fills 88% of the canvas — identical to the
# static badge masters (make_icons.py FILL_RATIO), so swapping between the
# static icon and the animation is pixel-seamless in the UI.
MARGIN = 64
FEATHER = 3.0        # px, rim softness of the matte
TAIL_HOLD = 0.35     # s of static end state kept after last motion
# app playback: sampled down to ~2s — a 5.2s morph is too slow for a
# theme toggle. Every 3rd source frame (161 -> 54) at 37ms ≈ 2.0s.
STEP = 3
FRAME_MS = 37


def sh(cmd):
    print("  $", " ".join(cmd))
    subprocess.run(cmd, check=True)


def detect_disc(arr):
    h, w = arr.shape[:2]
    corners = np.stack([
        arr[0:20, 0:20], arr[0:20, w - 20:w],
        arr[h - 20:h, 0:20], arr[h - 20:h, w - 20:w],
    ]).reshape(-1, 3)
    bg = np.median(corners.astype(int), axis=0)
    diff = np.abs(arr[:, :, :3].astype(int) - bg).sum(axis=2)
    mask = diff > 15
    cy, cx = h // 2, w // 2
    xs = np.where(mask[cy, :])[0]
    ys = np.where(mask[:, cx])[0]
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    dw, dh = x1 - x0, y1 - y0
    if not (0.7 * w < dw <= w and 0.7 * h < dh <= h):
        raise RuntimeError("implausible disc: %dx%d in %dx%d" % (dw, dh, w, h))
    return (x0 + x1) / 2.0, (y0 + y1) / 2.0, (dw + dh) / 4.0


def circle_alpha(h, w, cx, cy, r):
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    a = np.clip((r + FEATHER - d) / (2 * FEATHER), 0.0, 1.0)
    return (a * 255).astype(np.uint8)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for d in (TMP_RAW, TMP_PROC):
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)

    print("[1/5] extract frames")
    sh(["ffmpeg", "-y", "-v", "error", "-i", SRC, "-vsync", "0",
        os.path.join(TMP_RAW, "f%04d.png")])
    raws = sorted(glob.glob(os.path.join(TMP_RAW, "f*.png")))
    print("  %d frames" % len(raws))

    print("[2/5] detect disc + validate static matte")
    first = np.asarray(Image.open(raws[0]).convert("RGB"))
    h, w = first.shape[:2]
    cx, cy, r = detect_disc(first)
    print("  disc: center=(%.0f, %.0f) r=%.0f" % (cx, cy, r))
    worst = 0
    for i in range(0, len(raws), 16):
        a = np.asarray(Image.open(raws[i]).convert("RGB")).astype(int)
        lum = 0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]
        yy, xx = np.mgrid[0:h, 0:w]
        outside = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) > r + 6
        worst = max(worst, float(lum[outside].max()))
    print("  max luminance outside disc across samples: %.0f" % worst)
    if worst > 34:
        raise RuntimeError("outside the disc is not black; static matte unsafe")

    print("[3/5] trim static tail")
    small = [np.asarray(Image.open(f).convert("L").resize((w // 4, h // 4))).astype(int)
             for f in raws]
    # two complementary motion metrics: slow gradual motion (the final
    # dark->bright phase) is invisible to consecutive-frame diff but shows
    # against the final frame; sudden motion is the other way round.
    d_prev = [float(np.abs(small[i] - small[i - 1]).mean()) for i in range(1, len(small))]
    d_fin = [float(np.abs(small[i] - small[-1]).mean()) for i in range(len(small))]
    last_prev = max((i for i, d in enumerate(d_prev) if d > 1.5), default=0)
    last_fin = max((i for i, d in enumerate(d_fin) if d > 0.8), default=0)
    last_motion = max(last_prev, last_fin)
    keep = min(len(raws), last_motion + 1 + round(TAIL_HOLD * FPS))
    print("  motion last seen: consecutive-diff frame %d, vs-final frame %d"
          % (last_prev + 1, last_fin + 1))
    print("  keeping %d frames (%.2fs)" % (keep, keep / FPS))

    print("[4/5] apply matte, crop, scale")
    alpha = circle_alpha(h, w, cx, cy, r)
    box = (int(cx - r - MARGIN), int(cy - r - MARGIN),
           int(cx + r + MARGIN), int(cy + r + MARGIN))
    proc = []
    for i, f in enumerate(raws[:keep]):
        rgba = np.dstack([np.asarray(Image.open(f).convert("RGB")), alpha])
        img = Image.fromarray(rgba, "RGBA").crop(box)
        img = img.resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)
        img.save(os.path.join(TMP_PROC, "p%04d.png" % (i + 1)))
        proc.append(img)

    print("[5/5] encode animated webp + apng (~%.2fs)" % (len(proc[::STEP]) * FRAME_MS / 1000))
    play = proc[::STEP]
    webp = os.path.join(OUT_DIR, "ow-theme-switch.webp")
    play[0].save(webp, save_all=True, append_images=play[1:],
                 duration=FRAME_MS, loop=0,
                 format="WEBP", lossless=False, quality=80, method=6)
    # reversed play order = dark -> light, for toggling back in the UI
    rev = os.path.join(OUT_DIR, "ow-theme-switch-reverse.webp")
    revf = play[::-1]
    revf[0].save(rev, save_all=True, append_images=revf[1:],
                 duration=FRAME_MS, loop=0,
                 format="WEBP", lossless=False, quality=80, method=6)
    apng = os.path.join(OUT_DIR, "ow-theme-switch.apng")
    play[0].save(apng, save_all=True, append_images=play[1:],
                 duration=FRAME_MS, loop=0)
    if os.path.getsize(apng) > 8 * 1024 * 1024:
        half = play[::2]
        half[0].save(apng, save_all=True, append_images=half[1:],
                     duration=FRAME_MS * 2, loop=0)
        print("  apng rebuilt at half fps to fit size budget")

    for d in (TMP_RAW, TMP_PROC):
        shutil.rmtree(d, ignore_errors=True)
    for p in (webp, rev, apng):
        print("  -> %s (%.2f MB)" % (os.path.relpath(p, ROOT),
                                     os.path.getsize(p) / 1048576.0))


if __name__ == "__main__":
    main()
