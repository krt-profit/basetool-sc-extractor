package com.basetool.bpextractor.net

import com.basetool.bpextractor.model.BlueprintItem
import com.basetool.bpextractor.model.ItemRef

/**
 * One product the sync would add.
 *
 * @param bt the Basetool key the name resolved to
 * @param name the product's name, as the Basetool writes it
 * @param item the envelope item it came from
 */
data class PendingAdd(val bt: String, val name: String, val item: BlueprintItem)

/**
 * What one sync did.
 *
 * @param added products newly added to „Meine Blueprints"
 * @param alreadyOwned products the member already had
 * @param removedElsewhere products the member removed in the Basetool or another client; the server
 *   refused to add them back, and only an explicit [BlueprintSync.addRemovedElsewhere] does
 * @param unmatched names the Basetool knows no product for
 * @param ambiguous names that fit several products
 * @param refused products refused for any other reason, with it
 */
data class SyncReport(
    val added: Int,
    val alreadyOwned: Int,
    val removedElsewhere: List<PendingAdd>,
    val unmatched: List<String>,
    val ambiguous: List<String>,
    val refused: List<Pair<String, String>> = emptyList(),
)

/**
 * The direct blueprint sync (REQ-XCH-015, opt-in): pull before push, and **add only** — the log proves
 * a blueprint was received, never that one is gone, so the extractor never removes anything. Names
 * resolve through the Basetool's own matching (`catalog/resolve`); only products the member does not
 * own yet are sent, by their Basetool key. A product the member removed elsewhere is not re-added
 * unless the member asks for it.
 *
 * @param client the exchange client
 * @param credentials the member's token and its key
 * @param acceptLanguage the UI locale to relay
 */
class BlueprintSync(
    private val client: ExchangeClient,
    private val credentials: ExchangeCredentials,
    private val acceptLanguage: String,
) {

    /**
     * Adds the envelope's products the member does not own yet.
     *
     * @param items the envelope's items, of the member's own account only
     * @return what happened
     * @throws ExchangeException on any refusal of a whole request
     */
    fun sync(items: List<BlueprintItem>): SyncReport {
        val owned = ownedKeys()
        val unmatched = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        val pending = linkedMapOf<String, PendingAdd>()
        var alreadyOwned = 0
        items.chunked(BATCH_MAX).forEach { chunk ->
            val results = client.resolveBlueprints(credentials, chunk.map { it.ref }, acceptLanguage).results
            chunk.forEachIndexed { index, item ->
                val result = results.firstOrNull { it.index == index }
                val bt = result?.ref?.bt
                when {
                    result?.status == ResolveResult.RESOLVED && bt != null ->
                        if (bt in owned || bt in pending) {
                            if (bt in owned) alreadyOwned++
                        } else {
                            pending[bt] = PendingAdd(bt, result.ref.name ?: displayName(item.ref), item)
                        }
                    result?.status == ResolveResult.AMBIGUOUS -> ambiguous += displayName(item.ref)
                    else -> unmatched += displayName(item.ref)
                }
            }
        }
        val pushed = push(pending.values.toList(), override = false)
        return pushed.copy(
            alreadyOwned = alreadyOwned + pushed.alreadyOwned,
            unmatched = unmatched + pushed.unmatched,
            ambiguous = ambiguous + pushed.ambiguous,
        )
    }

    /**
     * Adds products the member removed elsewhere after all — only after the member was asked.
     *
     * @param adds the products a [sync] reported as removed elsewhere
     * @return what happened
     * @throws ExchangeException on any refusal of a whole request
     */
    fun addRemovedElsewhere(adds: List<PendingAdd>): SyncReport = push(adds, override = true)

    /** The Basetool keys of every blueprint the member owns, read page by page. */
    private fun ownedKeys(): Set<String> {
        val keys = HashSet<String>()
        var cursor: String? = null
        do {
            val page = client.blueprintsPage(credentials, cursor, acceptLanguage)
            page.items.mapNotNullTo(keys) { it.ref.bt }
            cursor = page.nextCursor
        } while (page.hasMore && cursor != null)
        return keys
    }

    private fun push(adds: List<PendingAdd>, override: Boolean): SyncReport {
        var applied = 0
        var unchanged = 0
        val removedElsewhere = mutableListOf<PendingAdd>()
        val unmatched = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        val refused = mutableListOf<Pair<String, String>>()
        adds.chunked(CHANGE_SET_MAX).forEach { chunk ->
            val ops =
                chunk.mapIndexed { index, add ->
                    BlueprintAdd(
                        ref = ItemRef(bt = add.bt, name = add.name),
                        acquiredAt = add.item.acquiredAt,
                        provenance = add.item.provenance,
                        opId = index.toString(),
                        override = if (override) true else null,
                    )
                }
            val result = client.addBlueprints(credentials, BlueprintChangeSet(ops), acceptLanguage)
            applied += result.applied
            unchanged += result.unchanged
            for (op in result.results) {
                val add = chunk.getOrNull(op.index) ?: continue
                when {
                    op.result == "unchanged" -> {}
                    op.reason == OpResult.REMOVED_ELSEWHERE -> removedElsewhere += add
                    op.result == "unmatched" -> unmatched += add.name
                    op.result == "ambiguous" -> ambiguous += add.name
                    else -> refused += add.name to (op.reason ?: op.result)
                }
            }
        }
        return SyncReport(applied, unchanged, removedElsewhere, unmatched, ambiguous, refused)
    }

    private fun displayName(ref: ItemRef): String = ref.name ?: ref.locKey?.let { "@$it" } ?: ref.bt.orEmpty()
}
