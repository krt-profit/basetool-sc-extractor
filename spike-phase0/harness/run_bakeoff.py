"""Phase-0 spike: the bake-off runner (issue #433 deliverables 1, 2, 5).

For every (model x strategy x image): Locate -> Normalize -> Read, score against the
golden set, record the RAW model output (so Phase 3 regression tests need no live GPU)
plus wall-clock timings. `ollama ps` is sampled after each model's first read for the
vendor-neutral GPU/CPU-split probe.

Usage:
  python run_bakeoff.py --models qwen3-vl:8b-instruct [--strategies schema,markdown]
                        [--images a1_213823,...] [--out ../work/bakeoff]
Golden per-image JSONs are read from ../work/golden/<id>.json.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import time
from pathlib import Path

import normalize
import read as reader
import score as scorer

SPIKE = Path(__file__).resolve().parent.parent
WORK = SPIKE / "work"
SRC_BASE = Path(r"D:/NC/Software/Coding/Java/KRT/Beispiele Raffinerieaufträge")

IMAGES = {
    "a1_213823": SRC_BASE / "Auftrag 1/Screenshot 2026-06-01 213823.png",
    "a1_213831": SRC_BASE / "Auftrag 1/Screenshot 2026-06-01 213831.png",
    "a1_213847": SRC_BASE / "Auftrag 1/Screenshot 2026-06-01 213847.png",
    "a1_213901": SRC_BASE / "Auftrag 1/Screenshot 2026-06-01 213901.png",
    "a2_222230": SRC_BASE / "Auftrag 2/Screenshot 2026-06-01 222230.png",
    "a2_222247": SRC_BASE / "Auftrag 2/Screenshot 2026-06-01 222247.png",
    "a2_222253": SRC_BASE / "Auftrag 2/Screenshot 2026-06-01 222253.png",
    "a2_222303": SRC_BASE / "Auftrag 2/Screenshot 2026-06-01 222303.png",
    "a3_": SRC_BASE / "Auftrag 3/image.png",
    "a4_2": SRC_BASE / "Auftrag 4/image2.png",
    "a5_192716": SRC_BASE / "Auftrag 5/Screenshot 2026-06-09 192716.png",
    "a6_3": SRC_BASE / "Auftrag 6/image3.png",
}


def ollama_ps() -> str:
    try:
        return subprocess.run(["ollama", "ps"], capture_output=True, text=True, timeout=30).stdout
    except Exception as e:  # noqa: BLE001
        return f"<ollama ps failed: {e}>"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True)
    ap.add_argument("--strategies", default="schema,markdown")
    ap.add_argument("--images", default=",".join(IMAGES))
    ap.add_argument("--out", default=str(WORK / "bakeoff"))
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    norm_dir = WORK / "normalized"
    ids = [i for i in args.images.split(",") if i]

    golden = {}
    for img_id in ids:
        gpath = WORK / "golden" / f"{img_id}.json"
        if not gpath.exists():
            raise SystemExit(f"golden file missing: {gpath}")
        golden[img_id] = json.loads(gpath.read_text(encoding="utf-8"))

    preps = {img_id: normalize.prepare(IMAGES[img_id], norm_dir) for img_id in ids}

    summary_rows = []
    for model in args.models.split(","):
        probe_recorded = False
        for strategy in args.strategies.split(","):
            fn = reader.read_schema if strategy == "schema" else reader.read_markdown
            per_image = []
            t_total = 0.0
            for img_id in ids:
                result = fn(model, preps[img_id]["readImage"])
                if not probe_recorded:
                    (out_dir / f"{model.replace(':', '_').replace('/', '_')}_ollama_ps.txt").write_text(
                        ollama_ps(), encoding="utf-8"
                    )
                    probe_recorded = True
                s = scorer.score_image(golden[img_id], result["parsed"])
                t_total += result["raw"]["elapsedSec"]
                rec = {
                    "model": model,
                    "strategy": strategy,
                    "image": img_id,
                    "prep": preps[img_id],
                    "result": result,
                    "score": s,
                }
                safe = model.replace(":", "_").replace("/", "_")
                rdir = out_dir / safe / strategy
                rdir.mkdir(parents=True, exist_ok=True)
                (rdir / f"{img_id}.json").write_text(
                    json.dumps(rec, indent=2, ensure_ascii=False), encoding="utf-8"
                )
                per_image.append(s)
                print(
                    f"{model} {strategy} {img_id}: {s['correct']}/{s['cells']}"
                    f" ({result['raw']['elapsedSec']}s)"
                    + (" PARSE-FAIL" if s.get("parseFailure") else ""),
                    flush=True,
                )
            agg = scorer.summarize(per_image)
            agg.update(model=model, strategy=strategy, secPerImage=round(t_total / len(ids), 1))
            summary_rows.append(agg)
            print(f">>> {model} {strategy}: cellAccuracy={agg['cellAccuracy']}", flush=True)

    (out_dir / "summary.json").write_text(
        json.dumps(summary_rows, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(json.dumps(summary_rows, indent=2))


if __name__ == "__main__":
    main()
