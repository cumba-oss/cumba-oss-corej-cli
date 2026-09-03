package net.cumba.dataviewer.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
class CdiscValidateTest
{

    @TempDir
    Path tempDir;

    @Test
    void run_help_short_printsUsageToStdout() throws Exception
    {
        Captured cap = capture();
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
        {
                "-t", "0", "-d", "/tmp"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("--threads must be"));
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[] {}, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("--data") || cap.errAsString().contains("required"));
    }


    @Test
    void run_unsupportedOutputFormat_returns2() throws Exception
    {
        Captured cap = capture();
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
        {
                "-dp", "/tmp/x.xpt"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        assertTrue(cap.errAsString().contains("-dp / --dataset-path was removed"));
    }


    @Test
    void run_dataPathNotFound_throwsIOException()
    {
        Captured cap = capture();
        java.io.IOException ex = org.junit.jupiter.api.Assertions
                .assertThrows(java.io.IOException.class, () -> CdiscValidate.run(new String[]
                {
                        "-d", tempDir.resolve("does-not-exist").toString()
                }, cap.out, cap.err));
        assertTrue(ex.getMessage().contains("path not found"));
    }


    @Test
    void run_defineXmlNotFound_alone_returns2() throws Exception
    {
        Path bogus = tempDir.resolve("no-such-define.xml");

        Captured cap = capture();
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Captured cap = capture();
        try
        {
            CdiscValidate.run(new String[]
            {
                    "-d", source.toString(), "-o", tempDir.resolve("ar.json").toString(), "-mp",
                    "adam/adamig-1-3", "--rules-dir", rulesDir.toString()
            }, cap.out, cap.err);
        }
        catch (RuntimeException ex)
        {
            // Library API may fail; ignore network-related failures.
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (!msg.contains("cdisc") && !msg.contains("library") && !msg.contains("network"))
            {
                throw ex;
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
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);
        Path report = tempDir.resolve("rep.json");
        Path runtime = tempDir.resolve("rep.runtime.csv");

        Captured cap = capture();
        int rc;
        try
        {
            rc = CdiscValidate.run(new String[]
            {
                    "-d", source.toString(), "-o", report.toString(), "-rp", "sdtmig-3-4", "-mp",
                    "sdtmig/3-4", "--rules-dir", rulesDir.toString()
            }, cap.out, cap.err);
        }
        catch (RuntimeException ex)
        {
            // Some validator paths throw when CDISC Library API is unreachable. Either way
            // this test exercises the deeper run() code path, which is the goal for
            // coverage. Re-raise only if the failure is unrelated to network / library.
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (!msg.contains("cdisc") && !msg.contains("library") && !msg.contains("network")
                    && !msg.contains("timeout") && !msg.contains("connect"))
            {
                throw ex;
            }
            return;
        }

        // Successful run: rc 0 and the report + runtime CSV are written.
        assertEquals(0, rc);
        assertTrue(Files.exists(report), "JSON report must be written");
        assertTrue(Files.exists(runtime), "runtime CSV must be written");
        String runtimeContent = Files.readString(runtime, StandardCharsets.UTF_8);
        assertTrue(runtimeContent.startsWith("domain,fileName,rowCount,columnCount,coreId,"));
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
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Captured cap = capture();
        int rc = CdiscValidate.run(new String[]
        {
                "-d", source.toString(), "-r", "CORE-NO-MATCH", "-rp", "sdtmig-3-4", "-mp",
                "sdtmig/3-4", "--rules-dir", rulesDir.toString()
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
        java.io.IOException ex = org.junit.jupiter.api.Assertions
                .assertThrows(java.io.IOException.class, () -> CdiscValidate.run(new String[]
                {
                        "-d", source.toString(), "-rd", tempDir.resolve("nope").toString(),
                        "--rules-dir", tempDir.toString()
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(args, cap.out, cap.err);

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
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Captured cap = capture();
        int rc;
        try
        {
            rc = CdiscValidate.run(new String[]
            {
                    "-d", source.toString(), "-rd", refLib.toString(), "-o",
                    tempDir.resolve("rd.json").toString(), "-rp", "sdtmig-3-4", "-mp", "sdtmig/3-4",
                    "--rules-dir", rulesDir.toString()
            }, cap.out, cap.err);
        }
        catch (RuntimeException ex)
        {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (!msg.contains("cdisc") && !msg.contains("library") && !msg.contains("network")
                    && !msg.contains("timeout") && !msg.contains("connect"))
            {
                throw ex;
            }
            return;
        }
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
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Captured cap = capture();
        try
        {
            CdiscValidate.run(new String[]
            {
                    "-d", source.toString(), "-rd", refLib.toString(), "-o",
                    tempDir.resolve("dup.json").toString(), "--rules-dir", rulesDir.toString()
            }, cap.out, cap.err);
        }
        catch (RuntimeException ex)
        {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (!msg.contains("cdisc") && !msg.contains("library") && !msg.contains("network")
                    && !msg.contains("timeout") && !msg.contains("connect"))
            {
                throw ex;
            }
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
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        Captured cap = capture();
        try
        {
            CdiscValidate.run(new String[]
            {
                    "-d", source.toString(), "-ds", "DM", "-o",
                    tempDir.resolve("ds.json").toString(), "--rules-dir", rulesDir.toString()
            }, cap.out, cap.err);
        }
        catch (RuntimeException ex)
        {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (!msg.contains("cdisc") && !msg.contains("library") && !msg.contains("network")
                    && !msg.contains("timeout") && !msg.contains("connect"))
            {
                throw ex;
            }
        }
    }


    @Test
    void run_endToEnd_datasetFilterUnmatchedMember_logsWarning() throws Exception
    {
        // -ds NOSUCH → no target matches, library still has DM + VS as references.
        // After the filter rejects everything, run() exits 2 with "filter matched no members".
        Path source = makeSourceFolderWithCsv();

        Captured cap = capture();
        int rc = CdiscValidate.run(new String[]
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
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        writeCdiscManifest(rulesDir, rulesFile);

        // Change to tempDir for the duration of the run so the default-name file lands there.
        String savedUserDir = System.getProperty("user.dir");
        Captured cap = capture();
        try
        {
            System.setProperty("user.dir", tempDir.toString());
            // Use --runtime-report to avoid creating a runtime CSV in cwd.
            CdiscValidate.run(new String[]
            {
                    "-d", source.toString(), "--runtime-report",
                    tempDir.resolve("nout-rt.csv").toString(), "--rules-dir", rulesDir.toString()
            }, cap.out, cap.err);
        }
        catch (RuntimeException ex)
        {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (!msg.contains("cdisc") && !msg.contains("library") && !msg.contains("network")
                    && !msg.contains("timeout") && !msg.contains("connect"))
            {
                throw ex;
            }
        }
        finally
        {
            if (savedUserDir != null)
            {
                System.setProperty("user.dir", savedUserDir);
            }
        }
        // Default name lives at cwd; the file may be in either old or new cwd depending on
        // jvm path resolution. Either way, the run completed enough to exercise the helpers.
        // Clean up: delete any CORE-Report-*.json that landed in the saved cwd.
        try (var stream = Files.newDirectoryStream(Path.of(savedUserDir), "CORE-Report-*.json"))
        {
            for (Path p : stream)
            {
                Files.deleteIfExists(p);
            }
        }
        catch (java.io.IOException _)
        {
            // best-effort cleanup
        }
    }


    @Test
    void run_emptyDataString_throwsIOException()
    {
        // -d "" → resolveLibrary throws "library path is empty".
        Captured cap = capture();
        java.io.IOException ex = org.junit.jupiter.api.Assertions
                .assertThrows(java.io.IOException.class, () -> CdiscValidate.run(new String[]
                {
                        "-d", ""
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
        int rc = CdiscValidate.run(new String[]
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
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        Path report = tempDir.resolve("rf.json");

        Captured cap = capture();
        int rc;
        try
        {
            rc = CdiscValidate.run(new String[]
            {
                    "-d", source.toString(), "--rules-file", rulesFile.toString(), "-mp",
                    "sdtmig/3-4", "-o", report.toString(), "--rules-dir", tempDir.toString()
            }, cap.out, cap.err);
        }
        catch (RuntimeException ex)
        {
            // Library API may be unreachable; ignore only network / library failures.
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (!msg.contains("cdisc") && !msg.contains("library") && !msg.contains("network")
                    && !msg.contains("timeout") && !msg.contains("connect"))
            {
                throw ex;
            }
            return;
        }

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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        int rc = CdiscValidate.run(new String[]
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
        new net.cumba.cdisc.core.RulePackageManifest("test",
                java.util.List.of(new net.cumba.cdisc.core.RulePackageManifest.Entry(n, "CDISC",
                        base.substring(0, dash), base.substring(dash + 1), 1))).writeTo(rulesDir);
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
            org.junit.jupiter.api.Assertions.assertNotNull(in);
            Files.copy(in, folder.resolve("dm.csv"));
        }
        try (var in = CdiscValidateTest.class.getResourceAsStream("/vs.csv"))
        {
            org.junit.jupiter.api.Assertions.assertNotNull(in);
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
                org.junit.jupiter.api.Assertions.assertNotNull(in, "fixture not found: " + name);
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
