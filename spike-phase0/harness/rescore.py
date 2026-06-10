"""Re-score recorded bake-off outputs after a scorer change — no live GPU needed.

Loads every work/bakeoff/<model>/<strategy>/<image>.json, re-runs score.score_image
on the recorded parsed output against the current golden file, rewrites the embedded
score and regenerates summary.json. Keeps `secPerImage` from the recorded timings.
"""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path

import score as scorer

WORK = Path(__file__).resolve().parent.parent / "work"
BAKEOFF = WORK / "bakeoff"


def main() -> None:
    summary_rows = []
    for model_dir in sorted(p for p in BAKEOFF.iterdir() if p.is_dir()):
        for strat_dir in sorted(p for p in model_dir.iterdir() if p.is_dir()):
            per_image = []
            groups: dict[str, float] = defaultdict(float)
            model = strategy = None
            n = 0
            for f in sorted(strat_dir.glob("*.json")):
                rec = json.loads(f.read_text(encoding="utf-8"))
                model, strategy = rec["model"], rec["strategy"]
                golden = json.loads(
                    (WORK / "golden" / f"{rec['image']}.json").read_text(encoding="utf-8")
                )
                s = scorer.score_image(golden, rec["result"]["parsed"])
                rec["score"] = s
                f.write_text(json.dumps(rec, indent=2, ensure_ascii=False), encoding="utf-8")
                per_image.append(s)
                groups["t"] += rec["result"]["raw"]["elapsedSec"]
                n += 1
            if not per_image:
                continue
            agg = scorer.summarize(per_image)
            agg.update(model=model, strategy=strategy, secPerImage=round(groups["t"] / n, 1))
            summary_rows.append(agg)
            print(f">>> {model} {strategy}: cellAccuracy={agg['cellAccuracy']} ({n} images)")
    (BAKEOFF / "summary.json").write_text(
        json.dumps(summary_rows, indent=2, ensure_ascii=False), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
