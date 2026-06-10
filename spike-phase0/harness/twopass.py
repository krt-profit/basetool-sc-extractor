"""Phase-0 spike: validate the two-pass derived-confidence policy (issue #433, del. 6).

Pass 1 is the recorded bake-off read (pad=0, scale=1.0). Pass 2 re-reads every image
with a perturbed crop (default pad=24, scale_tweak=0.9) so a systematic misread at
temp 0 gets a chance to flip. A cell where the two passes disagree is LOW-confidence
(needs user review); an agreeing cell is HIGH-confidence.

The validation metric: of the cells the recorded pass got WRONG vs golden, how many
does disagreement catch (recall), and how many correct cells get flagged (false-flag
rate)? Results land in work/bakeoff/twopass_eval.json.

Usage: python twopass.py [model] [strategy] [pad] [scale]
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import normalize
import read as reader
import score as scorer
from run_bakeoff import IMAGES

WORK = Path(__file__).resolve().parent.parent / "work"


def _cells(parsed: dict | None, row_count: int) -> dict[str, object]:
    """Flatten a parsed panel into normalized comparable cells (same keys as score)."""
    cells: dict[str, object] = {}
    parsed = parsed or {}
    for f in scorer.ORDER_FIELDS:
        cells[f] = scorer._cell(f, parsed.get(f))
    rows = parsed.get("rows") or []
    for i in range(row_count):
        row = rows[i] if i < len(rows) else {}
        for f in scorer.ROW_FIELDS:
            cells[f"row{i}.{f}"] = scorer._cell(f, row.get(f))
    return cells


def main() -> None:
    model = sys.argv[1] if len(sys.argv) > 1 else "qwen3-vl:8b-instruct"
    strategy = sys.argv[2] if len(sys.argv) > 2 else "markdown"
    pad = int(sys.argv[3]) if len(sys.argv) > 3 else 24
    scale = float(sys.argv[4]) if len(sys.argv) > 4 else 0.9
    fn = reader.read_schema if strategy == "schema" else reader.read_markdown
    safe = model.replace(":", "_").replace("/", "_")
    norm2 = WORK / "normalized_pass2"

    caught = missed = false_flags = agree_correct = 0
    per_image = []
    for img_id, src in IMAGES.items():
        rec1 = json.loads(
            (WORK / "bakeoff" / safe / strategy / f"{img_id}.json").read_text(encoding="utf-8")
        )
        golden = json.loads((WORK / "golden" / f"{img_id}.json").read_text(encoding="utf-8"))
        g_rows = [r for r in golden["rows"] if not r.get("partial")]
        prep2 = normalize.prepare(src, norm2, pad=pad, scale_tweak=scale)
        t0 = time.time()
        result2 = fn(model, prep2["readImage"])
        elapsed = round(time.time() - t0, 1)
        c1 = _cells(rec1["result"]["parsed"], len(g_rows))
        c2 = _cells(result2["parsed"], len(g_rows))
        cg = _cells(golden, len(g_rows))
        flags = []
        for key, gv in cg.items():
            v1, v2 = c1.get(key), c2.get(key)
            disagree = v1 != v2
            wrong1 = v1 != gv
            if disagree and wrong1:
                caught += 1
            elif not disagree and wrong1:
                missed += 1
            elif disagree and not wrong1:
                false_flags += 1
            else:
                agree_correct += 1
            if disagree or wrong1:
                flags.append(
                    f"{key}: pass1={v1!r} pass2={v2!r} golden={gv!r}"
                    f" [{'caught' if disagree and wrong1 else 'MISSED' if wrong1 else 'false-flag'}]"
                )
        per_image.append({"image": img_id, "elapsedSec": elapsed, "flags": flags})
        for fl in flags:
            print(f"{img_id} {fl}", flush=True)
    summary = {
        "model": model,
        "strategy": strategy,
        "pass2": {"pad": pad, "scaleTweak": scale},
        "caught": caught,
        "missedWrongCells": missed,
        "falseFlags": false_flags,
        "agreeingCorrect": agree_correct,
    }
    print(json.dumps(summary, indent=2))
    (WORK / "bakeoff" / "twopass_eval.json").write_text(
        json.dumps({"summary": summary, "perImage": per_image}, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
