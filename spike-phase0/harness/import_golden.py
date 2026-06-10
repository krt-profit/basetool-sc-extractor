"""One-shot: import the triple-blind transcription workflow result into golden JSONs."""

import json
import sys
from pathlib import Path

SPIKE = Path(__file__).resolve().parent.parent
GOLDEN = SPIKE / "work" / "golden"


def main() -> None:
    raw = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
    outer = json.loads(raw)
    result = outer.get("result", outer)
    if isinstance(result, str):
        result = json.loads(result)
    imgs = result["images"]
    print("images:", len(imgs))
    GOLDEN.mkdir(parents=True, exist_ok=True)
    for im in imgs:
        f = im["fields"]
        out = {
            "image": im["src"].split("/")[-1],
            "id": im["id"],
            "precropped": im["pre"],
            "location": f.get("location"),
            "method": f.get("method"),
            "quoted": f.get("quoted"),
            "inManifest": f.get("inManifest"),
            "toRefine": f.get("toRefine"),
            "totalCost": f.get("totalCost"),
            "processingTime": f.get("processingTime"),
            "cta": f.get("cta"),
            "rows": im["rows"],
            "adjudicated": im.get("adjudicated", False),
            "unresolved": {
                "fields": im.get("unresolvedFieldDisputes", []),
                "rows": im.get("unresolvedRowDisputes", []),
            },
        }
        (GOLDEN / f"{im['id']}.json").write_text(
            json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        flag = "OK" if not out["unresolved"]["fields"] and not out["unresolved"]["rows"] else "UNRESOLVED!"
        print(
            f"{im['id']}: rows={len(im['rows'])} quoted={f.get('quoted')} "
            f"adjudicated={im.get('adjudicated')} {flag}"
        )


if __name__ == "__main__":
    main()
