package com.basetool.bpextractor.net.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.net.URI
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the DPoP proof construction (RFC 9449, REQ-INGEST-012) from the outside, by decoding the
 * emitted JWT and re-deriving every answer independently.
 * [proofSignatureVerifiesUnderTheEmbeddedJwk] verifies the signature under the key rebuilt from the
 * `jwk` header alone.
 */
class DpopTest {

    private val key = DpopKey.generate()

    private fun decode(part: String): ByteArray = Base64.getUrlDecoder().decode(part)

    private fun jsonPart(part: String): JsonObject =
        Json.parseToJsonElement(decode(part).decodeToString()).jsonObject

    private fun header(proof: String): JsonObject = jsonPart(proof.split('.')[0])

    private fun claims(proof: String): JsonObject = jsonPart(proof.split('.')[1])

    private fun claim(proof: String, name: String): String? =
        claims(proof)[name]?.jsonPrimitive?.content

    /** Rebuilds the public key from the proof's own `jwk` header — no access to [key]'s internals. */
    private fun publicKeyFromHeader(proof: String): java.security.PublicKey {
        val jwk = header(proof)["jwk"]!!.jsonObject
        val params =
            AlgorithmParameters.getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        val point =
            ECPoint(
                BigInteger(1, decode(jwk["x"]!!.jsonPrimitive.content)),
                BigInteger(1, decode(jwk["y"]!!.jsonPrimitive.content)),
            )
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun proofSignatureVerifiesUnderTheEmbeddedJwk() {
        val proof = key.proof("POST", "https://ingest.example/v1/refinery-extract")
        val (headerPart, claimsPart, signaturePart) = proof.split('.')

        val verifier =
            Signature.getInstance("SHA256withECDSAinP1363Format").apply {
                initVerify(publicKeyFromHeader(proof))
                update("$headerPart.$claimsPart".toByteArray(Charsets.US_ASCII))
            }

        assertTrue(
            verifier.verify(decode(signaturePart)),
            "the ES256 signature must cover the exact header.payload the server re-derives",
        )
    }

    @Test
    fun aProofIsRejectedOnceAnyByteOfItIsTampered() {
        val proof = key.proof("POST", "https://ingest.example/v1/refinery-extract")
        val (headerPart, claimsPart, signaturePart) = proof.split('.')
        val forged = base64Url(claims(proof).toString().replace("POST", "GET_").toByteArray())

        val verifier =
            Signature.getInstance("SHA256withECDSAinP1363Format").apply {
                initVerify(publicKeyFromHeader(proof))
                update("$headerPart.$forged".toByteArray(Charsets.US_ASCII))
            }

        assertFalse(verifier.verify(decode(signaturePart)), "a tampered payload must not verify")
        assertEquals(3, proof.split('.').size)
        assertEquals(64, decode(signaturePart).size, "ES256 is raw R‖S, not a DER blob")
    }

    @Test
    fun headerCarriesTheDpopTypeAlgorithmAndPublicJwk() {
        val header = header(key.proof("POST", "https://ingest.example/v1/refinery-extract"))

        assertEquals("dpop+jwt", header["typ"]!!.jsonPrimitive.content)
        assertEquals("ES256", header["alg"]!!.jsonPrimitive.content)
        val jwk = header["jwk"]!!.jsonObject
        assertEquals("EC", jwk["kty"]!!.jsonPrimitive.content)
        assertEquals("P-256", jwk["crv"]!!.jsonPrimitive.content)
        assertEquals(setOf("crv", "kty", "x", "y"), jwk.keys)
    }

    @Test
    fun jwkCoordinatesAreAlwaysTheFullFieldWidth() {
        repeat(64) {
            val jwk = header(DpopKey.generate().proof("POST", "https://x/y"))["jwk"]!!.jsonObject
            assertEquals(32, decode(jwk["x"]!!.jsonPrimitive.content).size, "x must be 32 bytes")
            assertEquals(32, decode(jwk["y"]!!.jsonPrimitive.content).size, "y must be 32 bytes")
        }
    }

    @Test
    fun thumbprintIsTheRfc7638HashOfTheCanonicalJwk() {
        val proof = key.proof("POST", "https://ingest.example/v1/refinery-extract")
        val jwk = header(proof)["jwk"]!!.jsonObject
        val canonical =
            """{"crv":"P-256","kty":"EC","x":"${jwk["x"]!!.jsonPrimitive.content}",""" +
                """"y":"${jwk["y"]!!.jsonPrimitive.content}"}"""

        assertEquals(base64Url(sha256(canonical.toByteArray())), key.thumbprint)
    }

    @Test
    fun athIsTheBase64UrlSha256OfTheAccessToken() {
        val token = "eyJhbGciOiJSUzI1NiJ9.some-access-token"
        val proof = key.proof("POST", "https://ingest.example/v1/refinery-extract", accessToken = token)

        assertEquals(base64Url(sha256(token.toByteArray(Charsets.US_ASCII))), claim(proof, "ath"))
    }

    @Test
    fun athIsAbsentWhenNoTokenIsPresentedYet() {
        val proof = key.proof("POST", "https://keycloak.example/realms/iri/protocol/openid-connect/token")

        assertNull(claims(proof)["ath"], "no access token ⇒ no ath claim")
        assertNull(claims(proof)["nonce"], "no nonce demanded ⇒ no nonce claim")
    }

    @Test
    fun boundMethodUriAndIssueTimeAreCarriedVerbatim() {
        val issued = Instant.ofEpochSecond(1_800_000_000L)
        val proof =
            key.proof("POST", "https://ingest.example/v1/refinery-extract", issuedAt = issued)

        assertEquals("POST", claim(proof, "htm"))
        assertEquals("https://ingest.example/v1/refinery-extract", claim(proof, "htu"))
        assertEquals("1800000000", claim(proof, "iat"))
    }

    @Test
    fun everyProofGetsAFreshJti() {
        val ids = (1..200).map { claim(key.proof("POST", "https://ingest.example/v1/x"), "jti") }

        assertEquals(200, ids.toSet().size, "each proof needs its own jti")
        assertTrue(ids.all { !it.isNullOrBlank() })
    }

    @Test
    fun htuDropsQueryAndFragment() {
        assertEquals(
            "https://ingest.example/v1/refinery-extract",
            DpopKey.htu(URI.create("https://ingest.example/v1/refinery-extract?handoff=1#top")),
        )
    }

    @Test
    fun htuDropsARedundantDefaultPortButKeepsARealOne() {
        assertEquals(
            "https://ingest.example/v1/x",
            DpopKey.htu(URI.create("https://ingest.example:443/v1/x")),
        )
        assertEquals("http://localhost/v1/x", DpopKey.htu(URI.create("http://localhost:80/v1/x")))
        assertEquals(
            "http://localhost:8080/v1/x",
            DpopKey.htu(URI.create("http://localhost:8080/v1/x")),
        )
    }

    @Test
    fun htuLowerCasesSchemeAndHostAndKeepsAnEmptyPath() {
        assertEquals("https://ingest.example/v1/x", DpopKey.htu(URI.create("HTTPS://Ingest.Example/v1/x")))
        assertEquals("https://ingest.example", DpopKey.htu(URI.create("https://ingest.example")))
    }

    @Test
    fun aDpopKeyOffersNoWayToGetItsPrivateHalfOut() {
        val keyTypes = setOf(java.security.PrivateKey::class.java, java.security.KeyPair::class.java)
        val methods = DpopKey::class.java.methods
        assertFalse(methods.any { it.name == "encoded" || it.name == "getEncoded" })
        assertFalse(methods.any { it.returnType in keyTypes })
        assertFalse(methods.any { m -> m.returnType == ByteArray::class.java && m.parameterCount == 0 })
        assertNull(key.keyName)
        assertFalse(key.persistent)
    }

    @Test
    fun aLegacyExportedKeyIsStillReadableForTheOneRevocationItIsKeptFor() {
        val pair =
            java.security.KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        val legacy =
            Base64.getEncoder().encodeToString(pair.private.encoded) + "." +
                Base64.getEncoder().encodeToString(pair.public.encoded)
        val restored = assertNotNull(DpopKey.fromLegacyExport(legacy))
        val proof = restored.proof("POST", "https://sso.example/protocol/openid-connect/revoke")
        val (headerPart, claimsPart, signaturePart) = proof.split('.')

        val verifier =
            Signature.getInstance("SHA256withECDSAinP1363Format").apply {
                initVerify(pair.public)
                update("$headerPart.$claimsPart".toByteArray(Charsets.US_ASCII))
            }
        assertTrue(verifier.verify(decode(signaturePart)), "signed by the legacy key itself")
        assertContentEquals(pair.public.encoded, publicKeyFromHeader(proof).encoded)
        assertNull(restored.keyName, "a legacy key is never re-persisted under a name")
    }

    @Test
    fun anUnreadableLegacyKeyYieldsNullRatherThanThrowing() {
        assertNull(DpopKey.fromLegacyExport(""))
        assertNull(DpopKey.fromLegacyExport("not-base64"))
        assertNull(DpopKey.fromLegacyExport("bm90LWEta2V5.bm90LWEta2V5"))
        assertNull(DpopKey.fromLegacyExport("bm90LWEta2V5"))
    }

    @Test
    fun serverClockLearnsTheOffsetFromAnHttpDateAndAppliesItToIat() {
        val clock = ServerClock()
        assertEquals(0L, clock.offsetSeconds(), "an unmeasured clock must not shift anything")

        val sentAt = Instant.now()
        val serverTime = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(3_600)
        clock.observe(DateTimeFormatter.RFC_1123_DATE_TIME.format(serverTime), sentAt)

        assertTrue(clock.offsetSeconds() in 3_595..3_605, "was ${clock.offsetSeconds()}")
        val stamped = claim(key.proof("POST", "https://x/y", issuedAt = clock.now()), "iat")!!.toLong()
        assertTrue(
            stamped - Instant.now().epochSecond in 3_595..3_605,
            "the proof's iat must carry the correction",
        )
    }

    @Test
    fun serverClockIgnoresAnAbsentOrUnparseableDate() {
        val clock = ServerClock()
        clock.observe(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(600)), Instant.now())
        val learned = clock.offsetSeconds()

        clock.observe(null, Instant.now())
        clock.observe("", Instant.now())
        clock.observe("not-a-date", Instant.now())
        clock.observe("2026-08-03T12:00:00Z", Instant.now())

        assertEquals(learned, clock.offsetSeconds(), "a bad Date must not disturb what was learned")
    }

    @Test
    fun theNonceHandshakeIsDetectableButDeliberatelyUnimplemented() {
        assertEquals("DPoP-Nonce", DpopNonce.HEADER)
        assertEquals("use_dpop_nonce", DpopNonce.USE_DPOP_NONCE)
        assertNull(claims(key.proof("POST", "https://x/y"))["nonce"])
    }
}
