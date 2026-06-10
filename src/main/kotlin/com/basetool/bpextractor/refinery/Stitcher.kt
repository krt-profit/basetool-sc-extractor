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

    /** Identity triple for cross-image row alignment; name folds case/whitespace only. */
    private data class RowKey(val name: String, val quality: String?, val qty: String?)

    private fun key(row: PanelRow): RowKey = RowKey(
        name = row.name.trim().uppercase().replace(Regex("\\s+"), " "),
        quality = row.quality,
        qty = row.qty,
    )

    /** One assembled fragment: rows + (parallel) source-image names + quoted-state per row. */
    private data class Fragment(val rows: MutableList<PanelRow>, val sources: MutableList<String>)

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

        // Fragments = per-image row sequences, partial rows dropped.
        var fragments = reads.map { read ->
            val rows = read.panel.rows.filterNot { it.partial }
            Fragment(rows.toMutableList(), MutableList(rows.size) { read.imageName })
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
            for (a in fragments.indices) {
                for (b in fragments.indices) {
                    if (a == b) continue
                    val k = overlap(fragments[a], fragments[b])
                    if (k > bestK) {
                        bestA = a; bestB = b; bestK = k
                    }
                }
            }
            if (bestK == 0) break
            val merged = merge(fragments[bestA], fragments[bestB], bestK)
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
                )
            }
        }
        return StitchResult(method, anyQuoted, inManifest, toRefine, totalCost, processingTime, rows)
    }

    /** Index in [a] at which [b]'s whole key sequence appears, or null when not contained. */
    private fun containsAt(a: Fragment, b: Fragment): Int? {
        if (b.rows.size > a.rows.size) return null
        outer@ for (start in 0..(a.rows.size - b.rows.size)) {
            for (i in b.rows.indices) {
                if (key(a.rows[start + i]) != key(b.rows[i])) continue@outer
            }
            return start
        }
        return null
    }

    /** Fold the contained fragment [b] into [a] at [start], preferring quoted cells in place. */
    private fun fold(a: Fragment, b: Fragment, start: Int) {
        for (i in b.rows.indices) {
            val ai = start + i
            if (a.rows[ai].yield_ == null && b.rows[i].yield_ != null) {
                a.rows[ai] = b.rows[i]
                a.sources[ai] = b.sources[i]
            }
        }
    }

    /** Longest k such that the last k row keys of [a] equal the first k of [b]. */
    private fun overlap(a: Fragment, b: Fragment): Int {
        val max = minOf(a.rows.size, b.rows.size)
        for (k in max downTo 1) {
            var match = true
            for (i in 0 until k) {
                if (key(a.rows[a.rows.size - k + i]) != key(b.rows[i])) {
                    match = false
                    break
                }
            }
            if (match) return k
        }
        return 0
    }

    /** Concatenate [a] + [b] minus the overlap of size [k]; overlapping rows prefer quoted cells. */
    private fun merge(a: Fragment, b: Fragment, k: Int): Fragment {
        val rows = a.rows.toMutableList()
        val sources = a.sources.toMutableList()
        for (i in 0 until k) {
            val ai = a.rows.size - k + i
            val existing = rows[ai]
            val incoming = b.rows[i]
            // The quoted variant (yield present) wins; otherwise the existing read stays.
            if (existing.yield_ == null && incoming.yield_ != null) {
                rows[ai] = incoming
                sources[ai] = b.sources[i]
            }
        }
        for (i in k until b.rows.size) {
            rows += b.rows[i]
            sources += b.sources[i]
        }
        return Fragment(rows, sources)
    }
}
