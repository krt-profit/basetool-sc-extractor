package com.basetool.bpextractor

/**
 * Star Citizen Fankit attribution, shown wherever the tool surfaces its Star Citizen affiliation.
 * The trademark notice must be legible at 10pt or more and accompany the "Made by the Community"
 * logo.
 */
object Legal {
    /** The trademark notice required by the Fankit Guidelines, verbatim; its wording must not change. */
    const val TRADEMARK_NOTICE: String =
        "Star Citizen®, Roberts Space Industries® and Cloud Imperium® " +
        "are registered trademarks of Cloud Imperium Rights LLC"

    /** Plain-language "unofficial fan project" line (cf. Guidelines 2a alternate text). */
    const val UNAFFILIATED: String =
        "This is an unofficial Star Citizen fan tool, not affiliated with the " +
        "Cloud Imperium group of companies."
}
