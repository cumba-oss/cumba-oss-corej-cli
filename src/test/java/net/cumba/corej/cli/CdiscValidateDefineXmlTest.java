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
import net.cumba.cdisc.library.api.model.products.Products;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import org.jspecify.annotations.Nullable;
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
class CdiscValidateDefineXmlTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A minimal generated Define-XML corpus, written per test into a temp directory.
     *
     * <p>
     * ⚑ These tests used to reach across modules for the real corpus
     * ({@code repoRoot + "lib/corej-define-conformance/rules"}). That corpus has since moved to
     * {@code corej-cdisc-rules} and is now generated rather than authored, but the cross-module
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
        // No CT provider is bound (correctness-first), so CT-gated rules SKIP rather than fire.
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


    /**
     * C3-02: pins the <b>call site</b>, not the method. {@code printLibraryBasis} itself is covered
     * by {@code CdiscValidateHelpersTest}, but every other end-to-end case here runs with no API
     * key, so the provider is {@code null}, the method returns at its first {@code if}, and
     * {@code removed call to printLibraryBasis} in {@code runDefineConformance} is
     * <b>equivalent</b> — the recorded kill of {@code CdiscValidate:1651} was false. This case
     * injects a provider that has already recorded a failed lookup, so deleting the call from
     * {@code runDefineConformance} makes exactly this assertion fail.
     *
     * <p>
     * The lookup is provoked here rather than left to the engine on purpose: whether a rule asks
     * the Library depends on the corpus, and this test is about the CLI reporting degradation it
     * was handed, not about which rules ask.
     * </p>
     */
    @Test
    void validateXml_degradedLibrary_saysSoOnStderr() throws Exception
    {
        CdiscLibraryBackedLibraryProvider provider = new CdiscLibraryBackedLibraryProvider(
                new CdiscLibraryBackedLibraryProvider.ProductSource()
                {

                    @Override
                    public @Nullable SdtmProduct fetch(String aProductId, String aVersion)
                        throws IOException
                    {
                        throw new IOException("offline");
                    }


                    @Override
                    public @Nullable Products catalog() throws IOException
                    {
                        throw new IOException("offline");
                    }
                });
        assertTrue(provider.datasetLabel("SDTMIG", "3.4", "DM").isEmpty(),
                "a failing ProductSource answers empty");
        assertEquals(java.util.List.of("sdtmig|3-4"), provider.degradedLookups(),
                "the provider handed to the CLI has one recorded failure");

        Path emptyData = Files.createDirectory(tempDir.resolve("data-degraded"));
        Captured cap = new Captured();
        try
        {
            OfflineCli.run(new String[]
            {
                    "-vx", "-d", emptyData.toString(), "-dxp", fixture().toString(), "-o",
                    tempDir.resolve("degraded.json").toString(), "--rules-dir", tempDir.toString(),
                    "--define-rules-dir", defineRules(tempDir), "--define-family", "CDISC"
            }, cap.out, cap.err, aCacheDir -> provider);
        }
        catch (IOException | RuntimeException _)
        {
            // Data-lane variance is out of scope; the conformance report is already written.
        }

        assertTrue(Files.exists(tempDir.resolve("degraded.define.json")),
                "the conformance report is written");
        String err = cap.errAsString();
        // ⚠ The COUNT is deliberately not asserted here. This case hands the live run a provider
        // it has already poked once, and whether a define rule asks the Library a second time
        // depends on the corpus — so pinning "1 … failed" would red for a corpus change that has
        // nothing to do with the call site under test. The exact count wording is pinned, against
        // a provider nobody else touches, by
        // printLibraryBasis_namesEveryFailedLookupAndSaysTheReportUnderReports.
        assertTrue(err.contains("Library basis:"),
                "runDefineConformance must report the degraded Library on stderr: " + err);
        assertTrue(err.contains("sdtmig|3-4"), "the failed lookup is named: " + err);
        assertTrue(err.contains("under-reports"),
                "the consequence is stated, not just the count: " + err);
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
        catch (java.io.IOException | RuntimeException _)
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
