# Phase 0 spike report — refinery screenshot extraction (#433)

> **Doc type:** Spike report — frozen once Phase 0 closes; Phase 3 (#436) consumes its
> artifacts. **Status: complete** (one owner action open: low-resolution captures, §10).
> Epic: [krt-iri/basetool#439](https://github.com/krt-iri/basetool/issues/439) ·
> Plan: `basetool/docs/REFINERY_SCREENSHOT_IMPORT_PLAN.md` §Phase 0.
> Date: 2026-06-10 · Hardware: RTX 5090 32 GB VRAM, Ryzen 9 9950X3D, 95.6 GB RAM,
> Ollama 0.30.7, Python 3.13 + Pillow 12.2.

All spike code lives in `spike-phase0/harness/` (branch `spike/refinery-phase0`);
private inputs/outputs stay under the gitignored `spike-phase0/work/`.

## 1. Golden test set (deliverable 4)

12 images from the owner's example set, transcribed triple-blind (three independent
readers, zero disputes), stored as `work/golden/<id>.json`:

| Group | Images | Covers |
|---|---|---|
| Auftrag 1 | 4 × 4K frames | quoted order, duplicate materials, **scrolled viewport** (same header, different rows) |
| Auftrag 2 | 4 × 4K frames | **un-quoted GET-QUOTE state** (3 frames), the quote transition (1 frame), **HUD-marker overlap**, second PROCESSING panel beside the SETUP panel |
| Auftrag 3/4/6 | 3 pre-cropped panels | pre-cropped input path, `(ORE)` suffixes, REFINE-OFF rows, `IN MANIFEST ≠ TO REFINE` (a4) |
| Auftrag 5 | 1 × 4K frame | game-UI-truncated name (`CONSTRUCTION S`), large quantities (55 100) |

A "cell" for scoring = 6 order-level fields + 5 columns × every non-partial golden row;
exact match after normalization (`harness/score.py`: numeric folding `48928.00 == 48928`,
case/whitespace/bracket folding for names, `null == "--"` for absent values — the
terminal renders absent cost/time/yield as `--`).

**Coverage gaps (owner action, plan §Phase 0 Inputs):** native 1080p / 1440p / ultrawide
captures still need to be collected; the current set is 4K + pre-cropped only.

## 2. Model bake-off (deliverable 1)

All 7 models × 2 strategies × 12 images (scorer of 2026-06-10, after the `null == "--"`
fix); raw outputs under `work/bakeoff/<model>/<strategy>/`:

| Model | schema | markdown | sec/image (GPU) | Verdict |
|---|---|---|---|---|
| `qwen3-vl:8b-instruct` (q4_K_M) | 0.9821 | **0.9872** | 4.2–4.4 | **winner / default pin** |
| `qwen3-vl:8b-instruct-q8_0` | 0.9796 | 0.9872 | 4.3–7.3 | no gain over q4; not needed |
| `qwen3-vl:4b-instruct` | 0.9209 | 0.9872 | 3.1–4.3 | **low-VRAM/CPU fallback** |
| `gemma4:12b` | 0.9235 | 0.8852 | 22–29 | behind, slow |
| `glm-ocr` (0.9B) | 0.8929 | 0.0714 | 3.8–6.0 | viable only schema-forced; beaten by 4b |
| `qwen3.5:9b` | 0.5357 | 0.4872 | ~43 | unusable (5 parse failures) |
| `gemma4:e4b-it-qat` | 0.2755 | 0.3699 | 7.5–10.4 | unusable |

Decisive tie-breaker among the three 0.9872 results: **which errors survive the
production mitigations (§6)**. The 8b variants' errors are all absorbed (HUD overlay →
numeric validation; name garble/trailing dot → fuzzy matching; method → enum
nearest-match; `? AUEC` → numeric validation). The 4b's two REFINE-toggle misreads
(OFF→ON, a4) are the **only error class with no validation net** — they also corrupt the
`SUM_MISMATCH` checksum. Hence: **default `qwen3-vl:8b-instruct` q4_K_M; fallback
`qwen3-vl:4b-instruct`** (documented weakness: REFINE toggles on small/distant panels).

The documented qwen-vl table-repetition-loop bug never appeared in either quantization
(0 parse failures, 0 extra rows across 48 runs) — q8_0 is not needed as a mitigation.
`glm-ocr`'s markdown failure is expected: the OCR specialist does not follow the layout
instruction; with schema-forcing it is decent but strictly dominated by the 4b. Identical
accuracy across the re-timed q4 runs confirms temp-0 determinism end-to-end.

### 2.1 Error taxonomy (primary model, every remaining mismatch)

| Class | Instances | Production mitigation |
|---|---|---|
| HUD overlay read into a cell (`2.1KM` as yield) | 1 (both strategies) | numeric plausibility validation → review flag (§6) |
| Name spacing/garble (`SA VRIL IUM`, `SAVRILIMUM`) | 2 | absorbed by backend fuzzy matching (Phase 1, threshold 0.84) |
| Method "autocorrect" (`DINYX SOLVATION`) | 1 (both strategies) | nearest-match against the closed refining-method enum |
| Truncation artifact (`CONSTRUCTION S.` trailing dot) | 1 | fuzzy matching absorbs; or strip trailing punctuation |
| REFINE toggle misread (OFF → ON) | 2, **schema strategy only** | strategy choice (markdown); review UI shows toggles |

The schema strategy's two extra semantic errors (REFINE toggle) are consistent with the
"Format Tax" hypothesis (plan deliverable 2): schema-forcing during the vision pass
degrades visual judgment. Markdown + deterministic reformat wins on both accuracy and
error severity.

## 3. Locate → Normalize → Read (deliverable 3)

`harness/normalize.py`. **Locate** is classical CV on a 1/4-scale frame using two color
anchors — pure luminance profiling fails (the whole terminal interior is uniformly dark,
measured mean 35–64):

1. the maroon SETUP tab strip (~RGB(72,49,45); gap-tolerant runs, width-similarity
   clustering, strip height ≤ 12 rows at 1/4 scale), and
2. a bright-orange CTA/progress element below it (absolute run threshold — small/distant
   panels have small buttons).

The maroon strip covers only the panel's **left** part (the "WORK ORDER n" zone right of
it is dark gray, measured); the panel's right edge = max(strip end, CTA end) + margin.
Do **not** scan the whole body for orange — with two panels side by side that bleeds into
the neighbour's progress bar (verified regression during the spike).

**Multi-candidate + owner rule (2026-06-10):** a frame can hold several work-order panels
side by side (Auftrag 2: SETUP next to a running PROCESSING panel). The **newest order is
always the leftmost panel, and the leftmost is the extraction target** — candidates are
returned left→right and the pipeline takes `boxes[0]`. The VLM read's `panelType` is a
*validation* only (warn if not SETUP), never a selection mechanism.

Pre-cropped inputs (width < 1000 px and portrait) skip Locate. Fallback when no candidate
is found: the verified 4K geometry `(950, 350, 920, 1500)` scaled by frame size.

**Normalize** always runs client-side (Ollama silently downscales > ~3.2 MP): crop from
the native frame, resize to long edge 1536 px (pre-cropped capped at 1200 — upscaling a
~500 px panel beyond ~2.4× only blurs), dimensions snapped to multiples of 32.

**Location read** (terminal header strip, separate region `(250, 200, 900, 220)` @4K,
2× upscale): **9/9 exact matches** (`LEVSKI`), ~2.5 s/image, 3 output tokens.

## 4. Prompt + read strategy (deliverable 2)

Prompt v1 is externalized at `harness/prompts/setup_panel_v1.txt` (temperature 0,
`num_predict` 4096 with one retry at 8192 on `done_reason=length`, numeric cells read as
strings, parsed in code). One amendment shipped during the spike: the positive `quoted`
rule ("quoted is TRUE when the YIELD column shows numbers and the bottom button reads
CONFIRM; FALSE only when YIELD shows `--`") after a smoke-test miss.

**FROZEN: strategy (b) — freeform markdown read + deterministic reformat**
(`harness/read.py: read_markdown` + `_parse_markdown`). It beats schema-forcing on every
qwen-vl variant (0.9872 vs 0.9821/0.9796/0.9209) and avoids the schema strategy's
semantic-class errors (REFINE toggle) — the "Format Tax" confirmed on our domain. The
deterministic markdown→JSON parser is ~40 lines and fully unit-testable. Prompt v1 +
`MARKDOWN_INSTRUCTION` are the frozen artifacts Phase 3 ships as resources.

Note: the prompt asks the model to ignore overlapping AR markers, but the `2.1KM` case
(§2.1) shows prompt-hardening alone does not stop overlay bleed-through — the
**validation rule** (§6) is the reliable defence.

## 5. Hardware tiers (deliverable 5)

Measured on RTX 5090 32 GB / Ryzen 9 9950X3D (16C/32T); loaded sizes from the
vendor-neutral `ollama ps` probe (recorded per model under
`work/bakeoff/*_ollama_ps.txt`), **at Ollama's default 32k context**:

| Model | Loaded (GPU) | GPU sec/image | CPU-only sec/image (100% CPU probe) |
|---|---|---|---|
| `qwen3-vl:8b-instruct` q4_K_M | 10 GB | 4.2–4.4 | ~53 |
| `qwen3-vl:8b-instruct-q8_0` | 13 GB | 4.3–7.3 | not measured (not pinned) |
| `qwen3-vl:4b-instruct` | 7.9 GB | 3.1–4.3 | ~27 |
| `glm-ocr` | 4.0 GB | 3.8–6.0 | ~21 |

- **Tier table (preflight UI, `DESIGN_SC_EXTRACTOR.md` §5.1):** recommended = ≥12 GB
  VRAM → default 8b (10 GB loaded); minimum = ≥8 GB VRAM → 4b fallback (7.9 GB);
  below that → CPU mode with a "~1 minute per screenshot" warning (53 s on a top-tier
  16-core; weaker CPUs proportionally slower — surface the measured first-image time).
- **Phase-3 knob:** the loaded sizes include the default 32k context; our prompt + one
  image need a fraction of that. Shrinking `num_ctx` (e.g. 8k) cuts the KV-cache share
  and gives 12 GB cards real headroom for the 8b — measure once in Phase 3 and pin it.
- CPU mode is fully functional (accuracy identical — same model, same weights; verified
  36/36 on the probe image for both qwen variants).
- The auto-select policy stands: probe VRAM → pick 8b / 4b / CPU + warn; "close Star
  Citizen before extracting" stays (the game holds VRAM).

## 6. Confidence-derivation policy (deliverable 6) — **two-pass rejected**

The planned policy (exact-match agreement across two reads with varied crop) was
validated against the golden set (`harness/twopass.py`) and **fails on the data**:

| Pass-2 perturbation | Real errors caught | False flags (new pass-2 errors) |
|---|---|---|
| pad 24, scale 0.9 | **0 / 5** | 2 (digit flips `6811→6011`, `404→484`) |
| pad 40, scale 1.0 | **0 / 5** | 2 (digit flips `404→494`, `1802→1902`) |

All real errors are *systematic* — the model reads the same wrong value at every crop
(temp 0), so crop perturbation can never expose them; meanwhile the perturbed pass
*introduces* ~2 digit errors per 12 images and doubles latency.

**Frozen policy instead — deterministic validation, no second pass:**

1. **Numeric plausibility:** QTY / YIELD / header totals / cost must parse as a number or
   `--`; anything else (e.g. `2.1KM` HUD bleed-through) → row review flag. Catches error
   class 1.
2. **Closed-enum nearest-match** for the refining method (and `panelType`). Catches
   class 3.
3. **Backend fuzzy-match score** (Phase 1, threshold 0.84) is the per-row name
   confidence; classes 2/4 sit above the threshold and surface as match-with-score.
4. **Header-total checksum** per §7 (one-sided — see below).
5. Verbalized VLM self-confidence stays **forbidden** (uncalibrated for OCR, plan §9.5).

The §5.4 UI thresholds are unchanged; they are fed from (1)–(4).

## 7. Header-total semantics (deliverable 7) — verified

From the golden set (fully visible panels a3 / a5 / a6, plus a4's visible rows):

- **`TO REFINE` = Σ QTY of REFINE-ON rows**, within ±1 per panel (displayed integers are
  rounded from fractional internals): a3 1160≈1161, a5 55100=55100, a6 2826≈2827,
  a4 1947≈1948.
- **`IN MANIFEST` ≥ `TO REFINE`**; on a3/a5/a6 the OFF rows contribute 0 and
  `IM == TR`. On a4 (`2564` vs `1948`) the two small OFF rows (37+578=615≈616=IM−TR)
  are counted while the large OFF row is not — hypothesis: toggle state changed after
  quoting. IM's exact rule is **not** reliably reconstructible from one frame; do not
  validate against it.
- **The materials list is a scrolling ~6-row viewport** (Auftrag 1/2: identical header
  totals, different visible rows across frames). Σ of visible rows can therefore never
  equal the header on long orders — a hard equality check is impossible by design.

**Frozen `SUM_MISMATCH` rule (one-sided):** flag WARNING ("a scrolled screenshot may be
missing rows") when `Σ QTY(visible REFINE-ON rows) > toRefine + rowCount` (the +rowCount
absorbs ±1 rounding per row), or when any single row QTY exceeds `toRefine + 1`.
Equality is **not** required; a shortfall means scrolled-out rows, which the §5.4 review
UI surfaces via the row count.

## 8. Domain rules confirmed by the owner during the spike

- **Leftmost panel = newest order = extraction target** (2026-06-10, see §3).
- Real screenshots are **private** (player handle + account balance) — never committed;
  fixtures are synthesized/redacted.

## 9. Blueprint-parser regression (Definition of Done)

`.\gradlew.bat test --rerun` green on `spike/refinery-phase0` (2026-06-10) — the spike
touches no production parser code. The manual 179/`greluc` characterization check against
the private `game-log/` remains a manual step before merging Phase 3.

## 10. Open items

1. **Owner:** collect native 1080p / 1440p / ultrawide captures (§1) — tracked as a
   Phase 3 risk; the Locate stage is resolution-agnostic by construction but unverified
   below 4K except via downscaling.
2. Phase 3: measure and pin a reduced `num_ctx` (§5) and re-verify the 12 GB tier.

Everything else (model pins, strategy/prompt freeze, hardware tiers, confidence policy,
header semantics, golden set, parser regression) is **done** — see the sections above.
