package com.basetool.bpextractor.net

import java.net.URI

/**
 * Which server URLs the outbound clients ([BasetoolIngestClient], `auth.DeviceGrantClient`) may talk
 * to: `https` anywhere, plain `http` only to the local machine (a dev gateway or Keycloak).
 *
 * The URL is **parsed**, not prefix-matched. A prefix test such as `startsWith("http://localhost")`
 * also admits `http://localhost.attacker.tld` (a foreign host that merely begins with the word) and
 * `http://127.0.0.1@attacker.tld/` (user-info in front of a foreign host) — both would send the
 * refresh token, the DPoP proof and the export over cleartext to someone else's server.
 */
object TransportPolicy {

    /** The only hosts plain `http` is allowed for — loopback, i.e. a developer's own machine. */
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")

    /**
     * True when [url] is an absolute `https` URL with a host, or an `http` URL whose host is
     * exactly `localhost` or `127.0.0.1`. User-info (`user@host`) is refused in every case: it has
     * no legitimate use here and is the classic way to disguise the real host.
     */
    fun isAllowedServerUrl(url: String): Boolean {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (uri.rawUserInfo != null) return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> host in LOOPBACK_HOSTS
            else -> false
        }
    }
}
