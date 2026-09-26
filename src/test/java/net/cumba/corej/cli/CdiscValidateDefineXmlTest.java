package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the {@code -vx / --validate-xml} Phase 7 integration in {@link CdiscValidate}: promoting
 * the flag to run the standalone Define-XML conformance validator on the {@code -dxp} file and
 * write its JSON report to {@code <output-base>.define.json}.
 *
 * <p>
 * The runs point {@code -d} at an empty directory, so the data-validation lane fails fast (empty
 * library — exit 2 or a library-open error). The conformance report is emitted first and
 * independently, so its presence is asserted regardless of the data lane's outcome (see
 * {@code runTolerant}). Assertions are resilient: they check the report's structure (top-level keys
 * parse), never a corpus-dependent finding total.
 * </p>
 */
@org.junit.jupiter.api.extension.ExtendWith(WorkingDirectoryStaysCleanExtension.class)
@org.junit.jupiter.api.extension.ExtendWith(HomeStaysCleanExtension.class)
class CdiscValidateDefineXmlTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A minimal generated Define-XML corpus, written per test into a temp directory.
     *
     * <p>
     * ⚑ These tests used to reach across modules for the real corpus
     * ({@code repoRoot + "lib/corej-define-conformance/rules"}). That corpus has since moved to
     * {@code cumba-oss-corej-rules} and is now generated rather than authored, but the cross-module
     * path was worth losing on its own account: it coupled this client's tests to another module's
     * on-disk layout, and none of them assert on rule content — they assert the report's
     * <em>structure</em>. A synthetic package is therefore both sufficient and hermetic, and it
     * matches how the sibling tests here already build synthetic {@code --rules-dir} corpora.
     * </p>
     *
     * <p>
     * Both Define-XML versions are published so the test does not depend on which version the
     * fixture happens to declare.
     * </p>
     */
    private static String defineRules(Path aDir) throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("define-rules"));
        for (String version : new String[]
        {
                "2.0", "2.1"
        })
        {
            Files.writeString(
                    dir.resolve("rules-define-cdisc-" + version.replace('.', '-') + ".json"), """
                            {
                              "family" : "CDISC",
                              "version" : "%s",
                              "rules" : {
                                "DEFINE-XML-0001" : {
                                  "Rule_Id" : "DEFINE-XML-0001",
                                  "Rule_Set" : "CDISC",
                                  "Element" : "ODM",
                                  "Applicable_Versions" : [ "%s" ],
                                  "Plain_Text_Rule" : "The ODM element must carry a FileOID.",
                                  "Message" : "Attribute FileOID is missing for element ODM.",
                                  "Check" : { "kind" : "exists", "target" : "@FileOID" }
                                }
                              }
                            }
                            """.formatted(version, version), StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve("packages.json"), """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-cdisc-2-0.json", "family" : "CDISC",
                      "version" : "2.0", "ruleCount" : 1 },
                    { "file" : "rules-define-cdisc-2-1.json", "family" : "CDISC",
                      "version" : "2.1", "ruleCount" : 1 }
                  ]
                }
                """, StandardCharsets.UTF_8);
        return dir.toString();
    }

    @TempDir
    Path tempDir;

    @Test
    void validateXml_writesDefineJson_withExpectedTopLevelKeys() throws Exception
    {
        Path emptyData = Files.createDirectory(tempDir.resolve("data"));
        Path output = tempDir.resolve("report.json");

        Captured cap = new Captured();
        // The conformance report is written first and independently of the data-validation lane,
        // which may exit 2 or raise a library/network error on the empty overlay — either is fine.
        runTolerant(new String[]
        {
                "-d", emptyData.toString(), "-dxp", fixture().toString(), "-vx", "-o",
                output.toString(), "--rules-dir", tempDir.toString(), "--define-rules-dir",
                defineRules(tempDir), "--define-family", "CDISC"
        }, cap);

        Path defineJson = tempDir.resolve("report.define.json");
        assertTrue(Files.exists(defineJson), "conformance report must be written to " + defineJson);

        JsonNode root = MAPPER.readTree(Files.readString(defineJson, StandardCharsets.UTF_8));
        assertTrue(root.has("defineXml"), "defineXml key present");
        assertTrue(root.has("defineVersion"), "defineVersion key present");
        assertTrue(root.has("generatedAt"), "generatedAt key present");
        assertTrue(root.has("summary"), "summary key present");
        assertTrue(root.path("summary").has("totalFindings"), "summary.totalFindings present");
        assertTrue(root.path("summary").has("executionsByStatus"),
                "summary.executionsByStatus present");
        assertTrue(root.has("findings") && root.get("findings").isArray(),
                "findings array present");
        assertTrue(root.has("ruleExecutions") && root.get("ruleExecutions").isArray(),
                "ruleExecutions array present");
        // No store is named here (no -ca, none configured under target/test-home), so the CT
        // and library rules SKIP; the store-bound cases are the N4 tests below.
        assertTrue(root.path("ruleExecutions").size() > 0, "at least one rule executed/skipped");
    }


    @Test
    void validateXml_bareFlagBeforeDefineXml_stillEnables() throws Exception
    {
        // -vx bare (fallbackValue) placed immediately before another option must not swallow it.
        Path emptyData = Files.createDirectory(tempDir.resolve("data-bare"));
        Path output = tempDir.resolve("bare.json");

        Captured cap = new Captured();
        runTolerant(new String[]
        {
                "-vx", "-d", emptyData.toString(), "-dxp", fixture().toString(), "-o",
                output.toString(), "--rules-dir", tempDir.toString(), "--define-rules-dir",
                defineRules(tempDir), "--define-family", "CDISC"
        }, cap);

        assertTrue(Files.exists(tempDir.resolve("bare.define.json")),
                "bare -vx enables the conformance report");
    }

    // ------------------------------------------------------------------
    // N4 (PLAN-define-ct-evaluation): the store-backed CT and library binding of -vx
    // ------------------------------------------------------------------

    /**
     * The five CT rules naming an explicit Define-XML CT c-code (D-3): they execute only when a
     * {@code define-xmlct} package is bound, and SKIP — visibly — when only SDTM CT is (T1-4 a).
     */
    private static final List<String> DEFINE_XML_CT_RULES = List.of("DEFINE-XML-0132",
            "PMDA-DD0021-B", "PMDA-DD0055-C", "PMDA-DD0099", "PMDA-DD0100");

    /**
     * The 7 CDISC 2.1 and 17 PMDA 2.1 rules declaring {@code Requires: ct} in the shipped corpus.
     */
    private static final List<String> CT_RULES_21 = List.of("DEFINE-XML-0132", "DEFINE-XML-0179",
            "DEFINE-XML-0186", "DEFINE-XML-0187", "DEFINE-XML-0192", "DEFINE-XML-0201",
            "DEFINE-XML-0202", "PMDA-DD0021-B", "PMDA-DD0024", "PMDA-DD0024-B", "PMDA-DD0028",
            "PMDA-DD0028-B", "PMDA-DD0029", "PMDA-DD0029-B", "PMDA-DD0032", "PMDA-DD0032-B",
            "PMDA-DD0033", "PMDA-DD0034", "PMDA-DD0034-B", "PMDA-DD0055-C", "PMDA-DD0099",
            "PMDA-DD0100", "PMDA-DD0132", "PMDA-DD0132-B");

    /** The 8 CDISC 2.1 and 5 PMDA 2.1 rules declaring {@code Requires: library}. */
    private static final List<String> LIBRARY_RULES_21 = List.of("DEFINE-XML-0067",
            "DEFINE-XML-0097", "DEFINE-XML-0098", "DEFINE-XML-0099", "DEFINE-XML-0149",
            "DEFINE-XML-0263", "DEFINE-XML-0265", "DEFINE-XML-0266", "PMDA-DD0116", "PMDA-DD0118",
            "PMDA-DD0124", "PMDA-DD0136", "PMDA-DD0137");

    /** A 2.1 define declaring SDTMIG 3.4 + SDTM CT, with a mislabelled DM (0265 / DD0136). */
    private static final String LIBRARY_DEFINE = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1" FileOID="x" FileType="Snapshot"
                 ODMVersion="1.3.2" CreationDateTime="2026-01-01T00:00:00">
              <Study OID="ST">
                <GlobalVariables><StudyName>S</StudyName><StudyDescription>d</StudyDescription>
                  <ProtocolName>p</ProtocolName></GlobalVariables>
                <MetaDataVersion OID="MDV" Name="m" def:DefineVersion="2.1.0">
                  <def:Standards>
                    <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4" Status="Final"/>
                    <def:Standard OID="STD.CT" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                  Version="2024-03-29" Status="Final"/>
                  </def:Standards>
                  <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" Repeating="No" Purpose="Tabulation"
                                def:StandardOID="STD.IG" def:Structure="One record per subject"
                                SASDatasetName="DM">
                    <Description><TranslatedText xml:lang="en">Demographic Data</TranslatedText></Description>
                    <ItemRef ItemOID="IT.USUBJID" Mandatory="Yes"/>
                  </ItemGroupDef>
                  <ItemDef OID="IT.USUBJID" Name="USUBJID" DataType="text" Length="20">
                    <Description><TranslatedText xml:lang="en">Unique Subject Identifier</TranslatedText></Description>
                  </ItemDef>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    /** A 2.1 define whose only CT declaration names a package no store holds (T1-13 case 4). */
    private static final String DECLARES_MISSING_CT = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1" FileOID="x" FileType="Snapshot"
                 ODMVersion="1.3.2" CreationDateTime="2026-01-01T00:00:00">
              <Study OID="ST">
                <GlobalVariables><StudyName>S</StudyName><StudyDescription>d</StudyDescription>
                  <ProtocolName>p</ProtocolName></GlobalVariables>
                <MetaDataVersion OID="MDV" Name="m" def:DefineVersion="2.1.0">
                  <def:Standards>
                    <def:Standard OID="STD.CT" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                  Version="1999-01-01" Status="Final"/>
                  </def:Standards>
                </MetaDataVersion>
              </Study>
            </ODM>
            """;

    /**
     * The packaged Define-XML corpus the CLI bundles, staged by {@code download-corej-rules-define}
     * at generate-resources — the REAL rules, so the rosters below are the shipped ones. Asserted,
     * never assumed: a missing corpus fails this suite instead of silently removing it.
     */
    private static String bundledDefineRules() throws IOException
    {
        Path staged = Path.of(System.getProperty("projectBasedir", "."), "target",
                "corej-rules-define");
        assertTrue(Files.isDirectory(staged),
                "the bundled Define-XML corpus is staged at " + staged);
        try (java.util.stream.Stream<Path> dirs = Files.list(staged))
        {
            Path rules = dirs.map(d -> d.resolve("rules-define")).filter(Files::isDirectory)
                    .findFirst().orElseThrow(
                            () -> new AssertionError("no <version>/rules-define under " + staged));
            assertTrue(Files.isRegularFile(rules.resolve("packages.json")), rules.toString());
            return rules.toString();
        }
    }


    private static net.cumba.corej.core.metadata.store.StoredCodelist codelist(String aName,
            String aSubmissionValue, String aCCode, boolean aExtensible,
            java.util.Map<String, String> aTerms)
    {
        List<net.cumba.corej.core.metadata.store.StoredTerm> terms = new java.util.ArrayList<>();
        aTerms.forEach(
                (value, code) -> terms.add(new net.cumba.corej.core.metadata.store.StoredTerm(value,
                        code, value, null, null)));
        return new net.cumba.corej.core.metadata.store.StoredCodelist(aSubmissionValue, aCCode,
                aName, aName, null, null, aExtensible, terms);
    }


    /**
     * A store holding the corpus tests' stub CT as real packages — SEX, UNIT (sdtmct-2024-03-29),
     * STDNAM, GNRLOBSC (define-xmlct-2024-03-29) — plus a small SDTMIG 3.4 product (DM
     * "Demographics", USUBJID Req) with its SDTM 2.0 model. Written by the production writer, so it
     * is a genuine current-format store.
     */
    static Path store(Path aDir) throws IOException
    {
        net.cumba.corej.core.metadata.store.StoredCtPackage sdtm = new net.cumba.corej.core.metadata.store.StoredCtPackage(
                "sdtmct-2024-03-29",
                List.of(codelist("Sex", "SEX", "C66731", false,
                        java.util.Map.of("F", "C16576", "M", "C20197", "U", "C17998")),
                        codelist("Unit", "UNIT", "C71620", true,
                                java.util.Map.of("mg", "C28253", "mL", "C28254"))));
        net.cumba.corej.core.metadata.store.StoredCtPackage define = new net.cumba.corej.core.metadata.store.StoredCtPackage(
                "define-xmlct-2024-03-29", List.of(
                        codelist("Standard Name", "STDNAM", "C170452", true,
                                java.util.Map.of("SDTMIG", "C170455", "SENDIG", "C170456", "ADaMIG",
                                        "C170552", "CDISC/NCI", "C163415")),
                        codelist("General Observation Class", "GNRLOBSC", "C103329", false,
                                java.util.Map.of("EVENTS", "C103372", "FINDINGS", "C103373",
                                        "INTERVENTIONS", "C103374", "SPECIAL PURPOSE", "C103377")),
                        codelist("Analysis Reason", "ANLREAS", "C117744", true,
                                java.util.Map.of("SPECIFIED IN PROTOCOL", "C117749",
                                        "SPECIFIED IN SAP", "C117750", "DATA DRIVEN", "C117746")),
                        codelist("Analysis Purpose", "ANLPURP", "C117745", true,
                                java.util.Map.of("PRIMARY OUTCOME MEASURE", "C117752",
                                        "SECONDARY OUTCOME MEASURE", "C117753"))));
        net.cumba.corej.core.metadata.store.StoredVariable usubjid = net.cumba.corej.core.metadata.store.StoredVariable
                .builder().name("USUBJID").label("Unique Subject Identifier").core("Req").build();
        net.cumba.corej.core.metadata.store.StoredProduct ig = net.cumba.corej.core.metadata.store.StoredProduct
                .builder().key("standards/sdtmig/3-4").name("SDTMIG").version("3-4")
                .modelHref("/mdr/sdtm/2-0")
                .classes(List.of(new net.cumba.corej.core.metadata.store.StoredClass(
                        "Special-Purpose", null, "1", List.of(),
                        List.of(new net.cumba.corej.core.metadata.store.StoredDataset("DM",
                                "Demographics", "1", "One record per subject", List.of(usubjid),
                                null, null)),
                        null, List.of(), List.of(), List.of())))
                .build();
        net.cumba.corej.core.metadata.store.StoredProduct model = net.cumba.corej.core.metadata.store.StoredProduct
                .builder().key("models/sdtm/2-0").name("SDTM").version("2-0").build();
        Path store = aDir.resolve("store.zip");
        new net.cumba.corej.core.metadata.store.MetadataStoreWriter().addCtPackage(sdtm)
                .addCtPackage(define).addProduct(ig).addProduct(model)
                .publishedCtPackages(List.of("sdtmct-2024-03-29", "define-xmlct-2024-03-29"))
                .productCatalogue(List.of("standards/sdtmig/3-4")).write(store);
        return store;
    }


    private static java.util.Map<String, JsonNode> executionsById(Path aDefineJson)
        throws IOException
    {
        JsonNode root = MAPPER.readTree(Files.readString(aDefineJson, StandardCharsets.UTF_8));
        java.util.Map<String, JsonNode> byId = new java.util.HashMap<>();
        for (JsonNode execution : root.path("ruleExecutions"))
        {
            byId.put(execution.path("ruleId").asText(), execution);
        }
        assertFalse(byId.isEmpty(), "ruleExecutions present in " + aDefineJson);
        return byId;
    }


    private static void assertAllExecuted(java.util.Map<String, JsonNode> aExecutions,
            List<String> aRoster, String aWhat)
    {
        List<String> notExecuted = new java.util.ArrayList<>();
        for (String id : aRoster)
        {
            JsonNode execution = aExecutions.get(id);
            if (execution == null || !"EXECUTED".equals(execution.path("status").asText()))
            {
                notExecuted.add(id + "="
                        + (execution == null ? "absent" : execution.path("status").asText()));
            }
        }
        assertEquals(List.of(), notExecuted, aWhat + " must all be EXECUTED");
    }


    /** N4 case 1: with a store and {@code -ct}, every CT rule executes and DD0024 fires once. */
    @Test
    void validateXml_withAStoreAndCt_executesEveryCtRule() throws Exception
    {
        Path emptyData = Files.createDirectory(tempDir.resolve("data-ct"));
        Path store = store(tempDir);
        Captured cap = new Captured();
        runTolerant(new String[]
        {
                "-vx", "-d", emptyData.toString(), "-dxp", ctFixture().toString(), "-o",
                tempDir.resolve("ct.json").toString(), "--rules-dir", tempDir.toString(),
                "--define-rules-dir", bundledDefineRules(), "--define-family", "CDISC,PMDA", "-ca",
                store.toString(), "-ct", "sdtmct-2024-03-29", "-ct", "define-xmlct-2024-03-29"
        }, cap);

        java.util.Map<String, JsonNode> executions = executionsById(
                tempDir.resolve("ct.define.json"));
        assertAllExecuted(executions, CT_RULES_21, "the 24 CT rules of CDISC + PMDA 2.1");
        assertEquals(1, executions.get("PMDA-DD0024").path("findingCount").asInt(),
                "DD0024 fires on the fixture's non-member of the non-extensible SEX codelist");
        String err = cap.errAsString();
        assertTrue(err.contains("CT basis: define-xmlct-2024-03-29, sdtmct-2024-03-29 ("),
                "the CT basis line, Define-XML CT first (D-2): " + err);
        assertTrue(err.contains("Library basis: store " + store), err);
    }


    /**
     * N4 case 1, library half: with NO API key, the library rules execute from the store and
     * DEFINE-XML-0265 fires on the mislabelled dataset.
     */
    @Test
    void validateXml_withAStore_executesEveryLibraryRuleWithoutAnApiKey() throws Exception
    {
        // No API key is consulted anywhere on this path any more (T1-8 a): the case runs the same
        // whether or not the machine has one configured (review round 1, M3).
        Path emptyData = Files.createDirectory(tempDir.resolve("data-lib"));
        Path define = Files.writeString(tempDir.resolve("define.xml"), LIBRARY_DEFINE,
                StandardCharsets.UTF_8);
        Path store = store(tempDir);
        Captured cap = new Captured();
        runTolerant(new String[]
        {
                "-vx", "-d", emptyData.toString(), "-dxp", define.toString(), "-o",
                tempDir.resolve("lib.json").toString(), "--rules-dir", tempDir.toString(),
                "--define-rules-dir", bundledDefineRules(), "--define-family", "CDISC,PMDA", "-ca",
                store.toString()
        }, cap);

        java.util.Map<String, JsonNode> executions = executionsById(
                tempDir.resolve("lib.define.json"));
        assertAllExecuted(executions, LIBRARY_RULES_21, "the 13 library rules of CDISC + PMDA 2.1");
        assertEquals(1, executions.get("DEFINE-XML-0265").path("findingCount").asInt(),
                "0265 fires on DM 'Demographic Data' vs the IG's 'Demographics'");
        assertEquals(1, executions.get("PMDA-DD0136").path("findingCount").asInt());
        // A blank -ct takes the declaration (D1): SDTM CT is bound, so the CT rules execute -
        // except the five naming a Define-XML CT c-code, which nothing named (T1-4 a): the D-3
        // gate keeps them visibly SKIPPED instead of executing them against a CT that cannot
        // answer.
        List<String> sdtmCtRules = new java.util.ArrayList<>(CT_RULES_21);
        sdtmCtRules.removeAll(DEFINE_XML_CT_RULES);
        assertAllExecuted(executions, sdtmCtRules,
                "the declared SDTM CT is bound too (a blank -ct takes the declaration, D1)");
        for (String id : DEFINE_XML_CT_RULES)
        {
            assertEquals("SKIPPED_MISSING_CT", executions.get(id).path("status").asText(),
                    id + " names a Define-XML CT c-code the bound SDTM CT cannot serve (D-3)");
        }
        assertTrue(cap.errAsString().contains("CT basis: sdtmct-2024-03-29 ("), cap.errAsString());
    }


    /** N4 case 2: no store — every CT and library rule SKIPs, and stderr says why. */
    @Test
    void validateXml_withoutAStore_skipsCtAndLibraryRulesAndSaysSo() throws Exception
    {
        Path emptyData = Files.createDirectory(tempDir.resolve("data-nostore"));
        Captured cap = new Captured();
        runTolerant(new String[]
        {
                "-vx", "-d", emptyData.toString(), "-dxp", ctFixture().toString(), "-o",
                tempDir.resolve("nostore.json").toString(), "--rules-dir", tempDir.toString(),
                "--define-rules-dir", bundledDefineRules(), "--define-family", "CDISC,PMDA"
        }, cap);

        java.util.Map<String, JsonNode> executions = executionsById(
                tempDir.resolve("nostore.define.json"));
        for (String id : CT_RULES_21)
        {
            assertEquals("SKIPPED_MISSING_CT", executions.get(id).path("status").asText(), id);
        }
        for (String id : LIBRARY_RULES_21)
        {
            assertEquals("SKIPPED_MISSING_LIBRARY", executions.get(id).path("status").asText(), id);
        }
        String err = cap.errAsString();
        assertTrue(err.contains("CT basis: none - no metadata store is configured"), err);
        assertTrue(err.contains("Library basis: none - no metadata store is configured"), err);
    }


    /**
     * N4 case 3 (T1-2 a + T1-13 a): a {@code -ct} package the store lacks aborts the WHOLE
     * invocation — exit 2, no define report, no dataset run (no runtime csv, no report).
     */
    @Test
    void validateXml_ctPackageTheStoreLacks_abortsEverythingWithExit2() throws Exception
    {
        Path emptyData = Files.createDirectory(tempDir.resolve("data-abort"));
        Path store = store(tempDir);
        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "-vx", "-d", emptyData.toString(), "-dxp", ctFixture().toString(), "-o",
                tempDir.resolve("abort.json").toString(), "--rules-dir", tempDir.toString(),
                "--define-rules-dir", bundledDefineRules(), "--define-family", "CDISC,PMDA", "-ca",
                store.toString(), "-ct", "sdtmct-1999-01-01"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertFalse(Files.exists(tempDir.resolve("abort.define.json")),
                "no define report is written on an abort");
        assertNoDatasetRun("abort");
        String err = cap.errAsString();
        assertTrue(err.contains("Error: "), err);
        assertTrue(err.contains("sdtmct-1999-01-01"), err);
        assertTrue(err.contains(store.toString()), err);
    }


    /**
     * N4 case 4 (T1-13 a, the discriminator): the DEFINE declares a package the store lacks and
     * {@code -ct} is blank, so the dataset run alone would have been unaffected — still exit 2,
     * still no report of either kind.
     */
    @Test
    void validateXml_defineDeclaresAPackageTheStoreLacks_abortsEverythingWithExit2()
        throws Exception
    {
        Path emptyData = Files.createDirectory(tempDir.resolve("data-abort2"));
        Path define = Files.writeString(tempDir.resolve("declares-missing.xml"),
                DECLARES_MISSING_CT, StandardCharsets.UTF_8);
        Path store = store(tempDir);
        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "-vx", "-d", emptyData.toString(), "-dxp", define.toString(), "-o",
                tempDir.resolve("abort2.json").toString(), "--rules-dir", tempDir.toString(),
                "--define-rules-dir", bundledDefineRules(), "--define-family", "CDISC,PMDA", "-ca",
                store.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertFalse(Files.exists(tempDir.resolve("abort2.define.json")));
        assertNoDatasetRun("abort2");
        String err = cap.errAsString();
        assertTrue(err.contains("declares controlled-terminology package 'sdtmct-1999-01-01'"),
                err);
        assertTrue(err.contains("fill the CT Packages field"), "the escape hatch is named: " + err);
        assertTrue(err.contains("-ct <id>"), "this surface's own hatch is named (M1): " + err);
        assertTrue(err.contains("--seed-cache"), err);
    }


    /**
     * L4 (review round 1): an OLD-FORMAT store ends the whole invocation - -vx AND the dataset run
     * - with exit 2, nothing written, and the CLI's re-seed remedy (T1-10 a / m1).
     */
    @Test
    void anOldFormatStore_abortsTheWholeInvocationNamingTheSeedCacheRemedy() throws Exception
    {
        Path data = Files.createDirectory(tempDir.resolve("data-old"));
        Path old = store(tempDir);
        CdiscValidateSeedCacheTest.relabelFormatVersion(old, 1);
        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "-vx", "-d", data.toString(), "-dxp", ctFixture().toString(), "-o",
                tempDir.resolve("old.json").toString(), "--rules-dir", tempDir.toString(),
                "--define-rules-dir", bundledDefineRules(), "--define-family", "CDISC,PMDA", "-ca",
                old.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertFalse(Files.exists(tempDir.resolve("old.define.json")));
        assertNoDatasetRun("old");
        String err = cap.errAsString();
        assertTrue(err.contains("is format 1"), err);
        assertTrue(err.contains("must be re-seeded"), err);
        assertTrue(err.contains("--seed-cache"), err);
        assertTrue(!err.contains("--seed-overwrite"),
                "an OLD store re-seeds with a plain --seed-cache (D-17, M4): " + err);
        // The dataset run alone (no -vx) is the same abort: CdiscValidateTest
        // .aDatasetRunOnAnOldFormatStore_abortsNamingTheSeedCacheRemedy.
    }


    /** L5 (review round 1): a store NAMED with -ca that does not exist is a usage error. */
    @Test
    void aMissingExplicitStore_isAUsageErrorNamingTheFile() throws Exception
    {
        Path data = Files.createDirectory(tempDir.resolve("data-noca"));
        Path missing = tempDir.resolve("no-such-store.zip");
        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "-vx", "-d", data.toString(), "-dxp", ctFixture().toString(), "-o",
                tempDir.resolve("noca.json").toString(), "--rules-dir", tempDir.toString(),
                "--define-rules-dir", bundledDefineRules(), "--define-family", "CDISC,PMDA", "-ca",
                missing.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertFalse(Files.exists(tempDir.resolve("noca.define.json")));
        String err = cap.errAsString();
        assertTrue(err.contains("does not exist"), err);
        assertTrue(err.contains(missing.toAbsolutePath().toString()), err);
        assertTrue(!err.contains("no metadata store is configured"), err);
    }


    /**
     * The dataset run writes its runtime csv before validating; its absence proves it never ran.
     */
    private void assertNoDatasetRun(String aStem) throws IOException
    {
        try (java.util.stream.Stream<Path> files = Files.list(tempDir))
        {
            List<String> written = files.map(f -> f.getFileName().toString())
                    .filter(n -> n.startsWith(aStem + ".") || n.startsWith(aStem + "-")).sorted()
                    .toList();
            assertEquals(List.of(), written, "the dataset run must not have started");
        }
    }


    private static Path ctFixture() throws Exception
    {
        URL url = CdiscValidateDefineXmlTest.class.getResource("/define/ct-violations-21.xml");
        assertNotNull(url,
                "ct-violations-21.xml (from cumba-corej-rules) must be on the classpath");
        return Path.of(url.toURI());
    }


    @Test
    void validateXml_falseyValue_disablesReport() throws Exception
    {
        Path emptyData = Files.createDirectory(tempDir.resolve("data-off"));
        Path output = tempDir.resolve("off.json");

        Captured cap = new Captured();
        runTolerant(new String[]
        {
                "-d", emptyData.toString(), "-dxp", fixture().toString(), "-vx", "n", "-o",
                output.toString(), "--rules-dir", tempDir.toString()
        }, cap);

        assertFalse(Files.exists(tempDir.resolve("off.define.json")),
                "-vx n must not write a conformance report");
    }


    @Test
    void validateXml_withoutDefineXml_isUsageError() throws Exception
    {
        Path emptyData = Files.createDirectory(tempDir.resolve("data-nodxp"));

        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "-vx", "-d", emptyData.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc, "-vx without -dxp is a usage error");
        assertTrue(cap.errAsString().contains("-vx / --validate-xml requires -dxp"),
                "usage error names the missing -dxp requirement");
    }


    @Test
    void remoteMode_validateXml_isIgnoredWithNote() throws Exception
    {
        // A closed ephemeral port → the remote client fails fast with a connection error, but the
        // "-vx ignored in --remote mode" note is printed before any network call.
        int refusedPort;
        try (ServerSocket socket = new ServerSocket(0))
        {
            refusedPort = socket.getLocalPort();
        }
        String remoteUrl = "http://localhost:" + refusedPort;

        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "--remote", remoteUrl, "-dxp", fixture().toString(), "-vx"
        }, cap.out, cap.err);

        assertEquals(2, rc, "unreachable remote fails with exit 2");
        assertTrue(cap.errAsString().contains("ignored in --remote mode"),
                "remote mode prints the -vx ignore note");
    }


    @Test
    void validateXml_missingDefineRulesDir_isUsageError() throws Exception
    {
        // A bad --define-rules-dir must fail loudly (exit 2) rather than validate against no rules.
        Path emptyData = Files.createDirectory(tempDir.resolve("data-baddef"));

        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "-d", emptyData.toString(), "-dxp", fixture().toString(), "-vx", "--define-family",
                "CDISC", "-o", tempDir.resolve("x.json").toString(), "--define-rules-dir",
                tempDir.resolve("no-such-define-rules").toString()
        }, cap.out, cap.err);

        assertEquals(2, rc, "a missing --define-rules-dir is a usage error");
        assertTrue(cap.errAsString().contains("not found"), cap.errAsString());
        assertFalse(Files.exists(tempDir.resolve("x.define.json")),
                "no conformance report is written when the rules directory is invalid");
    }


    /**
     * The selection is mandatory and has no default. Before 2026-09-01 an unspecified
     * {@code --define-family} silently meant "every rule of both sheets" — a decision no caller had
     * made, and one that changed meaning as the corpus grew.
     */
    @Test
    void validateXml_withoutDefineFamily_isUsageError() throws Exception
    {
        Path emptyData = Files.createDirectory(tempDir.resolve("data"));

        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "-d", emptyData.toString(), "-dxp", fixture().toString(), "-vx", "-o",
                tempDir.resolve("x.json").toString(), "--define-rules-dir", defineRules(tempDir)
        }, cap.out, cap.err);

        assertEquals(2, rc, "a missing --define-family is a usage error");
        assertTrue(cap.errAsString().contains("--define-family"),
                "the error must name the flag that fixes it: " + cap.errAsString());
        assertFalse(Files.exists(tempDir.resolve("x.define.json")),
                "no conformance report is written when the selection is incomplete");
    }


    /** An unknown family name is refused rather than dropped, which would narrow the run. */
    @Test
    void validateXml_unknownDefineFamily_isUsageError() throws Exception
    {
        Path emptyData = Files.createDirectory(tempDir.resolve("data"));

        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "-d", emptyData.toString(), "-dxp", fixture().toString(), "-vx", "--define-family",
                "NOT-A-SHEET", "-o", tempDir.resolve("x.json").toString(), "--define-rules-dir",
                defineRules(tempDir)
        }, cap.out, cap.err);

        assertEquals(2, rc, "an unknown --define-family is a usage error");
        assertTrue(cap.errAsString().contains("NOT-A-SHEET"), cap.errAsString());
    }


    /**
     * Runs the CLI and swallows any {@link java.io.IOException} / {@link RuntimeException} raised
     * by the data-validation lane (empty overlay library, missing CDISC Library access, …). The
     * Define-XML conformance report is written before the data lane runs, so its artifact is
     * present regardless of that lane's outcome — which is what these tests assert.
     */
    private static void runTolerant(String[] args, Captured cap) throws Exception
    {
        try
        {
            OfflineCli.run(args, cap.out, cap.err);
        }
        catch (IOException | RuntimeException ignored)
        {
            // Data-lane variance is out of scope here; the conformance report is already written.
        }
    }


    private static Path fixture() throws Exception
    {
        URL url = CdiscValidateDefineXmlTest.class.getResource("/define/define-vlm-e2e.xml");
        assertNotNull(url, "define fixture must be on the test classpath");
        return Path.of(url.toURI());
    }

    private static final class Captured
    {

        private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();

        private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        final PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);

        final PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

        String errAsString()
        {
            return errBuf.toString(StandardCharsets.UTF_8);
        }
    }
}
