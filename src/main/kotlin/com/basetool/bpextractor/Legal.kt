package com.basetool.bpextractor

/**
 * Star Citizen Fankit attribution shown wherever the tool surfaces its Star Citizen
 * affiliation (GUI footer, CLI, docs). Required by the Star Citizen Fankit Guidelines
 * when using fankit assets such as the "Made by the Community" logo:
 *  - the trademark notice must be visible, legible, and ≥10pt (Guidelines 2b);
 *  - it must accompany the "Made by the Community" logo (Guidelines 2/6).
 */
object Legal {
    /**
     * The required trademark notice — VERBATIM from the Fankit Guidelines (page 2b).
     * Do not alter the wording (note: Squadron 42 is intentionally not listed; it is
     * not part of the required notice). `®` = the ® registered-trademark sign.
     */
    const val TRADEMARK_NOTICE: String =
        "Star Citizen®, Roberts Space Industries® and Cloud Imperium® " +
        "are registered trademarks of Cloud Imperium Rights LLC"

    /** Plain-language "unofficial fan project" line (cf. Guidelines 2a alternate text). */
    const val UNAFFILIATED: String =
        "This is an unofficial Star Citizen fan tool, not affiliated with the " +
        "Cloud Imperium group of companies."
}
