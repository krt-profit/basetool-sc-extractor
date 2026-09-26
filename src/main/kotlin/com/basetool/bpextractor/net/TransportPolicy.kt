package com.basetool.bpextractor.net

import java.net.URI

/**
 * Decides which server URLs the outbound clients ([BasetoolIngestClient], `auth.DeviceGrantClient`)
 * may talk to: `https` anywhere, plain `http` only to the local machine. The URL is parsed, never
 * prefix-matched, so look-alike hosts and user-info tricks are refused.
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
