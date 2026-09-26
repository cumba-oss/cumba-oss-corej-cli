package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests {@link CdiscValidate#run(String[], PrintStream, PrintStream)}. Coverage focuses on the
 * argument parser, the help / usage branches, and the early validation-stage exit codes (missing
 * inputs, bad paths, threads ≤ 0). The full validator engine invocation requires a CDISC Library
 * API key + heavy library loads and is intentionally not exercised here.
 */
// Reflective bridge in test infrastructure; RuntimeException is the correct wrapping.
@SuppressWarnings("RethrowReflectiveOperationExceptionAsLinkageError")
@org.junit.jupiter.api.extension.ExtendWith(WorkingDirectoryStaysCleanExtension.class)
@org.junit.jupiter.api.extension.ExtendWith(HomeStaysCleanExtension.class)
class CdiscValidateTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void run_help_short_printsUsageToStdout() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-h"
        }, cap.out, cap.err);

        assertEquals(0, rc);
        assertTrue(cap.outAsString().contains("Usage: CdiscValidate"));
    }


    @Test
    void run_help_long_printsUsageToStdout() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "--help"
        }, cap.out, cap.err);

        assertEquals(0, rc);
        assertTrue(cap.outAsString().contains("Usage: CdiscValidate"));
    }


    @Test
    void run_unknownOption_returns2_writesError() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "--totally-bogus"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("Unknown option"));
    }


    @Test
    void run_missingValueForFlag_returns2() throws Exception
    {
        Captured cap = capture();
        // ⚑ --standard used to be the sample flag here; it is removed, so the subject (a live
        // flag given no value) needs a live flag. --data still takes one.
        int rc = OfflineCli.run(new String[]
        {
                "--data"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("Missing value"));
    }


    @Test
    void run_threadsNotNumeric_returns2() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-t", "abc", "-d", "/tmp"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("--threads expects a positive integer"));
    }


    @Test
    void run_threadsZero_returns2() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-t", "0", "-d", "/tmp"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("--threads must be"));
    }


    /** PLAN-use-case-scope-filter T1-3: a use-case LIST is a usage error, exit 2, value named. */
    @Test
    void run_useCaseList_returns2() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-uc", "INDH, PROD", "-d", "/tmp"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("the use case must be a single code"),
                cap.errAsString());
        assertTrue(cap.errAsString().contains("'INDH, PROD'"), cap.errAsString());
    }


    /**
     * ⛔ Plan 2 (R5): {@code -s} / {@code --standard} was removed. The old subject here was
     * "--standard and --version are required"; the successor is that the flag is refused by name
     * through {@code run}'s exit-code-2 path, like {@code -ss} and {@code -sdtmv} below.
     */
    @Test
    void run_removedStandardFlag_returns2() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-s", "sdtmig", "-d", "/tmp"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("-s / --standard was removed"), cap.errAsString());
    }


    @Test
    void run_missingDataAndDefineXml_returns2() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[] {}, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("--data") || cap.errAsString().contains("required"));
    }


    @Test
    void run_unsupportedOutputFormat_returns2() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", "/tmp", "-of", "xml"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("Only --output-format json"));
    }


    @Test
    void run_removedSubstandardOption_returns2NamingTheReplacement() throws Exception
    {
        // F5: end-to-end pin of the removed-flag path - same exit code as -dp (2), and the
        // stderr message names the replacement instead of picocli's misleading duplicated
        // --standard error from the "-s s" POSIX cluster.
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", "/tmp", "-ss", "adam"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("-ss / --substandard was removed"));
        assertTrue(cap.errAsString().contains("--metadata-products"));
    }


    @Test
    void run_removedSdtmVersionOption_returns2NamingTheReplacement() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", "/tmp", "-sdtmv", "3-3"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("-sdtmv / --sdtm-version was removed"));
        assertTrue(cap.errAsString().contains("--metadata-products"));
    }


    @Test
    void run_oldDatasetPathOption_removed_returns2() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-dp", "/tmp/x.xpt"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("-dp / --dataset-path was removed"));
    }


    /**
     * F-cli-03. {@code --max-errors-per-rule} used to accept ANY string containing digits, because
     * the parse used {@code find()} rather than {@code matches()} over {@code -?\\d+}:
     * {@code -me v2.1} silently capped at 2. The cap decides how many findings per rule are
     * materialised, so a typo changed the reported result with nothing said.
     */
    @Test
    void maxErrorsPerRule_aTokenThatMerelyContainsDigits_isAUsageError() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", "/tmp", "-me", "v2.1"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("--max-errors-per-rule expects an integer"),
                cap.errAsString());
        assertTrue(cap.errAsString().contains("v2.1"), cap.errAsString());
    }


    /** F-cli-03. A negative cap has no meaning and used to be accepted from the tuple form. */
    @Test
    void maxErrorsPerRule_negativeCap_isAUsageError() throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", "/tmp", "-me", "(-3, True)"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("must not be negative"), cap.errAsString());
    }


    /**
     * ⚑ Rewritten (F-cli-04). This case used to assert that a non-existent {@code -d} escapes
     * {@code CdiscValidate.run} as an {@code IOException} — i.e. it pinned a raw stack trace out of
     * {@code main} for a plain user typo, contradicting run()'s own comment that the data library /
     * define.xml existence pre-checks "own the user-visible Error: … stderr text and the
     * exit-code-2 contract that callers and tests rely on". Only {@code -dxp} had such a check. It
     * now behaves like {@code -dxp}: one stderr line and exit 2.
     */
    @Test
    void run_dataPathNotFound_isAUsageErrorNamingThePath() throws Exception
    {
        Path missing = tempDir.resolve("does-not-exist");

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", missing.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("Error: data library not found: "),
                cap.errAsString());
        assertTrue(cap.errAsString().contains(missing.toAbsolutePath().toString()),
                cap.errAsString());
    }


    @Test
    void run_defineXmlNotFound_alone_returns2() throws Exception
    {
        Path bogus = tempDir.resolve("no-such-define.xml");

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-dxp", bogus.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("define.xml not found"));
    }


    @Test
    void run_defineXmlNotFound_withData_returns2() throws Exception
    {
        Path source = makeEmptyFolder();
        Path bogus = tempDir.resolve("no-define.xml");

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-dxp", bogus.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("define.xml not found"));
    }


    @Test
    void run_emptyLibrary_returns2() throws Exception
    {
        Path empty = makeEmptyFolder();
        Path runtime = tempDir.resolve("rt.csv");

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", empty.toString(), "-o", tempDir.resolve("rep.json").toString(),
                "--runtime-report", runtime.toString(), "--rules-dir", tempDir.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("library contains no datasets")
                || cap.errAsString().contains("--dataset filter"));
    }


    @Test
    void run_emptyLibraryWithDatasetFilter_returns2() throws Exception
    {
        Path empty = makeEmptyFolder();

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", empty.toString(), "-ds", "DM", "-o", tempDir.resolve("rep.json").toString(),
                "--rules-dir", tempDir.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(
                cap.errAsString().contains("filter") || cap.errAsString().contains("no datasets"));
    }


    @Test
    void run_libraryWithDatasets_noRulesAvailable_returns2() throws Exception
    {
        // Folder with two CSVs → loadDatasets succeeds. Empty rules-dir → the selection
        // resolves to zero packages, so the R3 selection guard fails the run before
        // loadRules is ever reached.
        Path source = makeSourceFolderWithCsv();
        Path emptyRules = makeEmptyFolder();

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-o", tempDir.resolve("rep.json").toString(),
                "--rules-dir", emptyRules.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("No rule package selected"), cap.errAsString());
    }


    @Test
    void run_endToEnd_adamStandardKind_writesReport() throws Exception
    {
        // Different code-path: ADaM standard takes a different branch through
        // buildProvider (fetches adamct rather than sdtmct). The validation result itself
        // doesn't matter — the test exercises StandardKind.ADAM, which is otherwise unreached.
        Path source = makeSourceFolderWithCsv();
        Path rulesDir = Files.createDirectory(tempDir.resolve("rules-a-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-adamig-1-3.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-A-001"},
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Path report = tempDir.resolve("ar.json");

        Captured cap = capture();
        // R3: a run must name its rule package. Without -rp this case stopped at the
        // selection guard (exit 2, "No rule package selected") and never reached the path
        // its name claims.
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-o", report.toString(), "-rp", "adamig-1-3", "-mp",
                "adam/adamig-1-3", "--rules-dir", rulesDir.toString()
        }, cap.out, cap.err);

        assertEquals(0, rc, cap.errAsString());
        assertTrue(Files.exists(report), "JSON report must be written");
        // The report's conformance header is what proves the ADaM branch ran.
        JsonNode conformance = MAPPER.readTree(report.toFile()).path("Conformance_Details");
        assertEquals("ADAMIG", conformance.path("Standard").asText());
        assertEquals("V1.3", conformance.path("Version").asText());
    }


    /**
     * PLAN-define-ct-evaluation T1-10 (a), review round 1 L4: a dataset run (no {@code -vx})
     * against an OLD-FORMAT store aborts before any rule runs — exit 2, no report — naming the
     * store's format and this surface's remedy ({@code --seed-cache}, without
     * {@code --seed-overwrite}, which an old store does not need).
     */
    @Test
    void aDatasetRunOnAnOldFormatStore_abortsNamingTheSeedCacheRemedy() throws Exception
    {
        Path source = makeSourceFolderWithCsv();
        Path rulesDir = Files.createDirectory(tempDir.resolve("rules-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-TEST-001"},
                      "Sensitivity": "Record",
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        // The package DECLARES its standard, so the run has a product to resolve against the
        // store and reaches the store itself (no -mp: that path has its own abort, engine L4).
        new net.cumba.corej.core.RulePackageManifest("test",
                List.of(new net.cumba.corej.core.RulePackageManifest.Entry(
                        rulesFile.getFileName().toString(), "CDISC", "sdtmig", "3-4", 1,
                        List.of(new net.cumba.corej.core.model.StandardRef("sdtmig/3-4",
                                net.cumba.corej.core.model.StandardRef.Role.PRIMARY)))))
                                        .writeTo(rulesDir);
        Path old = CdiscValidateDefineXmlTest.store(tempDir);
        CdiscValidateSeedCacheTest.relabelFormatVersion(old, 1);
        Path report = tempDir.resolve("old-store.json");

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-o", report.toString(), "-rp", "sdtmig-3-4",
                "--rules-dir", rulesDir.toString(), "-ca", old.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc, cap.errAsString());
        assertFalse(Files.exists(report), "no report is written for an aborted run");
        String err = cap.errAsString();
        assertTrue(err.contains("is format 1"), err);
        assertTrue(err.contains("--seed-cache"), err);
        assertFalse(err.contains("--seed-overwrite"), err);
    }


    /**
     * Review round 2 (M-MP), made DISCRIMINATING in round 3 (L1): the {@code -mp} pre-check and the
     * resolution read the store the RUN resolves ({@code -ca} first, then the ambient
     * configuration), not {@code MetadataProductCatalogue.configured()}. The whole-invocation form
     * of this test passed with the fix reverted, because {@code applyMetadataStoreConfiguration}
     * publishes {@code -ca} into the very property tier the old call read - so this drives
     * {@code resolveMetadataProducts} DIRECTLY, with the property tier pointing at an old-format
     * store and {@code -ca} at a store of this format. Measured red with the two calls reverted to
     * {@code configured()} / {@code resolveAllConfigured(tokens)} (§11.6).
     */
    @Test
    void resolveMetadataProducts_readsTheExplicitStoreNotTheAmbientOne() throws Exception
    {
        Path explicit = CdiscValidateDefineXmlTest.store(tempDir);
        Path ambient = Files.copy(explicit, tempDir.resolve("ambient-old.zip"));
        CdiscValidateSeedCacheTest.relabelFormatVersion(ambient, 1);
        String key = net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory.STORE_PROPERTY;
        String saved = System.getProperty(key);
        System.setProperty(key, ambient.toString());
        try
        {
            CdiscValidate.Args withCa = CdiscValidate.Args.parse(new String[]
            {
                    "-d", tempDir.toString(), "-mp", "3-4", "-ca", explicit.toString()
            });
            // A SHORT token against the explicit store: resolves there, and is not vetoed by
            // the ambient old-format store (neither the pre-check nor the resolution reads it).
            assertEquals(List.of("standards/sdtmig/3-4"),
                    CdiscValidate.resolveMetadataProducts(withCa));

            // The same token with NO -ca reads the ambient store and names its format.
            CdiscValidate.Args ambientOnly = CdiscValidate.Args.parse(new String[]
            {
                    "-d", tempDir.toString(), "-mp", "3-4"
            });
            CdiscValidate.UsageException veto = assertThrows(CdiscValidate.UsageException.class,
                    () -> CdiscValidate.resolveMetadataProducts(ambientOnly));
            assertTrue(veto.getMessage().contains("is format 1"), veto.getMessage());
            assertTrue(veto.getMessage().contains("--seed-cache"), veto.getMessage());
        }
        finally
        {
            if (saved == null)
            {
                System.clearProperty(key);
            }
            else
            {
                System.setProperty(key, saved);
            }
        }
    }


    @Test
    void run_endToEnd_minimalRulePackage_writesReportAndRuntimeCsv() throws Exception
    {
        Path source = makeSourceFolderWithCsv();
        Path rulesDir = Files.createDirectory(tempDir.resolve("rules-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-TEST-001"},
                      "Sensitivity": "Record",
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);
        Path report = tempDir.resolve("rep.json");
        Path runtime = tempDir.resolve("rep.runtime.csv");

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-o", report.toString(), "-rp", "sdtmig-3-4", "-mp",
                "sdtmig/3-4", "--rules-dir", rulesDir.toString()
        }, cap.out, cap.err);

        // Successful run: rc 0 and the report + runtime CSV are written.
        assertEquals(0, rc);
        assertTrue(Files.exists(report), "JSON report must be written");
        assertTrue(Files.exists(runtime), "runtime CSV must be written");
        String runtimeContent = Files.readString(runtime, StandardCharsets.UTF_8);
        assertTrue(runtimeContent.startsWith("domain,fileName,rowCount,columnCount,coreId,"));
    }


    /**
     * PLAN-report-conformance-fields P8: the report's conformance header states the cap the run
     * applied and the engine jar's own version. {@code -me 2} over a rule that flags all three DM
     * rows lists exactly two of them and says {@code "2"} / {@code "True"}; {@code -me 0} says
     * {@code "None"} / {@code "None"}. Unfixed, both limit fields read {@code "None"} always.
     */
    @Test
    void run_endToEnd_reportStatesTheAppliedCapAndTheEngineVersion() throws Exception
    {
        Path source = makeSourceFolderWithCsv();
        Path rulesDir = Files.createDirectory(tempDir.resolve("rules-cap-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-TEST-CAP"},
                      "Sensitivity": "Record",
                      "Check": {"expression": "not empty(USUBJID)"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        JsonNode capped = conformanceOfRun(source, rulesDir, "capped", "-me", "2");
        assertEquals("2", capped.path("Issue_Limit_Per_Rule").asText());
        assertEquals("True", capped.path("Issue_Limit_Per_Dataset").asText());
        // Per rule AND per dataset: the source folder holds dm.csv (3 rows) and vs.csv, and each
        // dataset is capped on its own — DM lists exactly 2 of its 3 rows.
        assertEquals(2, issueRows(tempDir.resolve("capped.json"), "CORE-TEST-CAP", "dm.csv"),
                "the cap really applied to the listed DM findings");
        String engine = net.cumba.corej.core.VersionInfo
                .forClass(net.cumba.corej.core.run.StudyValidationService.class).version();
        assertNotEquals("unknown", engine, "CONTROL FAILED: the engine jar is unfiltered");
        assertEquals(engine, capped.path("CORE_Engine_Version").asText());

        JsonNode unlimited = conformanceOfRun(source, rulesDir, "unlimited", "-me", "0");
        assertEquals("None", unlimited.path("Issue_Limit_Per_Rule").asText());
        assertEquals("None", unlimited.path("Issue_Limit_Per_Dataset").asText());
        assertEquals(3, issueRows(tempDir.resolve("unlimited.json"), "CORE-TEST-CAP", "dm.csv"),
                "without a cap every DM row is listed (the fixture flags all three)");
    }


    /**
     * PLAN-report-conformance-fields P14 / R7 (local arm): a run whose Define-XML declares a CT
     * package the run did not use prints the CT declaration mismatch on stderr, beside the
     * dictionary-basis line; a run with no such divergence prints nothing. Before R7 the line was
     * absent on every run.
     */
    @Test
    void run_endToEnd_printsTheCtDeclarationMismatchOnStderr() throws Exception
    {
        Path source = makeSourceFolderWithCsv();
        Path rulesDir = Files.createDirectory(tempDir.resolve("rules-ct-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-TEST-CT"},
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);
        // The full VLM fixture (a define the overlay library can open) with ONE addition: a
        // declared CT package the run below will not use.
        // The define library supplier accepts only a path ending in "/define.xml".
        Path define = Files.createDirectory(tempDir.resolve("ct-define")).resolve("define.xml");
        String fixture;
        try (var in = CdiscValidateTest.class.getResourceAsStream("/define/define-vlm-e2e.xml"))
        {
            assertNotNull(in);
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String opening = "StandardVersion=\"3.4\">";
        assertTrue(fixture.contains(opening), "fixture shape changed: " + opening);
        Files.writeString(define, fixture.replace(opening, opening + """

                      <def:Standards>
                        <def:Standard OID="STD.CT" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                      Version="2023-12-15" Status="Final"/>
                      </def:Standards>
                """));

        Captured mismatch = runOffline(source, rulesDir, "ct-mismatch", "-dxp", define.toString(),
                "-ct", "sdtmct-2024-09-27");
        assertTrue(mismatch.errAsString().contains(
                "CT declaration mismatch: define declares sdtmct-2023-12-15; run used sdtmct-2024-09-27"),
                mismatch.errAsString());

        Captured agreeing = runOffline(source, rulesDir, "ct-agree", "-dxp", define.toString());
        assertFalse(agreeing.errAsString().contains("CT declaration mismatch"),
                "declaration and selection agree, so no line: " + agreeing.errAsString());
    }


    /**
     * PLAN-report-conformance-fields T1-3 (owner: warn, keep accepting): the Python-CLI tuple's
     * {@code False} asked for a cumulative cap this engine does not have. The value is still
     * accepted (the cap is 5) and a warning says what will actually happen.
     */
    @Test
    void maxErrorsPerRule_tupleWithFalseFlag_isAcceptedWithAWarning() throws Exception
    {
        Captured warned = capture();
        CdiscValidate.Args parsed = CdiscValidate.Args.parse(new String[]
        {
                "-d", "/tmp", "-me", "(5, False)"
        }, warned.err);
        assertEquals(5, parsed.maxErrorsPerRule);
        assertTrue(warned.errAsString().contains("Warning: --max-errors-per-rule (5, False)"),
                warned.errAsString());
        assertTrue(warned.errAsString().contains("per rule and dataset"), warned.errAsString());

        Captured quiet = capture();
        CdiscValidate.Args trueFlag = CdiscValidate.Args.parse(new String[]
        {
                "-d", "/tmp", "-me", "(5, True)"
        }, quiet.err);
        assertEquals(5, trueFlag.maxErrorsPerRule);
        assertEquals("", quiet.errAsString(), "True is what the engine does: no warning");

        // Review L1: "(0, False)" asks for no cap at all — nothing is unhonoured, no warning.
        Captured unlimited = capture();
        CdiscValidate.Args noCap = CdiscValidate.Args.parse(new String[]
        {
                "-d", "/tmp", "-me", "(0, False)"
        }, unlimited.err);
        assertEquals(0, noCap.maxErrorsPerRule);
        assertEquals("", unlimited.errAsString(), "an unlimited cap has no per-dataset reading");
    }


    /**
     * Runs an offline local validation writing {@code <name>.json} and asserts exit 0.
     *
     * <p>
     * Review rounds 2 and 3 (L2, D1): there is no network-only skip here, nor in any other case of
     * this class. No validation run consults the CDISC Library any more — since
     * PLAN-define-ct-evaluation (T1-8 a) {@code -vx} reads the metadata store like every other
     * check, and only {@code --seed-cache-from-api} calls the Library — and {@link OfflineCli}
     * denies the dictionary and pickle-archive downloads; an expected failure is an exit code, not
     * an exception. A skip-on-exception branch could therefore only have hidden a real engine
     * failure as a SKIP.
     * </p>
     */
    private Captured runOffline(Path source, Path rulesDir, String name, String... extra)
        throws Exception
    {
        List<String> argv = new ArrayList<>(List.of("-d", source.toString(), "-o",
                tempDir.resolve(name + ".json").toString(), "-rp", "sdtmig-3-4", "-mp",
                "sdtmig/3-4", "--rules-dir", rulesDir.toString()));
        argv.addAll(List.of(extra));
        Captured cap = capture();
        int rc = OfflineCli.run(argv.toArray(new String[0]), cap.out, cap.err);
        assertEquals(0, rc, cap.errAsString());
        return cap;
    }


    private JsonNode conformanceOfRun(Path source, Path rulesDir, String name, String... extra)
        throws Exception
    {
        runOffline(source, rulesDir, name, extra);
        Path report = tempDir.resolve(name + ".json");
        assertTrue(Files.exists(report), "JSON report must be written");
        return MAPPER.readTree(report.toFile()).path("Conformance_Details");
    }


    private static long issueRows(Path report, String coreId, String dataset)
        throws java.io.IOException
    {
        long n = 0;
        for (JsonNode row : MAPPER.readTree(report.toFile()).path("Issue_Details"))
        {
            if (coreId.equals(row.path("core_id").asText())
                    && dataset.equalsIgnoreCase(row.path("dataset").asText()))
            {
                n++;
            }
        }
        return n;
    }


    @Test
    void run_endToEnd_includeFilter_excludesEverything() throws Exception
    {
        // Include filter that matches no rule id → "no rules selected" branch.
        Path source = makeSourceFolderWithCsv();
        Path rulesDir = Files.createDirectory(tempDir.resolve("rules-x-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-TEST-001"},
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-r", "CORE-NO-MATCH", "-rp", "sdtmig-3-4", "-mp",
                "sdtmig/3-4", "--rules-dir", rulesDir.toString(), "-o",
                tempDir.resolve("include-filter.json").toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        // ⚑ A package WAS selected; the include filter then emptied the rule list, so this
        // hits validate()'s rules-empty guard, NOT Phase 1's selection-empty guard.
        assertTrue(cap.errAsString().contains("no rules selected for validation"),
                cap.errAsString());
    }


    @Test
    void run_referenceDataMissing_throwsIOException()
    {
        Path source = pathToFolderWithCsv();

        Captured cap = capture();
        java.io.IOException ex = assertThrows(java.io.IOException.class,
                () -> OfflineCli.run(new String[]
                {
                        "-d", source.toString(), "-rd", tempDir.resolve("nope").toString(),
                        "--rules-dir", tempDir.toString(), "-o",
                        tempDir.resolve("refdata.json").toString()
                }, cap.out, cap.err));
        assertTrue(ex.getMessage().contains("path not found")
                || ex.getMessage().contains("not found"));
    }


    private Path pathToFolderWithCsv()
    {
        try
        {
            return makeSourceFolderWithCsv();
        }
        catch (java.io.IOException ioe)
        {
            throw new AssertionError(ioe);
        }
    }


    @Test
    void run_inlineEqualsValue_acceptedAsArg() throws Exception
    {
        // Inline "--rules-package=x" should parse identically to "--rules-package x".
        // (--standard=sdtmig was the sample until Plan 2 R5 removed the flag.)
        // Help suppresses the rest of the parse: combine with -h so the test stays trivial.
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "--rules-package=cdisc-sdtmig-3-4", "-h"
        }, cap.out, cap.err);

        assertEquals(0, rc);
        assertTrue(cap.outAsString().contains("Usage: CdiscValidate"));
    }


    @Test
    void run_ignoredOption_warningWritten() throws Exception
    {
        // -p / --progress is accepted-but-ignored. It should not abort the parse; with -h the
        // parse still completes and the "Note: ignoring unsupported options" message lands on
        // stderr.
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "--progress", "fancy", "-h"
        }, cap.out, cap.err);

        assertEquals(0, rc);
        // The ignored-options note is only emitted when help is not the sole flag — but the
        // parser still records it. Either branch is acceptable; the parser did not error.
        assertNotEquals("", cap.outAsString());
    }


    // Each case combines parse-only options with -h so the run exits 0 before validation:
    // includeAndExclude: repeating -r / -er must not error
    // datasetFilterCsv: -ds DM,VS,AE (comma-separated list)
    // inlineEqualsLower: -rp=cdisc-sdtmig-3-4 / -t=2 (lowercase inline-equals form)
    @ParameterizedTest(name = "parseOnly_exits0[{0}]")
    @MethodSource("parseOnlyHelpCases")
    void run_parseOnlyOptions_withHelp_exits0(String name, String[] args) throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(args, cap.out, cap.err);

        assertEquals(0, rc);
    }


    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> parseOnlyHelpCases()
    {
        return java.util.stream.Stream
                .of(org.junit.jupiter.params.provider.Arguments.of("includeAndExclude", new String[]
                {
                        "-r", "CORE-000001", "-r", "CORE-000002", "-er", "CORE-000003", "-h"
                }), org.junit.jupiter.params.provider.Arguments.of("datasetFilterCsv", new String[]
                {
                        "-ds", "DM,VS,AE", "-h"
                }), org.junit.jupiter.params.provider.Arguments.of("inlineEqualsLower", new String[]
                {
                        "-rp=cdisc-sdtmig-3-4", "-t=2", "-h"
                }));
    }


    @Test
    void run_endToEnd_withReferenceLibrary_writesReport() throws Exception
    {
        // Primary library has DM; reference library has VS (distinct domain).
        // Exercises loadReferenceLibrary, the soft-memoised reference supplier, and the
        // `Phase A2` external-reference registration path in run().
        Path source = makeFolderWith("dm.csv");
        Path refLib = makeFolderWith("vs.csv");

        Path rulesDir = Files.createDirectory(tempDir.resolve("rd-rules-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-RD-001"},
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-rd", refLib.toString(), "-o",
                tempDir.resolve("rd.json").toString(), "-rp", "sdtmig-3-4", "-mp", "sdtmig/3-4",
                "--rules-dir", rulesDir.toString()
        }, cap.out, cap.err);
        assertEquals(0, rc);
        assertTrue(Files.exists(tempDir.resolve("rd.json")));
    }


    @Test
    void run_endToEnd_withReferenceLibrary_duplicateDomainSkipped() throws Exception
    {
        // Primary library has DM and VS; reference library also has DM (same name) → the
        // duplicate-domain path in loadReferenceLibrary is exercised (skip + warn).
        Path source = makeSourceFolderWithCsv();
        Path refLib = makeFolderWith("dm.csv");

        Path rulesDir = Files.createDirectory(tempDir.resolve("rd-dup-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-RD-DUP-001"},
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Path report = tempDir.resolve("dup.json");

        Captured cap = capture();
        // The report cannot tell a skipped duplicate from a registered one (a reference dataset
        // is never validated, so DM reports from the primary either way). The skip is observable
        // only through StudyValidationService's INFO line, so collect that logger's INFO output.
        // The strong local reference also keeps the JUL logger (weakly held by the LogManager)
        // alive for the whole run.
        java.util.logging.Logger serviceLog = java.util.logging.Logger
                .getLogger(net.cumba.corej.core.run.StudyValidationService.class.getName());
        List<String> serviceInfo = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.logging.Handler infoHandler = new java.util.logging.Handler()
        {

            @Override
            public void publish(java.util.logging.LogRecord aRecord)
            {
                if (aRecord.getLevel().intValue() >= java.util.logging.Level.INFO.intValue())
                {
                    serviceInfo.add(java.text.MessageFormat
                            .format(String.valueOf(aRecord.getMessage()), aRecord.getParameters()));
                }
            }


            @Override
            public void flush()
            {
                // nothing buffered
            }


            @Override
            public void close()
            {
                // nothing to release
            }
        };
        infoHandler.setLevel(java.util.logging.Level.ALL);
        java.util.logging.Level previousLevel = serviceLog.getLevel();
        serviceLog.addHandler(infoHandler);
        serviceLog.setLevel(java.util.logging.Level.INFO);
        int rc;
        try
        {
            // R3: a run must name its rule package. Without -rp this case stopped at the
            // selection guard (exit 2, "No rule package selected") and never reached the path
            // its name claims.
            rc = OfflineCli.run(new String[]
            {
                    "-d", source.toString(), "-rd", refLib.toString(), "-o", report.toString(),
                    "-rp", "sdtmig-3-4", "-mp", "sdtmig/3-4", "--rules-dir", rulesDir.toString()
            }, cap.out, cap.err);
        }
        finally
        {
            serviceLog.removeHandler(infoHandler);
            serviceLog.setLevel(previousLevel);
        }

        assertEquals(0, rc, cap.errAsString());
        assertTrue(Files.exists(report), "JSON report must be written");
        // ⭐ The discriminating assertion: the duplicate reference DM was SKIPPED, by the
        // duplicate-domain branch of loadReferenceLibrary, and nothing else was.
        List<String> skipped = serviceInfo.stream()
                .filter(l -> l.contains("[reference-data] skipped")).toList();
        assertEquals(1, skipped.size(), () -> "exactly one reference domain must be skipped; INFO "
                + "lines: " + serviceInfo);
        assertTrue(
                skipped.get(0).toUpperCase(Locale.ROOT).contains("SKIPPED DM ")
                        && skipped.get(0).contains("domain already loaded from primary library"),
                () -> "the skip must name DM as already loaded: " + skipped.get(0));
        // The reference library's DM duplicates the primary's and is skipped: DM is validated
        // once, and from the primary library.
        assertEquals(List.of("DM", "VS"), reportedDomains(report).stream().sorted().toList());
        for (JsonNode dataset : MAPPER.readTree(report.toFile()).path("Dataset_Details"))
        {
            assertEquals(source.getFileName(), Path.of(dataset.path("path").asText()).getFileName(),
                    "validated from the primary library: " + dataset);
        }
    }


    @Test
    void run_endToEnd_datasetFilterFiltersOutMember_referenceLane() throws Exception
    {
        // Primary library has DM + VS; -ds DM keeps DM as target, VS becomes an in-library
        // reference. Exercises the references-path in loadDatasets and the
        // `loaded.references()` registration in run().
        Path source = makeSourceFolderWithCsv();

        Path rulesDir = Files.createDirectory(tempDir.resolve("ds-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-DS-001"},
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Path report = tempDir.resolve("ds.json");

        Captured cap = capture();
        // R3: a run must name its rule package. Without -rp this case stopped at the
        // selection guard (exit 2, "No rule package selected") and never reached the path
        // its name claims.
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-ds", "DM", "-o", report.toString(), "-rp", "sdtmig-3-4",
                "-mp", "sdtmig/3-4", "--rules-dir", rulesDir.toString()
        }, cap.out, cap.err);

        assertEquals(0, rc, cap.errAsString());
        assertTrue(Files.exists(report), "JSON report must be written");
        // VS is only a reference now: DM is the one dataset validated.
        assertEquals(List.of("DM"), reportedDomains(report));
    }


    @Test
    void run_endToEnd_datasetFilterUnmatchedMember_logsWarning() throws Exception
    {
        // -ds NOSUCH → no target matches, library still has DM + VS as references.
        // After the filter rejects everything, run() exits 2 with "filter matched no members".
        Path source = makeSourceFolderWithCsv();

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-ds", "NOSUCH", "-o",
                tempDir.resolve("nm.json").toString(), "--rules-dir", tempDir.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("filter"));
    }


    @Test
    void run_endToEnd_noOutputFlag_usesDefaultName() throws Exception
    {
        // No -o → defaultOutputName + the runtime-report fallback in resolveRuntimeReportPath
        // (parent-is-null branch when the default name has no parent directory).
        Path source = makeSourceFolderWithCsv();

        Path rulesDir = Files.createDirectory(tempDir.resolve("nout-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-NOOUT-001"},
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        // Change to tempDir for the duration of the run so the default-name file lands there.
        String savedUserDir = System.getProperty("user.dir");
        Path runtime = tempDir.resolve("nout-rt.csv");
        Captured cap = capture();
        int rc;
        try
        {
            System.setProperty("user.dir", tempDir.toString());
            // R3: a run must name its rule package. Without -rp this case stopped at the
            // selection guard (exit 2, "No rule package selected") and never reached the path
            // its name claims.
            // Use --runtime-report to avoid creating a runtime CSV in cwd.
            rc = OfflineCli.run(new String[]
            {
                    "-d", source.toString(), "--runtime-report", runtime.toString(), "-rp",
                    "sdtmig-3-4", "-mp", "sdtmig/3-4", "--rules-dir", rulesDir.toString()
            }, cap.out, cap.err);
        }
        finally
        {
            if (savedUserDir != null)
            {
                System.setProperty("user.dir", savedUserDir);
            }
        }
        // Default name lives at cwd; the file may be in either old or new cwd depending on
        // jvm path resolution. Clean up both before asserting, so a failure leaves no stray
        // report behind — and the count is the proof the default name was used.
        int defaultNamed = deleteDefaultNamedReports(Path.of(savedUserDir))
                + deleteDefaultNamedReports(tempDir);
        assertEquals(0, rc, cap.errAsString());
        assertTrue(Files.exists(runtime), "runtime CSV must be written at --runtime-report");
        assertEquals(1, defaultNamed, "one CORE-Report-<timestamp>.json must be written");
    }


    /** Deletes every {@code CORE-Report-*.json} in {@code aDir}; returns how many it deleted. */
    private static int deleteDefaultNamedReports(Path aDir) throws java.io.IOException
    {
        int deleted = 0;
        try (var stream = Files.newDirectoryStream(aDir, "CORE-Report-*.json"))
        {
            for (Path p : stream)
            {
                if (Files.deleteIfExists(p))
                {
                    deleted++;
                }
            }
        }
        return deleted;
    }


    /** The {@code domain} of every {@code Dataset_Details} entry of a JSON report, in order. */
    private static List<String> reportedDomains(Path aReport) throws java.io.IOException
    {
        List<String> domains = new ArrayList<>();
        for (JsonNode dataset : MAPPER.readTree(aReport.toFile()).path("Dataset_Details"))
        {
            domains.add(dataset.path("domain").asText());
        }
        return domains;
    }


    @Test
    void run_emptyDataString_throwsIOException()
    {
        // -d "" → resolveLibrary throws "library path is empty". The blank value is deliberately
        // NOT caught by run()'s -d existence pre-check: Path.of("") is the CWD and does exist, so
        // the precise message stays resolveLibrary's. -o is named so the run's runtime CSV lands
        // under @TempDir; without it the CWD-relative default stem is used and the file is left
        // in the working directory (see WorkingDirectoryStaysCleanExtension).
        Captured cap = capture();
        java.io.IOException ex = assertThrows(java.io.IOException.class,
                () -> OfflineCli.run(new String[]
                {
                        "-d", "", "-o", tempDir.resolve("blank.json").toString()
                }, cap.out, cap.err));
        assertTrue(ex.getMessage().contains("empty"));
    }


    @Test
    void run_fileUriScheme_recognisedAsUri() throws Exception
    {
        // A -d "file://..." URI takes the URI branch in resolveLibrary (not the local-path
        // branch). The URI points at a real folder, so the resolution succeeds but later
        // phases short-circuit (empty library or no rules).
        Path source = makeSourceFolderWithCsv();
        String uri = source.toUri().toString();

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", uri, "-o", tempDir.resolve("uri.json").toString(), "--rules-dir",
                tempDir.toString()
        }, cap.out, cap.err);

        // No rules → exit 2 on the R3 selection guard ("No rule package selected").
        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("No rule package selected"), cap.errAsString());
    }


    @Test
    void run_rulesFile_loadsExtraPackage() throws Exception
    {
        // --rules-file points at an explicit JSON; the selection takes the explicit-file
        // branch. -mp is REQUIRED in this shape since V4 (a custom rules file declares no
        // CDISC Library standard); the full-form token resolves with or without a
        // configured pickle cache. Review R-17 gave this test its assertions — it used to
        // assert nothing, with a catch that swallowed the very failure it should have seen.
        Path source = makeSourceFolderWithCsv();
        Path rulesFile = tempDir.resolve("extra-rules.json");
        Files.writeString(rulesFile, """
                {
                  "rules": {
                    "x1": {
                      "id": "x1",
                      "Core": {"Id": "CORE-EXTRA-001"},
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        Path report = tempDir.resolve("rf.json");

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "--rules-file", rulesFile.toString(), "-mp", "sdtmig/3-4",
                "-o", report.toString(), "--rules-dir", tempDir.toString()
        }, cap.out, cap.err);

        // The explicit file's rule ran: rc 0 (USUBJID exists in the fixture) and the report
        // was written.
        assertEquals(0, rc, cap.errAsString());
        assertTrue(Files.exists(report), "JSON report must be written");
    }


    @Test
    void run_rulesFileOnly_withoutMetadataProducts_failsAtParseNamingMp() throws Exception
    {
        // V4 (review R-7): a --rules-file-only selection used to pass parsing and die deep
        // inside the run ("The selected rule package(s) declare no CDISC Library standard…").
        // It must fail at PARSE time, exit 2 like every usage error, naming -mp.
        Path source = makeSourceFolderWithCsv();
        Path rulesFile = tempDir.resolve("only-rules.json");
        Files.writeString(rulesFile, "{\"rules\":{}}");

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "--rules-file", rulesFile.toString(), "-o",
                tempDir.resolve("only.json").toString(), "--rules-dir", tempDir.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("-mp / --metadata-products"), cap.errAsString());
        assertTrue(cap.errAsString().contains("declares no CDISC Library standard"),
                cap.errAsString());
    }


    @Test
    void run_rulesFileMissing_logsWarning_continues() throws Exception
    {
        // --rules-file points at a non-existent JSON; loadRules warns and continues
        // → the selection ends empty, so the run fails with R3's actionable message.
        // ⚑ Plan 2 deliberately did NOT overturn warn-and-continue here: no ruling asked for it.
        // -mp is passed so the V4 parse-time guard (rules files without a package need -mp)
        // does not fire first — this test is about the R3 selection guard, not V4.
        Path source = makeSourceFolderWithCsv();

        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "--rules-file",
                tempDir.resolve("not-there.json").toString(), "-mp", "sdtmig/3-4", "-o",
                tempDir.resolve("rfm.json").toString(), "--rules-dir", tempDir.toString()
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("No rule package selected"), cap.errAsString());
    }

    // ⚑ run_templatesRule_loadsFromRulesDir lived here and was removed with its subject
    // (Fix #366): StudyValidationService.loadRules used to load a `rules-templates.json` found in
    // the rules directory unconditionally, ahead of everything the caller selected. That branch is
    // gone — a rule belonging to no package must not run — and this test manufactured the file
    // solely to drive it.


    @Test
    void run_acceptedIgnoredOption_withValue_skipsValue() throws Exception
    {
        // -p / --progress accepts a value; the parser must consume both the flag and its value
        // so subsequent options parse normally. -rr / --raw-report is a flag (no value).
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "--progress", "fancy", "--raw-report", "-h"
        }, cap.out, cap.err);
        assertEquals(0, rc);
        assertTrue(cap.outAsString().contains("Usage: CdiscValidate"));
    }


    // Each case provides one flag+value pair in front of the -h tail.
    // The parser must accept the flag without error; -h exits the run with code 0.
    // -dv 2-1 (define-version, only used at report write)
    // -uc tigcase (use-case)
    // -ct sdtmct-2024-09-27 (CT package)
    // -ft xpt (filetype)
    @ParameterizedTest(name = "flag {0} {1} parses")
    @org.junit.jupiter.params.provider.CsvSource(
    {
            "-dv, 2-1", "-uc, tigcase", "-ct, sdtmct-2024-09-27", "-ft, xpt"
    })
    void run_flagWithValue_parses(String flag, String value) throws Exception
    {
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                flag, value, "-h"
        }, cap.out, cap.err);
        assertEquals(0, rc);
    }


    @Test
    void run_cacheFlag_parses() throws Exception
    {
        // -ca / --cache configures a different ApiCache. Combine with -h to exit before any
        // network call.
        Path cacheDir = Files.createDirectory(tempDir.resolve("cache-" + System.nanoTime()));
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-ca", cacheDir.toString(), "-h"
        }, cap.out, cap.err);
        assertEquals(0, rc);
    }


    @Test
    void argsParse_defaultStderr_usesSystemErr()
    {
        // The single-arg overload defaults err to System.err. Exercise it by parsing an
        // arg array that needs no error reporting.
        // Using reflection avoids exposing the package-private signature in production code.
        try
        {
            Class<?> argsCls = Class.forName(CdiscValidate.class.getName() + "$Args");
            assertNotNull(argsCls, "Args inner class must exist");
            var parse = argsCls.getDeclaredMethod("parse", String[].class);
            parse.setAccessible(true);
            Object parsed = parse.invoke(null, (Object) new String[]
            {
                    "-h"
            });
            assertNotNull(parsed);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(e);
        }
    }


    @Test
    void run_ignoredOption_isFlagBranch() throws Exception
    {
        // --raw-report and --custom-standard are tagged as flags (no value). The isFlag()
        // branch in the ignored-options switch fires for them.
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "--raw-report", "--custom-standard", "-h"
        }, cap.out, cap.err);
        assertEquals(0, rc);
    }


    @Test
    void run_ignoredOption_eatsValueAtEnd_doesNotError() throws Exception
    {
        // -e / --encoding takes a value; passing it last with no following token still works
        // — the parser only consumes the value when there is one available.
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-e", "UTF-8", "-h"
        }, cap.out, cap.err);
        assertEquals(0, rc);
    }


    @Test
    void run_inlineEqualsThreads_parses() throws Exception
    {
        // -t=2 inline form takes the inline-value branch in the threads case.
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-t=2", "-h"
        }, cap.out, cap.err);
        assertEquals(0, rc);
    }

    // ⛔ run_endToEnd_withRulesFileAndStandardKindUnknown_noWarning was DELETED (review R-17):
    // it had no assertions, its catch swallowed the failure its invocation actually produces,
    // and the StandardKind.UNKNOWN branch it existed for became unreachable when Plan 2
    // removed -s/-v (no StandardKind reference remains in this module). Its invocation shape
    // (--rules-file only, no -mp) is now pinned by
    // run_rulesFileOnly_withoutMetadataProducts_failsAtParseNamingMp.


    @Test
    void run_endToEnd_explicitDefineXmlMissing_returnsError() throws Exception
    {
        // -d with valid library and a missing -dxp → distinct error branch.
        Path source = makeSourceFolderWithCsv();
        Captured cap = capture();
        int rc = OfflineCli.run(new String[]
        {
                "-d", source.toString(), "-dxp", tempDir.resolve("missing-define.xml").toString()
        }, cap.out, cap.err);
        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("define.xml not found"));
    }

    // ---------- fixtures ----------


    /**
     * Writes a {@code packages.json} into {@code rulesDir} mapping
     * {@code (CDISC, standard, version)} to {@code rulesFile}, so the CLI's manifest-driven
     * resolver (family defaults to CDISC) finds the conventional pack. The standard/version are
     * decoded from the {@code rules-<std>-<ver>.json} file name.
     */
    private static void writeCdiscManifest(Path rulesDir, Path rulesFile) throws java.io.IOException
    {
        String n = rulesFile.getFileName().toString();
        String base = n.substring("rules-".length(), n.length() - ".json".length());
        int dash = base.indexOf('-');
        new net.cumba.corej.core.RulePackageManifest("test",
                List.of(new net.cumba.corej.core.RulePackageManifest.Entry(n, "CDISC",
                        base.substring(0, dash), base.substring(dash + 1), 1, List.of())))
                                .writeTo(rulesDir);
    }


    private Path makeEmptyFolder() throws java.io.IOException
    {
        return Files.createDirectory(tempDir.resolve("empty-" + System.nanoTime()));
    }


    private Path makeSourceFolderWithCsv() throws java.io.IOException
    {
        Path folder = Files.createDirectory(tempDir.resolve("src-" + System.nanoTime()));
        try (var in = CdiscValidateTest.class.getResourceAsStream("/dm.csv"))
        {
            assertNotNull(in);
            Files.copy(in, folder.resolve("dm.csv"));
        }
        try (var in = CdiscValidateTest.class.getResourceAsStream("/vs.csv"))
        {
            assertNotNull(in);
            Files.copy(in, folder.resolve("vs.csv"));
        }
        return folder;
    }


    private Path makeFolderWith(String... csvNames) throws java.io.IOException
    {
        Path folder = Files.createDirectory(tempDir.resolve("dir-" + System.nanoTime()));
        for (String name : csvNames)
        {
            try (var in = CdiscValidateTest.class.getResourceAsStream("/" + name))
            {
                assertNotNull(in, "fixture not found: " + name);
                Files.copy(in, folder.resolve(name));
            }
        }
        return folder;
    }


    private static Captured capture()
    {
        return new Captured();
    }

    private static final class Captured
    {

        private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();

        private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        final PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);

        final PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

        String outAsString()
        {
            return outBuf.toString(StandardCharsets.UTF_8);
        }


        String errAsString()
        {
            return errBuf.toString(StandardCharsets.UTF_8);
        }
    }
}
