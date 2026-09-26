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
     * `true` when overlapping captures read this row with a disagreeing numeric cell, so the kept value
     * is not consensus-safe; [Validation] lowers the row's confidence.
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
 * Merges the per-image panel reads of one order into a single row list.
 *
 * - Row identity is the triple (material name, quality, qty); rows co-visible in one screenshot never
 *   merge.
 * - Captures are ordered by the longest suffix-prefix row overlap; a loose pass on (name, quality)
 *   tolerates one QTY disagreement, keeping the read farther from its viewport edges. Sequences
 *   without overlap are appended in input order.
 * - The quoted variant of a row wins; partial viewport-edge rows are dropped before stitching.
 *
 * Header fields take the first non-null value; `quoted` is true when any read saw the quoted state.
 */
object Stitcher {

    /**
     * Row identity for cross-image alignment: quality and qty match exactly, and the name matches after
     * folding case, whitespace and bracket style. A single-character name difference is tolerated when
     * both numeric cells are present and equal.
     */
    private fun sameRow(a: PanelRow, b: PanelRow, loose: Boolean = false): Boolean {
        val an = foldName(a.name)
        val bn = foldName(b.name)

        if (a.quality == b.quality && a.qty == b.qty) {
            if (an == bn) return true
            return a.quality != null && a.qty != null && withinOneEdit(an, bn)
        }
        if (!loose) return false

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
     * Whether two boundary rows are the same refine-OFF seam row re-read across the scroll overlap with a
     * misread quality or qty: folded names match, both reads are OFF, and each numeric cell is equal or
     * one confusable digit apart, with at least one differing.
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

        val quotedReads = reads.filter { it.panel.quoted }
        val anyQuoted = quotedReads.isNotEmpty()
        val headerOrder = quotedReads + reads.filterNot { it.panel.quoted }
        val method = headerOrder.firstNotNullOfOrNull { it.panel.method }
        val inManifest = headerOrder.firstNotNullOfOrNull { it.panel.inManifest }
        val toRefine = headerOrder.firstNotNullOfOrNull { it.panel.toRefine }
        val totalCost = headerOrder.firstNotNullOfOrNull { it.panel.totalCost }
        val processingTime = headerOrder.firstNotNullOfOrNull { it.panel.processingTime }
        val cta = headerOrder.firstNotNullOfOrNull { it.panel.cta }

        var fragments = reads.map { read ->
            val rows = read.panel.rows.filterNot { it.partial }
            Fragment(
                rows.toMutableList(),
                MutableList(rows.size) { read.imageName },
                MutableList(rows.size) { read.panel.quoted },
                MutableList(rows.size) { false },
            )
        }.filter { it.rows.isNotEmpty() }

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
     * Whether the [incoming] duplicate of an overlap row replaces the [existing] one: a quoted read beats
     * an un-quoted one even with a `--` yield, and among equally quoted reads a numeric yield wins.
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
