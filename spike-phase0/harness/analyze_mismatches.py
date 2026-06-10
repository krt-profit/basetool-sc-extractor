"""Aggregate per-cell mismatches from a bake-off run (issue #433 analysis step).

Reads every work/bakeoff/<model>/<strategy>/<image>.json, prints each mismatch
(field, golden vs read) plus a per-field error histogram across the whole run,
so systematic misreads (e.g. a specific column) stand out from random noise.
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

BAKEOFF = Path(__file__).resolve().parent.parent / "work" / "bakeoff"


def main() -> None:
    field_errors: Counter[str] = Counter()
    for model_dir in sorted(p for p in BAKEOFF.iterdir() if p.is_dir()):
        for strat_dir in sorted(p for p in model_dir.iterdir() if p.is_dir()):
            for f in sorted(strat_dir.glob("*.json")):
                data = json.loads(f.read_text(encoding="utf-8"))
                score = data.get("score") or {}
                mismatches = score.get("mismatches") or []
                if not mismatches:
                    continue
                print(f"\n## {model_dir.name} / {strat_dir.name} / {f.stem}")
                for m in mismatches:
                    text = m if isinstance(m, str) else json.dumps(m)
                    field = text.split(":", 1)[0].strip().split("[")[0]
                    field_errors[f"{strat_dir.name}:{field}"] += 1
                    print(f"  {text}")
    print("\n## per-field error histogram (strategy:field -> count)")
    for key, n in field_errors.most_common():
        print(f"  {key}: {n}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
