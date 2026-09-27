package com.basetool.bpextractor.model

import kotlinx.serialization.Serializable

/**
 * The exchange v1 offline-file envelope of blueprints (`basetool.blueprints` 1.0,
 * `blueprint-draft.schema.json`): what the blueprint workflow saves and sends as a draft. It carries
 * no handle, player, source folder or file name.
 *
 * @param format the envelope format, always [FORMAT]
 * @param formatVersion the format's `major.minor`, always [FORMAT_VERSION]
 * @param generator the program that wrote it
 * @param generatedAt RFC 3339 UTC instant the envelope was written
 * @param items one entry per blueprint product
 */
@Serializable
data class BlueprintEnvelope(
    val format: String = FORMAT,
    val formatVersion: String = FORMAT_VERSION,
    val generator: Generator,
    val generatedAt: String,
    val items: List<BlueprintItem>,
) {
    companion object {
        /** The envelope format of blueprint files. */
        const val FORMAT = "basetool.blueprints"

        /** The format version this build writes. */
        const val FORMAT_VERSION = "1.0"

        /** The most items one draft may carry (`blueprint-draft.schema.json`). */
        const val MAX_ITEMS = 2000
    }
}

/**
 * The program that wrote an envelope.
 *
 * @param name its machine name
 * @param version its release version
 */
@Serializable
data class Generator(val name: String, val version: String)

/**
 * One blueprint product in an envelope.
 *
 * @param ref which product
 * @param acquiredAt RFC 3339 UTC instant it was first received, when the log said
 * @param provenance where the entry came from
 */
@Serializable
data class BlueprintItem(
    val ref: ItemRef,
    val acquiredAt: String? = null,
    val provenance: Provenance,
)

/**
 * An exchange item reference (`item-ref.schema.json`); the server resolves the first field it can.
 *
 * @param bt the Basetool's own key
 * @param locKey the game's `global.ini` name key, without the leading `@`
 * @param name the display name as the game wrote it
 */
@Serializable
data class ItemRef(
    val bt: String? = null,
    val locKey: String? = null,
    val name: String? = null,
)

/**
 * Where an entry came from (`provenance.schema.json`).
 *
 * @param source one of `log`, `manual`, `import`, `default`, `other`
 * @param observedAt RFC 3339 UTC instant it was observed
 */
@Serializable
data class Provenance(val source: String, val observedAt: String? = null) {
    companion object {
        /** An entry read from a game log. */
        const val LOG = "log"
    }
}
