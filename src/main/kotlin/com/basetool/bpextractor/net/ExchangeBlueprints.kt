package com.basetool.bpextractor.net

import com.basetool.bpextractor.model.ItemRef
import com.basetool.bpextractor.model.Provenance
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * One blueprint the member owns in the Basetool (`blueprint.schema.json`).
 *
 * @param key the Basetool's opaque key
 * @param ref the product, with its `bt` key
 * @param isDefault whether every member has it by default
 */
@Serializable
data class OwnedBlueprint(val key: String = "", val ref: ItemRef = ItemRef(), val isDefault: Boolean = false)

/**
 * A snapshot or feed page of the member's blueprints (`page.schema.json#/$defs/blueprintPage`).
 *
 * @param items the blueprints on this page
 * @param nextCursor the cursor of the next page, or of the feed after the last one
 * @param hasMore whether another page follows
 */
@Serializable
data class BlueprintPage(
    val items: List<OwnedBlueprint> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

/**
 * A `catalog/resolve` request (`resolve-request.schema.json`).
 *
 * @param kind the catalogue, here always `BLUEPRINT`
 * @param refs at most 500 references
 */
@Serializable
data class ResolveRequest(val kind: String, val refs: List<ItemRef>)

/**
 * One reference's outcome.
 *
 * @param index its position in the request
 * @param status `resolved`, `ambiguous` or `unmatched`
 * @param ref the entry it resolved to, with `bt` and `name`
 * @param candidates the entries an ambiguous reference could mean
 */
@Serializable
data class ResolveResult(
    val index: Int = 0,
    val status: String = "",
    val ref: ItemRef? = null,
    val candidates: List<ItemRef> = emptyList(),
) {
    companion object {
        /** The reference names exactly one entry. */
        const val RESOLVED = "resolved"

        /** The reference names several entries. */
        const val AMBIGUOUS = "ambiguous"
    }
}

/**
 * A `catalog/resolve` answer (`resolve-response.schema.json`).
 *
 * @param results one per reference, in request order
 */
@Serializable
data class ResolveResponse(val results: List<ResolveResult> = emptyList())

/**
 * One `add` of a blueprint change set (`change-set.schema.json#/$defs/blueprintOp`).
 *
 * @param ref the product, by its `bt` key
 * @param acquiredAt when it was first received
 * @param provenance where it came from
 * @param opId echoed in the result
 * @param override re-add a product removed elsewhere; only after asking the member
 * @param op always `add`: the extractor never removes
 */
@Serializable
data class BlueprintAdd(
    val ref: ItemRef,
    val acquiredAt: String? = null,
    val provenance: Provenance? = null,
    val opId: String? = null,
    val override: Boolean? = null,
    val op: String = "add",
)

/**
 * A blueprint change set of at most 500 ops.
 *
 * @param ops the adds
 */
@Serializable
data class BlueprintChangeSet(val ops: List<BlueprintAdd>)

/**
 * The detail of one op that was not applied.
 *
 * @param index its position in the change set
 * @param opId its `opId`
 * @param result `unchanged`, `unmatched`, `ambiguous` or `rejected`
 * @param reason the reason code of a rejection, e.g. `REMOVED_ELSEWHERE`
 */
@Serializable
data class OpResult(val index: Int = 0, val opId: String? = null, val result: String = "", val reason: String? = null) {
    companion object {
        /** An add of a product the member removed elsewhere, while the tombstone lives. */
        const val REMOVED_ELSEWHERE = "REMOVED_ELSEWHERE"
    }
}

/**
 * A change set's outcome (`change-result.schema.json`).
 *
 * @param applied how many ops changed something
 * @param unchanged how many were already true
 * @param notApplied how many were not applied
 * @param results the detail of the ops not applied
 */
@Serializable
data class ChangeResult(
    val applied: Int = 0,
    val unchanged: Int = 0,
    val notApplied: Int = 0,
    val results: List<OpResult> = emptyList(),
)

/**
 * The account check's answer (`account-check-response.schema.json`).
 *
 * @param result `match`, `mismatch` or `unknown`
 */
@Serializable
data class AccountCheckResult(val result: String = "") {
    companion object {
        /** The handle is the one on the member's profile. */
        const val MATCH = "match"

        /** The handle belongs to another member's profile, or the member's profile names another. */
        const val MISMATCH = "mismatch"

        /** The member's profile names no handle. */
        const val UNKNOWN = "unknown"
    }
}

/** The account check's request body. */
@Serializable
private data class AccountCheckRequest(val handle: String)

/** The shape the account check accepts, so a handle the server would refuse is never sent. */
private val HANDLE = Regex("^[A-Za-z0-9_-]{3,60}$")

/**
 * Whether [handle] is one the account check accepts (`account-check-request.schema.json`).
 *
 * @param handle the handle from the log
 * @return `true` when it may be sent
 */
fun isCheckableHandle(handle: String): Boolean = HANDLE.matches(handle)

/**
 * Asks whether an RSI handle belongs to the signed-in member (`POST /me/account-check`, REQ-XCH-031).
 * The handle goes only here; it is never stored.
 *
 * @param credentials the member's token and its key
 * @param handle the handle, [isCheckableHandle]
 * @param acceptLanguage the UI locale to relay
 * @return `match`, `mismatch` or `unknown`
 * @throws ExchangeException on any refusal or transport failure
 */
fun ExchangeClient.accountCheck(credentials: ExchangeCredentials, handle: String, acceptLanguage: String): AccountCheckResult =
    decode(
        post(
            "/me/account-check",
            jsonCodec().encodeToString(AccountCheckRequest.serializer(), AccountCheckRequest(handle)),
            credentials,
            acceptLanguage,
            write = false,
        ),
    )

/**
 * Reads one page of the member's blueprints (`GET /me/blueprints`).
 *
 * @param credentials the member's token and its key
 * @param cursor the cursor of the previous page, or `null` for the first page of a new snapshot
 * @param acceptLanguage the UI locale to relay
 * @return the page
 * @throws ExchangeException on any refusal or transport failure
 */
fun ExchangeClient.blueprintsPage(credentials: ExchangeCredentials, cursor: String?, acceptLanguage: String): BlueprintPage {
    val query =
        listOfNotNull(
            "limit=$PAGE_SIZE",
            cursor?.let { "cursor=" + URLEncoder.encode(it, StandardCharsets.UTF_8) },
        ).joinToString("&")
    return decode(get("/me/blueprints", query, credentials, acceptLanguage))
}

/**
 * Resolves blueprint references to Basetool keys (`POST /catalog/resolve`).
 *
 * @param credentials the member's token and its key
 * @param refs at most [BATCH_MAX] references
 * @param acceptLanguage the UI locale to relay
 * @return one result per reference
 * @throws ExchangeException on any refusal or transport failure
 */
fun ExchangeClient.resolveBlueprints(credentials: ExchangeCredentials, refs: List<ItemRef>, acceptLanguage: String): ResolveResponse =
    decode(
        post(
            "/catalog/resolve",
            jsonCodec().encodeToString(ResolveRequest.serializer(), ResolveRequest("BLUEPRINT", refs)),
            credentials,
            acceptLanguage,
            write = false,
        ),
    )

/**
 * Applies a blueprint change set (`POST /me/blueprints/changes`), with a fresh idempotency key.
 *
 * @param credentials the member's token and its key
 * @param changes at most [CHANGE_SET_MAX] adds
 * @param acceptLanguage the UI locale to relay
 * @return the outcome
 * @throws ExchangeException on any refusal or transport failure
 */
fun ExchangeClient.addBlueprints(credentials: ExchangeCredentials, changes: BlueprintChangeSet, acceptLanguage: String): ChangeResult =
    decode(
        post(
            "/me/blueprints/changes",
            jsonCodec().encodeToString(BlueprintChangeSet.serializer(), changes),
            credentials,
            acceptLanguage,
            write = true,
        ),
    )

/** The largest page the extractor asks for; the contract allows 1000. */
const val PAGE_SIZE = 1000

/** The most references one `catalog/resolve` request may carry. */
const val BATCH_MAX = 500

/**
 * The most ops one change set carries: the contract allows 500, but a set above 100 ops may be refused
 * `503 RELAY_BUSY` while the gateway relays other large sets.
 */
const val CHANGE_SET_MAX = 100
