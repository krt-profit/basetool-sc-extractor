"""Phase-0 spike: Locate -> Normalize for refinery SETUP screenshots.

Locate is classical CV (issue #433 deliverable 3): the work-order panel chrome is a
high-contrast dark rectangle with a fixed bright header strip; we find it via a
luminance column/row profile on a downscaled frame, then crop from the NATIVE image.
Pre-cropped inputs (panel fills the frame) skip Locate entirely.

Normalize always runs client-side: Ollama silently downscales anything above ~3.2 MP,
so we never feed raw 4K frames. Target long edge 1280-1600 px, dimensions snapped to
multiples of 32 (issue #433 deliverable 3).
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image

# The model's sweet spot per the master plan section 9 / Phase 0.
TARGET_LONG_EDGE = 1536
PRECROP_MAX_DIM = 1200  # a ~500 px panel upscaled beyond ~2.4x just blurs

# Verified manually against the owner's 4K example set (all nine 3840x2160 frames
# share this terminal layout; the work-order panel is stable across them).
PANEL_4K = (950, 350, 920, 1500)  # x, y, w, h in source pixels
LOCATION_4K = (250, 200, 900, 220)  # terminal header strip holding the location


def is_precropped(img: Image.Image) -> bool:
    """A pre-cropped panel is small and portrait — the full frame is large landscape."""
    return img.width < 1000 and img.height > img.width


def _is_maroon(r: int, g: int, b: int) -> bool:
    """The SETUP tab strip chrome: dark desaturated red, ~RGB(72,49,45) at 4K."""
    return 55 <= r <= 115 and g <= 75 and b <= 70 and r - g >= 14 and r - b >= 16


def _is_cta_orange(r: int, g: int, b: int) -> bool:
    """The CONFIRM / GET QUOTE button fill: bright KRT-style orange."""
    return r >= 170 and 110 <= g <= 200 and b <= 110 and r - b >= 90


def _runs(matches: list[bool], max_gap: int) -> list[tuple[int, int]]:
    """Gap-tolerant contiguous runs over a boolean row (gaps = strip text holes)."""
    runs: list[tuple[int, int]] = []
    start = last = None
    for x, m in enumerate(matches):
        if m:
            if start is None:
                start = x
            last = x
        elif start is not None and x - last > max_gap:
            runs.append((start, last))
            start = last = None
    if start is not None:
        runs.append((start, last))
    return runs


def locate_panels(img: Image.Image) -> list[tuple[int, int, int, int]]:
    """Classical-CV Locate via the panel chrome's colour anchors — MULTI-candidate.

    Verified golden-set fact: a frame can hold SEVERAL work-order panels side by
    side (Auftrag 2: the SETUP panel next to a running PROCESSING panel), and the
    camera distance varies, so panel size/position are not fixed. A plain
    luminance profile does not work either — the whole terminal interior is
    uniformly dark (measured mean 35-64 across the 4K set).

    Anchors per panel: a maroon tab strip (~RGB(72,49,45), text holes allowed)
    spanning 8-45%% of the frame width, plus at least one bright-orange element
    (CTA button or progress bar) below it within the strip's x-extent.

    Owner-confirmed domain rule (2026-06-10): when several work-order panels sit
    side by side, the NEWEST order is always the LEFTMOST panel — and that is
    the one to extract. Boxes are therefore returned left to right and callers
    take boxes[0]; the VLM read's panelType acts only as a VALIDATION (warn if
    the leftmost panel is not SETUP), never as a selection mechanism.
    Returns candidate boxes (x, y, w, h) in native pixels, left to right.
    """
    scale = 4
    small = img.convert("RGB").resize((img.width // scale, img.height // scale))
    px = small.load()
    w, h = small.size
    max_gap = max(6, w // 80)
    # 1. Per row: gap-tolerant maroon runs of plausible strip width.
    row_runs: list[tuple[int, int, int]] = []  # (y, x0, x1)
    for y in range(h):
        matches = [_is_maroon(*px[x, y]) for x in range(w)]
        for x0, x1 in _runs(matches, max_gap):
            width = x1 - x0
            density = sum(matches[x0 : x1 + 1]) / max(1, width + 1)
            if 0.08 * w <= width <= 0.45 * w and density >= 0.35:
                row_runs.append((y, x0, x1))
    # 2. Cluster runs that overlap horizontally, sit on consecutive rows AND have
    #    similar width (environment noise above the strip must not chain in).
    clusters: list[list[tuple[int, int, int]]] = []
    for run in sorted(row_runs):
        y, x0, x1 = run
        placed = False
        for cl in clusters:
            ly, lx0, lx1 = cl[-1]
            same_width = abs((x1 - x0) - (lx1 - lx0)) <= 0.4 * max(1, lx1 - lx0)
            if y - ly <= 3 and same_width and min(x1, lx1) - max(x0, lx0) > 0.5 * (x1 - x0):
                cl.append(run)
                placed = True
                break
        if not placed:
            clusters.append([run])
    boxes: list[tuple[int, int, int, int]] = []
    for cl in clusters:
        if len(cl) < 2:  # a real strip is several rows tall even at 1/4 scale
            continue
        ys = [r[0] for r in cl]
        x0 = sorted(r[1] for r in cl)[len(cl) // 2]
        x1 = sorted(r[2] for r in cl)[len(cl) // 2]
        strip_top, strip_bot = min(ys), max(ys)
        if strip_bot - strip_top > 12:  # too tall to be a tab strip
            continue
        strip_w = x1 - x0
        # 3. Confirm with an orange element (CTA button / progress bar) below the
        #    strip. The maroon strip covers only the LEFT part of the panel (the
        #    "WORK ORDER n" zone right of it is dark gray — measured), while the
        #    CTA is right-aligned: search to the right of the strip too, and use
        #    an absolute run threshold (small/distant panels have small buttons).
        # The maroon strip covers the panel's left part only (the "WORK ORDER n"
        # zone right of it is dark gray — measured); the panel's right border
        # sits a small margin beyond max(strip end, CTA-button end). Do NOT
        # scan the whole body for orange: with two panels side by side
        # (Auftrag 2) that bleeds into the neighbour's progress bar.
        search_x1 = min(w - 1, x0 + int(strip_w * 1.9))
        bottom = None
        orange_right = x1
        for y in range(h - 1, strip_bot + 5, -1):
            matches = [_is_cta_orange(*px[x, y]) for x in range(x0, search_x1 + 1)]
            o_runs = [r for r in _runs(matches, 2) if r[1] - r[0] >= max(6, strip_w // 12)]
            if o_runs:
                bottom = y
                orange_right = max(orange_right, x0 + max(r[1] for r in o_runs))
                break
        if bottom is None:
            continue
        margin = max(3, strip_w // 40)
        top = max(0, strip_top - 4 * margin)  # include the WORK ORDER bar above
        bot = min(h - 1, bottom + 3 * margin)
        boxes.append(
            (
                max(0, x0 - margin) * scale,
                top * scale,
                (orange_right - x0 + 2 * margin) * scale,
                (bot - top) * scale,
            )
        )
    # Merge near-duplicate candidates (overlapping clusters from split strips).
    boxes.sort(key=lambda b: b[0])
    merged: list[tuple[int, int, int, int]] = []
    for b in boxes:
        if merged and b[0] < merged[-1][0] + merged[-1][2] * 0.5:
            m = merged[-1]
            x0n, y0n = min(m[0], b[0]), min(m[1], b[1])
            merged[-1] = (
                x0n,
                y0n,
                max(m[0] + m[2], b[0] + b[2]) - x0n,
                max(m[1] + m[3], b[1] + b[3]) - y0n,
            )
        else:
            merged.append(b)
    return merged


def locate_panel(img: Image.Image) -> tuple[int, int, int, int]:
    """Target-panel selection: the LEFTMOST CV candidate (= newest order, owner
    rule 2026-06-10), else the verified 4K geometry as fallback."""
    boxes = locate_panels(img)
    if boxes:
        return boxes[0]
    fx = img.width / 3840
    fy = img.height / 2160
    x, y, pw, ph = PANEL_4K
    return (int(x * fx), int(y * fy), int(pw * fx), int(ph * fy))


def snap32(v: int) -> int:
    """Snap a dimension to the nearest multiple of 32 (>= 32)."""
    return max(32, int(round(v / 32)) * 32)


def normalize(img: Image.Image) -> Image.Image:
    """Resize so the long edge hits the model sweet spot, dims snapped to /32."""
    long_edge = max(img.size)
    target = min(TARGET_LONG_EDGE, PRECROP_MAX_DIM if long_edge < 1000 else TARGET_LONG_EDGE)
    factor = target / long_edge
    nw, nh = snap32(math.ceil(img.width * factor)), snap32(math.ceil(img.height * factor))
    return img.resize((nw, nh), Image.LANCZOS if factor < 1 else Image.BICUBIC)


def prepare(src: str | Path, out_dir: str | Path, pad: int = 0, scale_tweak: float = 1.0) -> dict:
    """Locate+Normalize one screenshot; returns paths + metadata.

    pad/scale_tweak vary the crop between the two confidence passes (issue #433
    deliverable 6: identical input repeats the same systematic misread at temp 0).
    Emits <stem>_read.png (panel) and, for full frames, <stem>_loc.png (location strip).
    """
    src = Path(src)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    img = Image.open(src)
    pre = is_precropped(img)
    meta = {
        "source": str(src),
        "width": img.width,
        "height": img.height,
        "precropped": pre,
    }
    if pre:
        panel = img
        meta["cropMode"] = "precropped"
        meta["panelBox"] = None
    else:
        x, y, w, h = locate_panel(img)
        x, y = max(0, x - pad), max(0, y - pad)
        w, h = min(img.width - x, w + 2 * pad), min(img.height - y, h + 2 * pad)
        panel = img.crop((x, y, x + w, y + h))
        meta["cropMode"] = "vlm"  # spike: CV-located; production keeps the same tag space
        meta["panelBox"] = [x, y, w, h]
        fx, fy = img.width / 3840, img.height / 2160
        lx, ly, lw, lh = LOCATION_4K
        loc = img.crop((int(lx * fx), int(ly * fy), int((lx + lw) * fx), int((ly + lh) * fy)))
        loc_norm = loc.resize((snap32(loc.width * 2), snap32(loc.height * 2)), Image.BICUBIC)
        loc_path = out_dir / f"{src.stem}_loc.png"
        loc_norm.save(loc_path)
        meta["locationImage"] = str(loc_path)
    if scale_tweak != 1.0:
        panel = panel.resize(
            (int(panel.width * scale_tweak), int(panel.height * scale_tweak)), Image.BICUBIC
        )
    read_img = normalize(panel)
    read_path = out_dir / f"{src.stem}_read.png"
    read_img.save(read_path)
    meta["readImage"] = str(read_path)
    meta["readSize"] = list(read_img.size)
    return meta
