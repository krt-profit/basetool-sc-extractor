package com.basetool.bpextractor.net

import com.basetool.bpextractor.BlueprintExtractor
import com.basetool.bpextractor.refinery.RefineryPipeline
import com.basetool.bpextractor.refinery.model.RefineryExtract
import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import com.basetool.bpextractor.refinery.model.RefineryExtractImage
import com.basetool.bpextractor.refinery.model.RefineryExtractOrder
import com.basetool.bpextractor.ui.i18n.StringsDe
import com.basetool.bpextractor.ui.i18n.StringsEn
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the extractor sends and reads, held against the exchange v1 JSON Schemas and conformance
 * fixtures vendored under `src/test/resources/exchange-v1` (REQ-XCH-011): every payload it writes must
 * validate, and every valid fixture of an answer it reads must decode.
 */
class ExchangeContractTest {

    private val registry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
            builder.schemaIdResolvers { it.mapPrefix(BASE, "classpath:exchange-v1/schemas/") }
        }

    private val json = Json { ignoreUnknownKeys = true }

    private fun schema(name: String): Schema = registry.getSchema(SchemaLocation.of(BASE + name))

    private fun assertValid(schema: String, body: String) {
        val errors = schema(schema).validate(body, InputFormat.JSON)
        assertTrue(errors.isEmpty(), "$schema: $errors\n$body")
    }

    private fun fixtures(folder: String, kind: String = "valid"): List<File> =
        File(requireNotNull(javaClass.getResource("/exchange-v1/examples/$folder/$kind")).toURI())
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .also { assertTrue(it.isNotEmpty(), "no $kind fixtures in $folder") }

    @Test
    fun `the vendored fixtures still validate against the vendored schemas`() {
        for (folder in listOf("blueprint-draft", "refinery-draft", "draft-result", "installation", "problem")) {
            fixtures(folder).forEach { assertValid("$folder.schema.json", it.readText()) }
            fixtures(folder, "invalid").forEach {
                assertTrue(schema("$folder.schema.json").validate(it.readText(), InputFormat.JSON).isNotEmpty(), it.name)
            }
        }
    }

    @Test
    fun `the corpus envelope is a valid blueprint draft and carries nothing about the account`() {
        val corpus = File(requireNotNull(javaClass.getResource("/game-log-corpus-v1/LIVE")).toURI())
        val result = BlueprintExtractor.extract(corpus)
        val own = BlueprintExtractor.exportFor(result.export, requireNotNull(result.defaultAccount))
        val body = BlueprintExtractor.toJson(BlueprintExtractor.envelopeOf(own, Instant.parse("2026-09-27T12:00:00Z")))

        assertValid("blueprint-draft.schema.json", body)
        assertValid("envelope.schema.json", body)
        for (leak in listOf("PLAYER_A", "Game Build", "LIVE", "sourceFolder", "sourceFile", "player", "handle")) {
            assertTrue(leak !in body, "the envelope must not carry $leak")
        }
        assertEquals(31, Regex("\"ref\"").findAll(body).count())
    }

    @Test
    fun `a raw localisation key goes out as locKey without the at-sign and without a fake name`() {
        val envelope =
            BlueprintExtractor.envelopeOf(
                com.basetool.bpextractor.model.BlueprintScan(
                    sourceFolder = "x",
                    logFilesScanned = 1,
                    blueprints = listOf(
                        com.basetool.bpextractor.model.BlueprintEvent(
                            productName = "@Unknown_Item_Name",
                            category = "Other",
                            receivedAt = "2026-01-01T10:00:00.000Z",
                            sourceFile = "a.log",
                            localizationKey = "Unknown_Item_Name",
                        ),
                        com.basetool.bpextractor.model.BlueprintEvent(
                            productName = "Tankdüse Secure",
                            category = "Other",
                            receivedAt = "2026-01-01T11:00:00.000Z",
                            sourceFile = "a.log",
                            localizationKey = "Nozzle_FuelGiver_Name",
                        ),
                    ),
                ),
            )
        val body = BlueprintExtractor.toJson(envelope)

        assertValid("blueprint-draft.schema.json", body)
        assertEquals(listOf(null, "Tankdüse Secure"), envelope.items.map { it.ref.name })
        assertEquals(listOf("Unknown_Item_Name", "Nozzle_FuelGiver_Name"), envelope.items.map { it.ref.locKey })
    }

    @Test
    fun `repeats of one product collapse to the earliest receipt`() {
        fun event(at: String) =
            com.basetool.bpextractor.model.BlueprintEvent(
                productName = "Arclight Pistol",
                category = "Weapon",
                receivedAt = at,
                sourceFile = "a.log",
            )
        val envelope =
            BlueprintExtractor.envelopeOf(
                com.basetool.bpextractor.model.BlueprintScan(
                    sourceFolder = "x",
                    logFilesScanned = 1,
                    blueprints = listOf(event("2026-02-01T10:00:00.000Z"), event("2026-01-01T10:00:00.000Z"), event("")),
                ),
            )

        assertEquals(1, envelope.items.size)
        assertEquals("2026-01-01T10:00:00.000Z", envelope.items.single().acquiredAt)
    }

    @Test
    fun `a refinery extract with unread fields is a valid refinery draft`() {
        val extract =
            RefineryExtract(
                tool = RefineryPipeline.TOOL,
                toolVersion = "0.0.0-test",
                model = "qwen3-vl:8b-instruct",
                generatedAt = "2026-06-10T12:00:00Z",
                orders = listOf(
                    RefineryExtractOrder(
                        panelType = "SETUP",
                        quoted = false,
                        layoutConfidence = 0.9,
                        sourceImages = listOf(RefineryExtractImage("a.png", 3840, 2160, "vlm")),
                        goods = listOf(
                            RefineryExtractGood(
                                rowIndex = 0,
                                rawMaterialName = "QUANTANIUM (RAW)",
                                quality = 0,
                                inputQuantity = 76,
                                outputQuantity = null,
                                refine = true,
                                confidence = 0.95,
                                sourceImage = "a.png",
                            ),
                        ),
                    ),
                ),
            )

        assertValid("refinery-draft.schema.json", RefineryPipeline.toDraftJson(extract))
    }

    @Test
    fun `the offered installation labels are valid`() {
        for (label in listOf(StringsDe.send.defaultLabel, StringsEn.send.defaultLabel)) {
            assertValid("installation.schema.json", """{"label":"$label"}""")
        }
    }

    @Test
    fun `the answers the extractor reads decode from every valid fixture`() {
        fixtures("draft-result").forEach { f ->
            val result = json.decodeFromString<DraftResult>(f.readText())
            assertTrue(result.frontendUrl.isNotBlank() && result.handoffId.isNotBlank(), f.name)
        }
        fixtures("problem").forEach { f ->
            val problem = json.decodeFromString<ExchangeProblem>(f.readText())
            assertTrue(problem.code.isNotBlank() && problem.status >= 400, f.name)
        }
        val installation = json.decodeFromString<Installation>(
            File(requireNotNull(javaClass.getResource("/exchange-v1/examples/installation/valid/response.json")).toURI()).readText(),
        )
        assertEquals("inst-7f3c2a9e", installation.installationId)
    }

    @Test
    fun `every client name agrees`() {
        assertEquals(BlueprintExtractor.GENERATOR_NAME, RefineryPipeline.TOOL)
        assertEquals(BlueprintExtractor.GENERATOR_NAME, com.basetool.bpextractor.net.auth.DeviceGrantClient.CLIENT_ID)
    }

    private companion object {
        const val BASE = "https://ingest.profit-base.online/exchange/v1/schemas/"
    }
}
