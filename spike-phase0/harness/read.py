"""Phase-0 spike: the Read step — Ollama VLM calls with both A/B strategies.

Strategy A ("schema"): single-pass structured output via Ollama's `format` = JSON Schema.
Strategy B ("markdown"): freeform markdown-table read + deterministic reformat in code
(the "Format Tax" hypothesis: schema-forcing during the vision pass degrades accuracy).

Zero non-stdlib deps (urllib); numeric cells travel as strings and are parsed in code
(issue #433 deliverable 2). temperature 0, generous num_predict, retry on truncation.
"""

from __future__ import annotations

import base64
import json
import re
import time
import urllib.request
from pathlib import Path

OLLAMA = "http://localhost:11434"
PROMPT = (Path(__file__).parent / "prompts" / "setup_panel_v1.txt").read_text(encoding="utf-8")

# All numerics as STRINGS: the model must transcribe, not interpret.
PANEL_SCHEMA = {
    "type": "object",
    "required": ["method", "quoted", "inManifest", "toRefine", "totalCost", "processingTime", "rows"],
    "properties": {
        "method": {"type": ["string", "null"]},
        "quoted": {"type": "boolean"},
        "inManifest": {"type": ["string", "null"]},
        "toRefine": {"type": ["string", "null"]},
        "totalCost": {"type": ["string", "null"]},
        "processingTime": {"type": ["string", "null"]},
        "cta": {"type": ["string", "null"]},
        "rows": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["name", "quality", "qty", "yield", "refine"],
                "properties": {
                    "name": {"type": "string"},
                    "quality": {"type": ["string", "null"]},
                    "qty": {"type": ["string", "null"]},
                    "yield": {"type": ["string", "null"]},
                    "refine": {"type": "string", "enum": ["ON", "OFF"]},
                    "partial": {"type": "boolean"},
                },
            },
        },
    },
}

SCHEMA_INSTRUCTION = (
    "\nReturn the panel content as JSON matching the provided schema. "
    "All numeric cells are STRINGS transcribed digit for digit ('--' stays '--' in yield; use null for absent values)."
)

MARKDOWN_INSTRUCTION = """
Return the panel content in EXACTLY this layout (plain text, no code fences):

METHOD: <method or ?>
QUOTED: <YES|NO>
IN_MANIFEST: <number or ?>
TO_REFINE: <number or ?>
TOTAL_COST: <number or ?>
PROCESSING_TIME: <verbatim or ?>
CTA: <button label or ?>

| MATERIAL | QUALITY | QTY | YIELD | REFINE |
|---|---|---|---|---|
| <name> | <number> | <number> | <number or --> | <ON|OFF> |
(one line per visible row, top to bottom; append " PARTIAL" to the name of edge-cut rows)
"""


def _chat(model: str, prompt: str, image_path: str | Path, fmt=None, num_predict: int = 4096) -> dict:
    """One /api/chat call; returns {text, elapsedSec, evalCount, doneReason}."""
    img_b64 = base64.b64encode(Path(image_path).read_bytes()).decode()
    body = {
        "model": model,
        "messages": [{"role": "user", "content": prompt, "images": [img_b64]}],
        "stream": False,
        "options": {"temperature": 0, "num_predict": num_predict},
        "keep_alive": "10m",
    }
    if fmt is not None:
        body["format"] = fmt
    req = urllib.request.Request(
        f"{OLLAMA}/api/chat",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    t0 = time.monotonic()
    with urllib.request.urlopen(req, timeout=1800) as resp:
        data = json.loads(resp.read())
    return {
        "text": data["message"]["content"],
        "elapsedSec": round(time.monotonic() - t0, 2),
        "evalCount": data.get("eval_count"),
        "doneReason": data.get("done_reason"),
    }


def read_schema(model: str, image_path: str | Path) -> dict:
    """Strategy A: schema-forced single pass (+1 retry with larger budget on truncation)."""
    r = _chat(model, PROMPT + SCHEMA_INSTRUCTION, image_path, fmt=PANEL_SCHEMA)
    parsed, err = _try_json(r["text"])
    if parsed is None and r["doneReason"] == "length":
        r = _chat(model, PROMPT + SCHEMA_INSTRUCTION, image_path, fmt=PANEL_SCHEMA, num_predict=8192)
        parsed, err = _try_json(r["text"])
    return {"strategy": "schema", "raw": r, "parsed": parsed, "parseError": err}


def read_markdown(model: str, image_path: str | Path) -> dict:
    """Strategy B: freeform layout read, deterministically reformatted in code."""
    r = _chat(model, PROMPT + MARKDOWN_INSTRUCTION, image_path)
    if r["doneReason"] == "length":
        r = _chat(model, PROMPT + MARKDOWN_INSTRUCTION, image_path, num_predict=8192)
    parsed, err = _parse_markdown(r["text"])
    return {"strategy": "markdown", "raw": r, "parsed": parsed, "parseError": err}


def _try_json(text: str):
    try:
        return json.loads(text), None
    except json.JSONDecodeError as e:
        return None, str(e)


def _clean_num(s: str | None):
    if s is None:
        return None
    s = s.strip().rstrip(".")
    if s in {"?", "", "--", "null"}:
        return None if s != "--" else "--"
    return s.replace(",", "").replace(".00", "") or None


def _parse_markdown(text: str):
    """Deterministic reformat of the strategy-B layout into the schema shape."""
    try:
        fields: dict = {"rows": []}
        kv = dict(re.findall(r"^([A-Z_]+):\s*(.*)$", text, re.MULTILINE))
        fields["method"] = kv.get("METHOD", "?").strip() or None
        if fields["method"] == "?":
            fields["method"] = None
        fields["quoted"] = kv.get("QUOTED", "").strip().upper() == "YES"
        fields["inManifest"] = _clean_num(kv.get("IN_MANIFEST"))
        fields["toRefine"] = _clean_num(kv.get("TO_REFINE"))
        fields["totalCost"] = _clean_num(kv.get("TOTAL_COST"))
        pt = kv.get("PROCESSING_TIME", "?").strip()
        fields["processingTime"] = None if pt in {"?", ""} else pt
        cta = kv.get("CTA", "?").strip()
        fields["cta"] = None if cta in {"?", ""} else cta
        for line in text.splitlines():
            line = line.strip()
            if not line.startswith("|") or line.replace("|", "").strip().strip("-: ") == "":
                continue
            cells = [c.strip() for c in line.strip("|").split("|")]
            if len(cells) != 5 or cells[0].upper() == "MATERIAL":
                continue
            name = cells[0]
            partial = name.endswith(" PARTIAL")
            if partial:
                name = name[: -len(" PARTIAL")].strip()
            fields["rows"].append(
                {
                    "name": name,
                    "quality": _clean_num(cells[1]),
                    "qty": _clean_num(cells[2]),
                    "yield": _clean_num(cells[3]),
                    "refine": cells[4].upper(),
                    "partial": partial,
                }
            )
        return fields, None
    except Exception as e:  # noqa: BLE001 — spike code, every failure is a data point
        return None, f"{type(e).__name__}: {e}"


def read_location(model: str, image_path: str | Path) -> dict:
    """Read the terminal-header location strip (second read region)."""
    prompt = (
        "This is the header bar of a Star Citizen refinement terminal. "
        "It shows the station/outpost name on the left (e.g. LEVSKI). "
        "Reply with ONLY that name, verbatim and uppercase. Ignore everything else. "
        "If no name is visible, reply NONE."
    )
    r = _chat(model, prompt, image_path, num_predict=64)
    name = r["text"].strip().upper().strip(".")
    return {"raw": r, "location": None if name in {"NONE", ""} else name}
