"""Phase-0 spike: evaluate the location read against the golden `location` field.

Runs read.read_location on every full-frame image's `<stem>_loc.png` strip (pre-cropped
panels have no terminal header and are skipped) and compares case-insensitively against
the golden. Results land in work/bakeoff/location_eval.json.
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import read as reader
from run_bakeoff import IMAGES

WORK = Path(__file__).resolve().parent.parent / "work"


def main() -> None:
    model = sys.argv[1] if len(sys.argv) > 1 else "qwen3-vl:8b-instruct"
    results = []
    for img_id, src in IMAGES.items():
        golden = json.loads((WORK / "golden" / f"{img_id}.json").read_text(encoding="utf-8"))
        expected = golden.get("location")
        loc_img = WORK / "normalized" / f"{Path(src).stem}_loc.png"
        if not loc_img.exists():
            results.append({"image": img_id, "skipped": "pre-cropped, no header strip"})
            continue
        t0 = time.time()
        read_loc = reader.read_location(model, str(loc_img))
        ok = (
            expected is not None
            and read_loc is not None
            and expected.strip().upper() in str(read_loc).strip().upper()
        )
        rec = {
            "image": img_id,
            "golden": expected,
            "read": read_loc,
            "match": ok,
            "elapsedSec": round(time.time() - t0, 1),
        }
        results.append(rec)
        print(f"{img_id}: golden={expected!r} read={read_loc!r} match={ok}", flush=True)
    out = WORK / "bakeoff" / "location_eval.json"
    out.write_text(json.dumps({"model": model, "results": results}, indent=2), encoding="utf-8")
    evaluated = [r for r in results if "skipped" not in r]
    print(f"matches: {sum(1 for r in evaluated if r['match'])}/{len(evaluated)}")


if __name__ == "__main__":
    main()
