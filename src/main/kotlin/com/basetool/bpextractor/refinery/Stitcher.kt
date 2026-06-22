package com.basetool.bpextractor.refinery

/** One screenshot's parsed panel, tagged with the image it came from. */
data class ImageRead(val imageName: String, val panel: PanelRead)

/** A stitched, deduplicated row: the surviving cell values + provenance. */
data class StitchedRow(
    val name: String,
    val quality: String?,
    val qty: String?,
    val yield_: String?,
    val refine: String,
    /** The image whose (preferred) read supplied the surviving cells. */
    val sourceImage: String,
    /**
     * Whether the surviving read saw the QUOTED panel state. Rows from an un-quoted capture show
     * `--` yields by definition, so the yield-based refine correction in [Validation] must not
     * fire for them — in a mixed capture set the order-level `quoted` is too coarse.
     */
    val quotedRead: Boolean,
    /**
     * True when this row was re-read across OVERLAPPING captures with a DISAGREEING numeric cell
     * (quality/qty/yield): the merge kept one read, but at least one other capture saw the same
     * physical row differently, so the value is consensus-unsafe. [Validation] lowers the row's
     * confidence so the review surfaces it — the cross-capture analogue of the cross-model
     * `contested` signal, and the only check that catches the single-digit flips (Auftrag 14: the
     * same TUNGSTEN row read 850 in one capture and 858 in the next) that no checksum can.
     */
    val contested: Boolean = false,
)

/** The stitched order: merged header fields + rows in reconstructed on-screen order. */
data class StitchResult(
    val method: String?,
    val quoted: Boolean,
    val inManifest: String?,
    val toRefine: String?,
    val totalCost: String?,
    val processingTime: String?,
    val rows: List<StitchedRow>,
    /** The bottom CTA label (`CONFIRM` / `GET QUOTE`), quoted reads preferred — validation input. */
    val cta: String? = null,
)

/**
 * Merges the per-image panel reads of ONE order into a single row list (master plan §9 Phase 3,
 * corrected stitch rules):
 *
 * - **Row identity is the full triple** (material name, quality, qty) — duplicate materials at
 *   different qualities are normal (Auftrag 1 has LINDINIUM at four qualities) and must never
 *   collapse.
 * - **Rows co-visible in the same screenshot never merge**, even when their triple is identical:
 *   the merge step only aligns ACROSS images (suffix/prefix overlap), it never folds within one.
 * - **A single QTY disagreement does not break the overlap**: when no exact suffix-prefix
 *   overlap exists, a loose pass re-aligns on (name, quality) alone — the same physical row
 *   mis-read in ONE capture must not export twice (Auftrag 10: 105 vs 185). The surviving QTY
 *   comes from the read that saw the row farther from its viewport edges (edge rows render
 *   half-cut/glowing and are the unreliable ones — same rationale as the partial-row drop).
 * - **The quoted variant wins**: when the same row appears once with a yield (quoted) and once as
 *   `--` (captured before GET QUOTE), the quoted cells survive.
 * - **On-screen order is reconstructed from scroll overlap** (consecutive captures overlap by
 *   ~one row; file timestamps are NOT capture order): greedy fragment assembly by the longest
 *   suffix-prefix identity overlap. Sequences that share no overlap (a scroll gap) are appended
 *   in input order — the header-total checksum then flags the possible hole downstream.
 * - **Partial rows are dropped before stitching**: a viewport-edge-cut row is an unreliable
 *   duplicate of the full row in the adjacent capture.
 *
 * Header fields merge as "first non-null across reads"; `quoted` is true when ANY read saw the
 * quoted state (the quoted capture is authoritative for cost/time too, so those prefer quoted
 * reads).
 */
object Stitcher {

    /**
     * Row identity for cross-image alignment. Quality and qty must match exactly; the name folds
     * case/whitespace and bracket style — the VLM transcribes the suffix inconsistently as
     * `(ORE)`/`[ORE]` across reads of the same panel, which must not break the overlap detection
     * (the exported name stays verbatim; only the comparison folds). On top, a SINGLE-character
     * name garble (`LINDINIMUM` vs `LINDINIUM`, a real run-to-run artefact) is tolerated when
     * BOTH numeric cells are present and equal — duplicate materials always differ in
     * quality/qty, so the numbers disambiguate; basetool fuzzy-matches names downstream anyway.
     */
    private fun sameRow(a: PanelRow, b: PanelRow, loose: Boolean = false): Boolean {
        val an = foldName(a.name)
        val bn = foldName(b.name)

        // Exact identity: same QUALITY and QTY. The name folds bracket/whitespace style, or
        // tolerates a single-character garble (LINDINIMUM vs LINDINIUM) when BOTH numeric cells
        // are present to disambiguate.
        if (a.quality == b.quality && a.qty == b.qty) {
            if (an == bn) return true
            return a.quality != null && a.qty != null && withinOneEdit(an, bn)
        }
        if (!loose) return false

        // Loose cross-image re-alignment — the SAME physical row mis-read in ONE capture must not
        // export twice. The name must fold-match exactly; then ONE of two anchors stands in for the
        // full triple:
        //  (a) QUALITY matches and only a single QTY digit disagrees (Auftrag 10: 105 vs 185); or
        //  (b) a POSITIVE YIELD matches but the QTY does NOT — "same yield, different qty" can only
        //      be ONE physical row mis-read across the overlap (Auftrag 14 TUNGSTEN: 958|950 in one
        //      capture, 858|858 in the next, yield 413 in both); a real sibling row sharing the
        //      yield would share the qty too (yield scales with qty). A `--` / 0 yield is ambiguous
        //      (every OFF row shows it) and is NOT an anchor; rows with equal qty+yield differing
        //      ONLY in quality stay UNMERGED (could be two real tiers of an equal amount) so the
        //      review dedupes them rather than silently dropping a row.
        if (an != bn) return false
        val qtyTolerance = a.quality == b.quality && a.quality != null && a.qty != null && b.qty != null
        val yieldAnchor = a.qty != b.qty && hasQuotedYield(a) && a.yield_ == b.yield_ &&
            (PanelValues.toQuantity(a.yield_) ?: 0L) > 0L
        return qtyTolerance || yieldAnchor
    }

    /**
     * Do two aligned reads of the SAME physical row disagree on a numeric cell? Drives the
     * [StitchedRow.contested] flag — a quality/qty/yield mismatch means one capture mis-read the
     * cell, so the merged value is consensus-unsafe and the review must look.
     */
    private fun cellsContested(a: PanelRow, b: PanelRow): Boolean =
        (a.quality != null && b.quality != null && a.quality != b.quality) ||
            (a.qty != null && b.qty != null && a.qty != b.qty) ||
            (hasQuotedYield(a) && hasQuotedYield(b) && a.yield_ != b.yield_)

    private fun foldName(name: String): String = name.trim().uppercase()
        .replace(Regex("\\s+"), " ").replace('[', '(').replace(']', ')')

    /** Levenshtein distance ≤ 1 (one substitution, insertion or deletion). */
    internal fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return when (long.length - short.length) {
            0 -> short.indices.count { short[it] != long[it] } == 1
            1 -> {
                var i = 0
                var j = 0
                var skipped = false
                while (i < short.length) {
                    when {
                        short[i] == long[j] -> { i++; j++ }
                        !skipped -> { skipped = true; j++ }
                        else -> return false
                    }
                }
                true
            }
            else -> false
        }
    }

    /** True when the row carries a quoted yield value (not the `--` marker, not unreadable). */
    private fun hasQuotedYield(row: PanelRow): Boolean = row.yield_ != null && row.yield_ != "--"

    /** The SC HUD font's round/loopy digit confusions — mirrors [Validation]'s CONFUSABLE_DIGITS. */
    private val CONFUSABLE_HUD_DIGITS = setOf('0', '6', '8', '9')

    /**
     * Whether two boundary rows are the SAME physical seam row re-read across the scroll overlap,
     * for the case overlap() cannot detect: a refine-OFF row (no positive yield to anchor on) whose
     * quality AND/OR qty were mis-read. Requires the folded names to match, BOTH reads to be OFF
     * (a positive yield is the yieldAnchor's job, and an ON row's qty feeds the checksum and must
     * not be guessed at), and each numeric cell to be equal or a single confusable-digit edit
     * apart — with at least one cell actually differing (an exact match is overlap()'s job).
     */
    private fun seamReconcilable(a: PanelRow, b: PanelRow): Boolean {
        if (foldName(a.name) != foldName(b.name)) return false
        if (hasQuotedYield(a) || hasQuotedYield(b)) return false
        if (!numericSeamMatch(a.quality, b.quality) || !numericSeamMatch(a.qty, b.qty)) return false
        return a.quality != b.quality || a.qty != b.qty
    }

    /** Two numeric cells align at a seam: both present and equal, or a single confusable-digit edit apart. */
    private fun numericSeamMatch(a: String?, b: String?): Boolean =
        a != null && b != null && (a == b || withinOneConfusableDigitEdit(a, b))

    /** Same length, exactly one position differs, and BOTH differing digits are HUD-confusable. */
    private fun withinOneConfusableDigitEdit(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = -1
        for (i in a.indices) {
            if (a[i] != b[i]) {
                if (diff >= 0) return false
                diff = i
            }
        }
        return diff >= 0 && a[diff] in CONFUSABLE_HUD_DIGITS && b[diff] in CONFUSABLE_HUD_DIGITS
    }

    /** One assembled fragment: rows + (parallel) source-image names + quoted-/contested-state per row. */
    private data class Fragment(
        val rows: MutableList<PanelRow>,
        val sources: MutableList<String>,
        val quoted: MutableList<Boolean>,
        val contested: MutableList<Boolean>,
    )

    fun stitch(reads: List<ImageRead>): StitchResult {
        require(reads.isNotEmpty()) { "stitch() needs at least one read" }

        // Header merge: quoted reads are authoritative for the quote-dependent fields.
        val quotedReads = reads.filter { it.panel.quoted }
        val anyQuoted = quotedReads.isNotEmpty()
        val headerOrder = quotedReads + reads.filterNot { it.panel.quoted }
        val method = headerOrder.firstNotNullOfOrNull { it.panel.method }
        val inManifest = headerOrder.firstNotNullOfOrNull { it.panel.inManifest }
        val toRefine = headerOrder.firstNotNullOfOrNull { it.panel.toRefine }
        val totalCost = headerOrder.firstNotNullOfOrNull { it.panel.totalCost }
        val processingTime = headerOrder.firstNotNullOfOrNull { it.panel.processingTime }
        val cta = headerOrder.firstNotNullOfOrNull { it.panel.cta }

        // Fragments = per-image row sequences, partial rows dropped.
        var fragments = reads.map { read ->
            val rows = read.panel.rows.filterNot { it.partial }
            Fragment(
                rows.toMutableList(),
                MutableList(rows.size) { read.imageName },
                MutableList(rows.size) { read.panel.quoted },
                MutableList(rows.size) { false },
            )
        }.filter { it.rows.isNotEmpty() }

        // Greedy assembly. Two merge moves, tried in this order each round:
        // 1. containment fold — a capture whose whole row sequence appears inside another
        //    (e.g. an identical re-capture of the same viewport) folds into it in place;
        // 2. the pair with the largest suffix-prefix overlap chains into one fragment.
        outer@ while (fragments.size > 1) {
            for (a in fragments.indices) {
                for (b in fragments.indices) {
                    if (a == b) continue
                    val at = containsAt(fragments[a], fragments[b])
                    if (at != null) {
                        fold(fragments[a], fragments[b], at)
                        fragments = fragments.filterIndexed { i, _ -> i != b }
                        continue@outer
                    }
                }
            }
            var bestA = -1
            var bestB = -1
            var bestK = 0
            var bestLoose = false
            // Exact overlap first; only when nothing chains, retry tolerating a single-cell QTY
            // disagreement (loose) — otherwise one mis-read digit in the overlap zone makes the
            // whole capture pair "share no overlap" and every overlap row exports TWICE.
            for (loose in listOf(false, true)) {
                for (a in fragments.indices) {
                    for (b in fragments.indices) {
                        if (a == b) continue
                        val k = overlap(fragments[a], fragments[b], loose)
                        if (k > bestK) {
                            bestA = a; bestB = b; bestK = k; bestLoose = loose
                        }
                    }
                }
                if (bestK > 0) break
            }
            if (bestK == 0) {
                // Seam reconciliation (last resort): consecutive scroll captures overlap by ~1
                // row by construction, but a refine-OFF seam row (no positive-yield anchor) whose
                // quality AND qty are both mis-read in ONE capture defeats overlap() entirely and
                // would export twice (Auftrag 15 TARANITE: 310/290 in one capture, 318/298 in the
                // next). Align the boundary pair (last row of A, first of B) on the folded name
                // when each numeric cell is equal or a single CONFUSABLE-digit edit apart; merge
                // with k=1 and let cellsContested mark it so the review looks. The OFF-row analogue
                // of the positive-yield seam anchor in sameRow() — gated to the no-overlap case so
                // it can never re-merge rows a real overlap already aligned.
                var reconciled = false
                seam@ for (a in fragments.indices) {
                    for (b in fragments.indices) {
                        if (a == b) continue
                        if (seamReconcilable(fragments[a].rows.last(), fragments[b].rows.first())) {
                            val seamMerged = merge(fragments[a], fragments[b], 1, loose = true)
                            fragments = fragments.filterIndexed { i, _ -> i != a && i != b } + seamMerged
                            reconciled = true
                            break@seam
                        }
                    }
                }
                if (!reconciled) break
                continue
            }
            val merged = merge(fragments[bestA], fragments[bestB], bestK, bestLoose)
            fragments = fragments.filterIndexed { i, _ -> i != bestA && i != bestB } + merged
        }

        // No-overlap leftovers: keep input order, concatenated.
        val rows = mutableListOf<StitchedRow>()
        for (fragment in fragments) {
            fragment.rows.forEachIndexed { i, row ->
                rows += StitchedRow(
                    name = row.name,
                    quality = row.quality,
                    qty = row.qty,
                    yield_ = row.yield_,
                    refine = row.refine,
                    sourceImage = fragment.sources[i],
                    quotedRead = fragment.quoted[i],
                    contested = fragment.contested[i],
                )
            }
        }
        return StitchResult(method, anyQuoted, inManifest, toRefine, totalCost, processingTime, rows, cta)
    }

    /**
     * Whether the [incoming] duplicate of an overlap row should replace the [existing] one:
     * a quoted READ beats an un-quoted READ even when its yield cell is the `--` marker — for a
     * refine-OFF row `--` IS the quoted information ([Validation.yieldRefineSignal] may only fire
     * on rows whose surviving read saw the quoted state). Among reads of the same quoted-ness the
     * numeric yield wins (a quoted capture can still show `--` rows pre-GET-QUOTE leftovers).
     */
    private fun prefersIncoming(
        existing: PanelRow,
        existingQuoted: Boolean,
        incoming: PanelRow,
        incomingQuoted: Boolean,
    ): Boolean = when {
        incomingQuoted != existingQuoted -> incomingQuoted
        else -> !hasQuotedYield(existing) && hasQuotedYield(incoming)
    }

    /** Index in [a] at which [b]'s whole row sequence appears, or null when not contained. */
    private fun containsAt(a: Fragment, b: Fragment): Int? {
        if (b.rows.size > a.rows.size) return null
        outer@ for (start in 0..(a.rows.size - b.rows.size)) {
            for (i in b.rows.indices) {
                if (!sameRow(a.rows[start + i], b.rows[i])) continue@outer
            }
            return start
        }
        return null
    }

    /** Fold the contained fragment [b] into [a] at [start], preferring quoted reads in place. */
    private fun fold(a: Fragment, b: Fragment, start: Int) {
        for (i in b.rows.indices) {
            val ai = start + i
            val contested = a.contested[ai] || b.contested[i] || cellsContested(a.rows[ai], b.rows[i])
            if (prefersIncoming(a.rows[ai], a.quoted[ai], b.rows[i], b.quoted[i])) {
                a.rows[ai] = b.rows[i]
                a.sources[ai] = b.sources[i]
                a.quoted[ai] = b.quoted[i]
            }
            a.contested[ai] = contested
        }
    }

    /** Longest k such that the last k rows of [a] match the first k of [b]. */
    private fun overlap(a: Fragment, b: Fragment, loose: Boolean = false): Int {
        val max = minOf(a.rows.size, b.rows.size)
        for (k in max downTo 1) {
            var match = true
            for (i in 0 until k) {
                if (!sameRow(a.rows[a.rows.size - k + i], b.rows[i], loose)) {
                    match = false
                    break
                }
            }
            if (match) return k
        }
        return 0
    }

    /** Rows from the middle of a capture's table render clean; edge rows sit half-cut/glowing. */
    private fun edgeDistance(index: Int, size: Int): Int = minOf(index, size - 1 - index)

    /** Concatenate [a] + [b] minus the overlap of size [k]; overlapping rows prefer quoted reads. */
    private fun merge(a: Fragment, b: Fragment, k: Int, loose: Boolean = false): Fragment {
        val rows = a.rows.toMutableList()
        val sources = a.sources.toMutableList()
        val quoted = a.quoted.toMutableList()
        val contested = a.contested.toMutableList()
        for (i in 0 until k) {
            val ai = a.rows.size - k + i
            contested[ai] = contested[ai] || b.contested[i] || cellsContested(rows[ai], b.rows[i])
            val replace = if (loose && rows[ai].qty != b.rows[i].qty && quoted[ai] == b.quoted[i]) {
                // QTY disagreement on the same physical row (loose overlap): trust the read
                // that saw the row farther from its viewport edges — the edge row of one
                // capture re-appears mid-table in the next, where the glyphs render clean.
                edgeDistance(i, b.rows.size) > edgeDistance(ai, a.rows.size)
            } else {
                prefersIncoming(rows[ai], quoted[ai], b.rows[i], b.quoted[i])
            }
            if (replace) {
                rows[ai] = b.rows[i]
                sources[ai] = b.sources[i]
                quoted[ai] = b.quoted[i]
            }
        }
        for (i in k until b.rows.size) {
            rows += b.rows[i]
            sources += b.sources[i]
            quoted += b.quoted[i]
            contested += b.contested[i]
        }
        return Fragment(rows, sources, quoted, contested)
    }
}
