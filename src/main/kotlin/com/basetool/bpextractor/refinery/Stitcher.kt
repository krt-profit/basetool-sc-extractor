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
        if (a.quality != b.quality) return false
        val an = foldName(a.name)
        val bn = foldName(b.name)
        if (a.qty != b.qty) {
            // Loose mode (second-chance overlap detection): the SAME physical row read with a
            // digit disagreement across two captures (Auftrag 10: 105 vs 185) — name + quality
            // must then match exactly, and both cells must be actual values.
            return loose && a.qty != null && b.qty != null && a.quality != null && an == bn
        }
        if (an == bn) return true
        return a.quality != null && a.qty != null && withinOneEdit(an, bn)
    }

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

    /** One assembled fragment: rows + (parallel) source-image names + quoted-state per row. */
    private data class Fragment(
        val rows: MutableList<PanelRow>,
        val sources: MutableList<String>,
        val quoted: MutableList<Boolean>,
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
            if (bestK == 0) break
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
            if (prefersIncoming(a.rows[ai], a.quoted[ai], b.rows[i], b.quoted[i])) {
                a.rows[ai] = b.rows[i]
                a.sources[ai] = b.sources[i]
                a.quoted[ai] = b.quoted[i]
            }
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
        for (i in 0 until k) {
            val ai = a.rows.size - k + i
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
        }
        return Fragment(rows, sources, quoted)
    }
}
