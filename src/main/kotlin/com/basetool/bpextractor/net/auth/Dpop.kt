package com.basetool.bpextractor.net.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Signs ES256 (ECDSA P-256 over SHA-256, raw 64-byte R‖S) with a private key it never hands out.
 * Implemented by [JdkDpopSigner] (in-memory, process-bound) and by `CngDpopKeyStore`'s
 * non-exportable Windows key.
 */
internal interface DpopSigner {

    /** The P-256 public half — the proof's `jwk` and the input of the RFC 7638 thumbprint. */
    val publicKey: ECPublicKey

    /**
     * Signs [input] with ES256.
     *
     * @param input the JWS signing input (`base64url(header).base64url(claims)` as ASCII)
     * @return the 64-byte R‖S signature
     */
    fun signEs256(input: ByteArray): ByteArray
}

/**
 * A P-256 key pair held in JVM memory only. Used for the **session** key — when no persistent key
 * could be created (another OS, a Windows without a usable key storage provider), and by tests. It
 * is never serialized: nothing in this app can write its private half anywhere.
 */
internal class JdkDpopSigner(private val keyPair: KeyPair) : DpopSigner {

    override val publicKey: ECPublicKey = keyPair.public as ECPublicKey

    override fun signEs256(input: ByteArray): ByteArray =
        Signature.getInstance(ES256).run {
            initSign(keyPair.private)
            update(input)
            sign()
        }

    companion object {
        /** JWS `ES256` = ECDSA on P-256 with SHA-256; P1363 output is the raw R‖S JWS wants. */
        private const val ES256 = "SHA256withECDSAinP1363Format"

        /** NIST P-256 / `secp256r1` — the curve `REQ-INGEST-012` is tested against. */
        private const val CURVE = "secp256r1"

        /** A fresh in-memory P-256 key pair. */
        fun generate(): JdkDpopSigner {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE))
            return JdkDpopSigner(generator.generateKeyPair())
        }
    }
}

/**
 * A DPoP proof-of-possession key (RFC 9449) on EC P-256 / ES256 (REQ-INGEST-012). A key that outlives
 * the process is a non-exportable CNG key persisted only by [keyName]. Never log an instance.
 *
 * @param keyName the persisted key's name in its key storage provider, or `null` for a process-only
 *   key ([generate])
 */
class DpopKey internal constructor(private val signer: DpopSigner, val keyName: String? = null) {

    /**
     * The public key as the RFC 7638 canonical JWK (`crv`, `kty`, `x`, `y` in that order, no extras),
     * used both as the proof header's `jwk` and as the thumbprint input.
     */
    val publicJwk: JsonObject = jwkOf(signer.publicKey)

    /** The RFC 7638 JWK SHA-256 thumbprint — what Keycloak stamps into the token's `cnf.jkt`. */
    val thumbprint: String = base64Url(sha256(canonicalJson(publicJwk)))

    /** Whether this key survives the process (a named CNG key) rather than living in memory only. */
    val persistent: Boolean get() = keyName != null

    /**
     * Builds and signs one DPoP proof JWT for a single request (RFC 9449 §4.2).
     *
     * @param htm the upper-case HTTP method, e.g. `POST`
     * @param htu the absolute request URI without query and fragment, built with [htu]
     * @param accessToken the token the proof accompanies, adding the `ath` hash; absent at the token
     *   endpoint
     * @param issuedAt the `iat` instant, normally [ServerClock.now]
     * @param jti the proof's unique id, fresh per proof
     * @return the serialized `header.payload.signature` proof
     */
    fun proof(
        htm: String,
        htu: String,
        accessToken: String? = null,
        issuedAt: Instant = Instant.now(),
        jti: String = UUID.randomUUID().toString(),
    ): String {
        val header =
            buildJsonObject {
                put("typ", "dpop+jwt")
                put("alg", "ES256")
                put("jwk", publicJwk)
            }
        val claims =
            buildJsonObject {
                put("jti", jti)
                put("htm", htm)
                put("htu", htu)
                put("iat", issuedAt.epochSecond)
                if (accessToken != null) put("ath", base64Url(sha256(accessToken.toByteArray(Charsets.US_ASCII))))
            }
        val signingInput = "${base64Url(canonicalJson(header))}.${base64Url(canonicalJson(claims))}"
        val signature = signer.signEs256(signingInput.toByteArray(Charsets.US_ASCII))
        return "$signingInput.${base64Url(signature)}"
    }

    companion object {
        private val COMPACT = Json

        /**
         * Generates a fresh **in-memory** P-256 key for this process only. Persistent keys come
         * from a [DpopKeyStore] instead; this is the fallback when none can be created.
         *
         * @return the new key, with no [keyName]
         */
        fun generate(): DpopKey = DpopKey(JdkDpopSigner.generate())

        /**
         * Reads a legacy record's exported key pair (base64 PKCS#8 private and X.509 public encodings joined
         * by `.`), used only to revoke that record's refresh token. Fail-safe.
         *
         * @param encoded the `dpopKey` value of a legacy record
         * @return an in-memory key, or `null` when it cannot be read
         */
        internal fun fromLegacyExport(encoded: String): DpopKey? =
            try {
                val (privatePart, publicPart) = encoded.split('.', limit = 2).let { it[0] to it[1] }
                val factory = KeyFactory.getInstance("EC")
                val private =
                    factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privatePart)))
                val public =
                    factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicPart)))
                require(public is ECPublicKey && public.params.curve.field.fieldSize == 256)
                DpopKey(JdkDpopSigner(KeyPair(public, private)))
            } catch (_: Exception) {
                null
            }

        /**
         * The `htu` value for a request URI: without query and fragment, with a default port dropped and
         * scheme and host lower-cased (RFC 9449 §4.2, RFC 3986 §6).
         *
         * @param uri the absolute request URI
         * @return the normalized absolute URI to put in the proof
         */
        fun htu(uri: URI): String {
            val scheme = uri.scheme.orEmpty().lowercase()
            val host = uri.host.orEmpty().lowercase()
            val defaultPort = if (scheme == "https") 443 else if (scheme == "http") 80 else -1
            val authority = if (uri.port == -1 || uri.port == defaultPort) host else "$host:${uri.port}"
            return "$scheme://$authority${uri.rawPath.orEmpty()}"
        }

        /** The canonical (RFC 7638) public JWK: required members only, in lexicographic order. */
        private fun jwkOf(key: ECPublicKey): JsonObject {
            val length = (key.params.curve.field.fieldSize + 7) / 8
            return buildJsonObject {
                put("crv", "P-256")
                put("kty", "EC")
                put("x", base64Url(coordinate(key.w.affineX, length)))
                put("y", base64Url(coordinate(key.w.affineY, length)))
            }
        }

        /**
         * An [ECPoint] coordinate as the fixed-width big-endian octet string JWK requires: strips
         * `BigInteger`'s sign byte and left-pads short values, which a raw `toByteArray()` would get
         * wrong for roughly one key in 256 in each direction.
         */
        private fun coordinate(value: BigInteger, length: Int): ByteArray {
            val raw = value.toByteArray()
            if (raw.size == length) return raw
            val out = ByteArray(length)
            if (raw.size > length) {
                raw.copyInto(out, 0, raw.size - length, raw.size)
            } else {
                raw.copyInto(out, length - raw.size, 0, raw.size)
            }
            return out
        }

        private fun canonicalJson(value: JsonObject): ByteArray =
            COMPACT.encodeToString(JsonObject.serializer(), value).toByteArray(Charsets.UTF_8)

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * The clock one server's proofs are stamped from, corrected by the offset measured from that
 * server's HTTP `Date` headers so a proof's `iat` lands inside its acceptance window. One instance
 * per server.
 */
class ServerClock {

    @Volatile private var offset: Long = 0

    /**
     * How far the server's clock is ahead of this machine's, in seconds (negative when this machine runs
     * fast); zero until a `Date` has been seen.
     */
    fun offsetSeconds(): Long = offset

    /** The instant to stamp into a proof's `iat`: local time, corrected towards the server. */
    fun now(): Instant = Instant.now().plusSeconds(offset)

    /**
     * Learns the offset from one response; an absent or unparseable header is ignored.
     *
     * @param dateHeader the response's `Date` value
     * @param sentAt local time immediately before the request went out, used with the current time to
     *   take the round-trip midpoint
     */
    fun observe(dateHeader: String?, sentAt: Instant) {
        val server = parseHttpDate(dateHeader) ?: return
        val midpoint = sentAt.plusMillis(Duration.between(sentAt, Instant.now()).toMillis() / 2)
        offset = Duration.between(midpoint, server).seconds
    }

    companion object {
        /**
         * The offset change that justifies re-sending a rejected request with a corrected `iat`.
         * Below it the first proof was already inside every server's window, so the rejection had
         * another cause and a retry would only repeat it.
         */
        const val MATERIAL_SECONDS = 5L

        /**
         * The offset at which the clock is worth *telling the user about* — comfortably inside
         * Keycloak's ±15s tolerance, so it is only ever reported when it really is the likely cause.
         */
        const val REPORTABLE_SECONDS = 10L

        private fun parseHttpDate(value: String?): Instant? =
            try {
                if (value.isNullOrBlank()) {
                    null
                } else {
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                }
            } catch (_: Exception) {
                null
            }
    }
}

/**
 * Detects RFC 9449 §8's nonce challenge without implementing it: a challenge fails the send with
 * [CODE] so the UI can report that the server requires an unsupported handshake.
 */
object DpopNonce {

    /** The response header carrying a nonce; its mere presence on a 4xx is the challenge. */
    const val HEADER = "DPoP-Nonce"

    /** The RFC 9449 §8 error code an authorization server names in the challenge. */
    const val USE_DPOP_NONCE = "use_dpop_nonce"

    /** The reason code the UI keys its explanation off (client-synthesized, not a server code). */
    const val CODE = "DPOP_NONCE_REQUIRED"

    /**
     * Reports a nonce challenge on standard error for a developer running from a terminal; the user sees
     * the UI message keyed on [CODE]. The nonce itself is not printed.
     *
     * @param endpoint the URL that issued the challenge
     */
    fun reportChallenge(endpoint: String) {
        System.err.println(
            "DPoP: $endpoint demanded a nonce (RFC 9449 §8). This build does not implement the " +
                "nonce handshake; the request was NOT retried. This needs a new release.",
        )
    }
}
