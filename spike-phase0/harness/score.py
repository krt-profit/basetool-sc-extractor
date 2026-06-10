"""Phase-0 spike: exact-match-per-cell scoring against the golden set.

A "cell" is each order-level field (method, quoted, inManifest, toRefine, totalCost,
processingTime) plus the 5 columns of every golden row. Rows are aligned positionally
(both golden and read are strictly top-to-bottom); a missing/extra read row counts
every cell of the affected golden row as wrong (and extra rows add a penalty bucket).
"""

from __future__ import annotations

import re

ORDER_FIELDS = ["method", "quoted", "inManifest", "toRefine", "totalCost", "processingTime"]
ROW_FIELDS = ["name", "quality", "qty", "yield", "refine"]


def _num(v):
    """Normalize a numeric-ish transcription for comparison ('48928.00' == 48928)."""
    if v is None or v == "--":
        return v
    s = str(v).strip().replace(",", "")
    try:
        f = float(s)
        return int(f) if f == int(f) else f
    except ValueError:
        return s.upper()


def _name(v):
    if v is None:
        return None
    return re.sub(r"\s+", " ", str(v).strip().upper()).replace("[", "(").replace("]", ")")


def _empty(v) -> bool:
    """The terminal renders absent values as '--' (un-quoted cost/time/yield); golden
    encodes them as null. Both spellings — incl. '-- aUEC' — mean the same cell."""
    return v is None or re.fullmatch(r"-+(\s*AUEC)?", str(v).strip(), re.IGNORECASE) is not None


def _cell(field: str, v):
    if _empty(v):
        return None
    if field in {"quality", "qty", "yield", "inManifest", "toRefine", "totalCost"}:
        return _num(v)
    if field in {"name", "method", "processingTime"}:
        return _name(v)
    if field == "refine":
        if isinstance(v, bool):
            return "ON" if v else "OFF"
        return str(v).strip().upper()
    return v


def score_image(golden: dict, parsed: dict | None) -> dict:
    """Score one read result against one golden per-image transcript."""
    g_rows = [r for r in golden["rows"] if not r.get("partial")]
    total = len(ORDER_FIELDS) + len(g_rows) * len(ROW_FIELDS)
    if parsed is None:
        return {"cells": total, "correct": 0, "parseFailure": True, "mismatches": ["<parse failure>"]}
    correct = 0
    mismatches = []
    for f in ORDER_FIELDS:
        gv, pv = _cell(f, golden.get(f)), _cell(f, parsed.get(f))
        if gv == pv:
            correct += 1
        else:
            mismatches.append(f"{f}: golden={gv!r} read={pv!r}")
    p_rows = [r for r in (parsed.get("rows") or []) if not r.get("partial")]
    for i, g_row in enumerate(g_rows):
        p_row = p_rows[i] if i < len(p_rows) else None
        for f in ROW_FIELDS:
            gv = _cell(f, g_row.get(f))
            pv = _cell(f, p_row.get(f)) if p_row else None
            if gv == pv:
                correct += 1
            else:
                mismatches.append(f"row{i}.{f}: golden={gv!r} read={pv!r}")
    extra = max(0, len(p_rows) - len(g_rows))
    return {
        "cells": total,
        "correct": correct,
        "extraRows": extra,
        "parseFailure": False,
        "rowCountRead": len(p_rows),
        "rowCountGolden": len(g_rows),
        "mismatches": mismatches,
    }


def summarize(per_image: list[dict]) -> dict:
    cells = sum(s["cells"] for s in per_image)
    correct = sum(s["correct"] for s in per_image)
    return {
        "images": len(per_image),
        "cells": cells,
        "correct": correct,
        "cellAccuracy": round(correct / cells, 4) if cells else 0.0,
        "imagePerfect": sum(1 for s in per_image if s["correct"] == s["cells"]),
        "parseFailures": sum(1 for s in per_image if s.get("parseFailure")),
        "extraRows": sum(s.get("extraRows", 0) for s in per_image),
    }
