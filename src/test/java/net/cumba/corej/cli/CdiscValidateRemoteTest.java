package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests for {@link CdiscValidate}'s {@code --remote} mode against a stub REST server.
 */
class CdiscValidateRemoteTest
{

    private static final String REPORT = "{\"Conformance_Details\":{\"Standard\":\"SDTMIG\"},\"Issue_Details\":[]}";

    private static final String REPORT_V2 = "{\"Report_Version\":\"2.0\",\"Findings\":[]}";

    private HttpServer server;

    private final AtomicInteger uploads = new AtomicInteger();

    private final AtomicReference<String> startBody = new AtomicReference<>();

    private final ConcurrentLinkedQueue<String> authHeaders = new ConcurrentLinkedQueue<>();

    private volatile String runStatus = "SUCCEEDED";

    /** The v1 report body the stub serves; a test may swap in a degraded one. */
    private volatile String reportBody = REPORT;

    @BeforeEach
    void startServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/", new StubHandler());
        server.start();
    }


    @AfterEach
    void stopServer()
    {
        if (server != null)
        {
            server.stop(0);
        }
        System.clearProperty(RemoteConfig.PROP_URL);
        System.clearProperty(RemoteConfig.PROP_TOKEN);
    }


    private String baseUrl()
    {
        return "http://localhost:" + server.getAddress().getPort();
    }


    private static int run(String... args) throws Exception
    {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);
        return CdiscValidate.run(args, sink, sink);
    }


    /** Runs and captures stderr so note / error text can be asserted. */
    private static RunResult runCapturingErr(String... args) throws Exception
    {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
        int code = CdiscValidate.run(args, out, err);
        return new RunResult(code, errBuf.toString(StandardCharsets.UTF_8));
    }

    private record RunResult(int code, String err)
    {
    }

    private static Path studyDir(Path tmp, String... files) throws IOException
    {
        return studyDirNamed(tmp, "study", files);
    }


    private static Path studyDirNamed(Path tmp, String name, String... files) throws IOException
    {
        Path dir = Files.createDirectory(tmp.resolve(name));
        for (String f : files)
        {
            Files.writeString(dir.resolve(f), "STUDYID,USUBJID\n1,1\n");
        }
        return dir;
    }


    @Test
    void uploadsRunsAndWritesReport(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv", "ae.csv");
        Path out = tmp.resolve("report.json");

        int code = run("--remote", baseUrl(), "-d", data.toString(), "-o", out.toString(), "-rp",
                "cdisc-sdtmig-3-4");

        assertEquals(0, code);
        assertEquals(2, uploads.get());
        assertTrue(Files.readString(out).contains("Conformance_Details"));
        // ⚑ Plan 2 (R5): 'standard' / 'version' left the wire; 'rulesPackages' is what now carries
        // the run's selection to the server, and the subject — the CLI forwards it — is the same.
        assertTrue(startBody.get().contains("\"rulesPackages\":[\"cdisc-sdtmig-3-4\"]"),
                startBody.get());
    }


    /**
     * Phase 9 batch B11 — D13 surface 5 holds in remote mode too: when the fetched report carries a
     * {@code Dictionary_Basis} (a degraded run), the same one-line stderr notice the local path
     * prints must appear; a healthy report (the default stub body) prints none.
     */
    @Test
    void remoteRunPrintsTheDictionaryBasisLineWhenTheReportCarriesOne(@TempDir Path tmp)
        throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");
        RunResult healthy = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-o",
                tmp.resolve("healthy.json").toString());
        assertEquals(0, healthy.code());
        assertFalse(healthy.err().contains("Dictionary basis:"),
                "a healthy remote report must not fabricate a basis line: " + healthy.err());

        reportBody = "{\"Conformance_Details\":{\"Standard\":\"SDTMIG\",\"Dictionary_Basis\":"
                + "\"external dictionaries degraded: 0 of 1 dictionary rules in this run were "
                + "answerable, the rest SKIPPED.\"},\"Issue_Details\":[]}";
        RunResult degraded = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-o",
                tmp.resolve("degraded.json").toString());
        assertEquals(0, degraded.code());
        assertTrue(
                degraded.err().contains("Dictionary basis: external dictionaries degraded: 0 of 1"),
                "the degradation must reach stderr in remote mode too: " + degraded.err());
    }


    @Test
    void json2FormatFetchesV2ReportAndWritesV2File(@TempDir Path tmp) throws Exception
    {
        // --output-format json2 in remote mode fetches GET /report-v2 and writes <base>.v2.json.
        Path data = studyDir(tmp, "dm.csv");
        Path out = tmp.resolve("report.json");

        int code = run("--remote", baseUrl(), "-d", data.toString(), "-of", "json2", "-o",
                out.toString());

        assertEquals(0, code);
        Path v2 = tmp.resolve("report.v2.json");
        assertTrue(Files.exists(v2), "json2 remote run must write <base>.v2.json");
        assertTrue(Files.readString(v2).contains("Report_Version"), Files.readString(v2));
        assertFalse(Files.exists(out),
                "v1 report.json must not be written when only json2 requested");
    }


    @Test
    void xlsxFormatRejectedInRemoteMode(@TempDir Path tmp) throws Exception
    {
        // xlsx has no remote fetch path; it must be rejected (exit 2) rather than writing JSON
        // bytes into a .xlsx file.
        Path data = studyDir(tmp, "dm.csv");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-of", "xlsx",
                "-o", tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("xlsx is not supported in --remote mode"), res.err());
        assertEquals(0, uploads.get(), "no upload may happen when the xlsx guard trips");
    }


    @Test
    void sendsBearerAuthHeader(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");
        Path out = tmp.resolve("report.json");

        int code = run("--remote", baseUrl(), "--remote-token", "tok", "-d", data.toString(), "-o",
                out.toString());

        assertEquals(0, code);
        assertFalse(authHeaders.isEmpty());
        assertTrue(authHeaders.stream().allMatch("Bearer tok"::equals), authHeaders.toString());
    }


    @Test
    void failedRunReturnsNonZeroAndWritesNoReport(@TempDir Path tmp) throws Exception
    {
        runStatus = "FAILED";
        Path data = studyDir(tmp, "dm.csv");
        Path out = tmp.resolve("report.json");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-o",
                out.toString());

        assertEquals(1, res.code());
        assertFalse(Files.exists(out));
        // The terminal-but-not-succeeded branch prints "Error: remote run FAILED: <message>".
        assertTrue(res.err().contains("Error: remote run FAILED"), res.err());
        // The stub status payload carries "message":"boom" — it must be appended after ": ".
        assertTrue(res.err().contains(": boom"),
                "failed-run message must be surfaced: " + res.err());
    }


    @Test
    void cancelledRunReturnsOneAndWritesNoReport(@TempDir Path tmp) throws Exception
    {
        runStatus = "CANCELLED";
        Path data = studyDir(tmp, "dm.csv");
        Path out = tmp.resolve("report.json");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-o",
                out.toString());

        assertEquals(1, res.code());
        assertFalse(Files.exists(out));
        assertTrue(res.err().contains("Error: remote run CANCELLED"), res.err());
    }


    @Test
    void uriDataRejectedInRemoteMode(@TempDir Path tmp) throws Exception
    {
        // --remote requires a local --data path; a URI is rejected with exit 2 before any upload.
        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", "file:///tmp/whatever", "-o",
                tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("--remote requires a local --data path"), res.err());
        assertEquals(0, uploads.get(), "no upload may happen when the URI guard trips");
    }


    @Test
    void missingDataPathReportsErrorAndExitsTwo(@TempDir Path tmp) throws Exception
    {
        Path missing = tmp.resolve("not-there");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", missing.toString(), "-o",
                tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("data path not found"), res.err());
    }


    @Test
    void controlledTerminologyAndCacheNoteWritten(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-ct",
                "sdtmct-2024", "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        assertTrue(res.err().contains("-ct / -ca are server-side configuration"), res.err());
    }


    /**
     * Fix #217. The old assertion pinned {@code "-rd / --reference-data is not supported"}; it is
     * <b>inverted</b> here rather than reworded, because any rewording that kept "is not supported"
     * would still have passed. {@code -rd} is now translated into a complement
     * {@code datasetFilter}: the reference members are uploaded and everything <i>not</i> named by
     * {@code -rd} is validated.
     */
    @Test
    void referenceDataIsTranslatedIntoAComplementDatasetFilter(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv", "ae.csv");
        Path ref = studyDirNamed(tmp, "refdir", "vs.csv");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-rd",
                ref.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        assertFalse(res.err().contains("is not supported"),
                "-rd must no longer be reported as unsupported: " + res.err());
        assertTrue(res.err().contains("translated into a datasetFilter complement"), res.err());
        // 2 study files + the reference member.
        assertEquals(3, uploads.get(), "the -rd member must be uploaded into the session");
        // The filter names the study members only; VS is absent, so it becomes a ReferenceDataset
        // server-side — visible to cross-dataset rules, never validated.
        assertTrue(startBody.get().contains("\"datasetFilter\":[\"ae.csv\",\"dm.csv\"]"),
                startBody.get());
        assertFalse(startBody.get().contains("vs.csv"), startBody.get());
        // The REST referenceDataFilenames field is deliberately NOT sent: naming a file there
        // resolves it inside the one flat data library and opens it as a *library*, which kills
        // the run for Dataset-JSON / CSV / Parquet.
        assertFalse(startBody.get().contains("referenceDataFilenames"), startBody.get());
    }


    /**
     * The central correctness question of Fix #217: {@code -ds} and {@code -rd} are not independent
     * lists. The filter is <b>{@code -ds} MINUS {@code -rd}</b> — a dataset named by both is a
     * reference, because letting {@code -ds} win would validate a reference dataset.
     */
    @Test
    void datasetFilterIsDatasetMinusReferenceData(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "adsl.csv", "adae.csv");
        Path ref = studyDirNamed(tmp, "refdir", "dm.csv");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-ds",
                "ADSL,ADAE,DM", "-rd", ref.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        // DM was named by BOTH -ds and -rd; -rd wins, so DM is dropped from the filter.
        assertTrue(startBody.get().contains("\"datasetFilter\":[\"ADSL\",\"ADAE\"]"),
                startBody.get());
    }


    /**
     * The subtraction is by the engine's comparison key (extension stripped, upper-cased), not by
     * raw string equality — otherwise {@code -ds dm} against {@code -rd .../dm.csv} would leave DM
     * a validation target.
     */
    @Test
    void referenceSubtractionIgnoresExtensionAndCase(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "adsl.csv");
        Path ref = studyDirNamed(tmp, "refdir", "DM.XPT");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-ds",
                "adsl.csv,dm", "-rd", ref.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        assertTrue(startBody.get().contains("\"datasetFilter\":[\"adsl.csv\"]"), startBody.get());
    }


    /**
     * An empty {@code datasetFilter} means "validate everything" server-side, so a complement that
     * subtracts everything must abort rather than be sent — sending {@code []} would validate the
     * reference data, the exact failure {@code -rd} exists to prevent.
     */
    @Test
    void referenceDataCoveringEveryTargetAbortsRatherThanSendingAnEmptyFilter(@TempDir Path tmp)
        throws Exception
    {
        // Reachable only through -ds: the derived branch subtracts reference members, but a
        // reference member colliding with a study member is dropped from the reference set
        // (targets win), so a -d-derived filter can never empty itself. Here -ds names only VS,
        // which lives solely in the reference library, so the complement is genuinely empty.
        Path data = studyDir(tmp, "dm.csv");
        Path ref = studyDirNamed(tmp, "refdir", "vs.csv");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-ds", "VS",
                "-rd", ref.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("leaving no validation targets"), res.err());
        assertEquals(0, uploads.get(), "the -rd guard must trip before any upload");
        assertNull(startBody.get(), "no check may be started with an empty complement");
    }


    /**
     * A reference member whose name collides with a study member is dropped rather than uploaded —
     * mirroring the engine's "domain already loaded from primary library" skip, and avoiding a
     * flat-namespace upload conflict.
     */
    @Test
    void referenceMemberCollidingWithAStudyMemberIsSkipped(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv", "ae.csv");
        Path ref = studyDirNamed(tmp, "refdir", "dm.xpt", "vs.csv");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-rd",
                ref.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        assertTrue(res.err().contains("reference dataset dm.xpt skipped"), res.err());
        // 2 study files + VS only; the colliding DM is not uploaded.
        assertEquals(3, uploads.get());
        // DM stays a target — it came from the study library, and targets win over references.
        assertTrue(startBody.get().contains("\"datasetFilter\":[\"ae.csv\",\"dm.csv\"]"),
                startBody.get());
    }


    @Test
    void referenceDataPathNotFoundAbortsBeforeAnyUpload(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-rd",
                tmp.resolve("no-such-dir").toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("reference data path not found"), res.err());
        assertEquals(0, uploads.get());
    }


    /**
     * {@code -rd} pointing straight at a define.xml is rejected remotely: uploads are staged flat
     * and the define's href closure is never resolved, so the referenced datasets would never
     * arrive and the reference library would be silently empty.
     */
    @Test
    void referenceDataNamingADefineXmlFileDirectlyIsRejected(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");
        Path define = tmp.resolve("define.xml");
        Files.writeString(define, "<ODM/>");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-rd",
                define.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("names a define.xml file directly"), res.err());
        assertEquals(0, uploads.get());
    }


    /** A define.xml inside a reference directory is skipped — it would collide with the study's. */
    @Test
    void referenceDirectoryDefineXmlIsSkippedNotUploaded(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "adsl.csv");
        Path ref = studyDirNamed(tmp, "refdir", "dm.csv");
        Files.writeString(ref.resolve("define.xml"), "<ODM/>");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-rd",
                ref.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        assertTrue(res.err().contains("is not uploaded in --remote mode"), res.err());
        // 1 study file + dm.csv; the reference define.xml is not uploaded.
        assertEquals(2, uploads.get());
    }


    /**
     * Without {@code -d} and without {@code -ds} there is nothing to derive a positive filter from,
     * so the translation is refused instead of silently validating the reference data. Reached
     * through the legacy {@code -dxp}-as-data-library fallback: {@code Args.parse} requires one of
     * {@code -d} / {@code -dxp} (<i>"One of --data or --define-xml-path is required"</i>), so a
     * {@code -dxp}-only run is the only way into this branch.
     */
    @Test
    void referenceDataWithoutDataOrDatasetFilterIsRefused(@TempDir Path tmp) throws Exception
    {
        Path ref = studyDirNamed(tmp, "refdir", "dm.csv");
        Path define = tmp.resolve("define.xml");
        Files.writeString(define, "<ODM/>");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-dxp", define.toString(), "-rd",
                ref.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("needs -d / --data"), res.err());
        assertEquals(0, uploads.get());
    }


    /**
     * The mirror of the empty-complement guard: when {@code -rd} names a domain the study library
     * <i>also</i> supplies, the study copy stays a validation target. This matches local mode
     * exactly — {@code loadReferenceLibrary} skips a reference domain that is "already loaded from
     * primary library" — so the reference member is dropped from the subtraction rather than
     * knocking the study's own dataset out of the filter.
     */
    @Test
    void aStudyMemberNamedByBothDatasetAndReferenceDataStaysATarget(@TempDir Path tmp)
        throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");
        Path ref = studyDirNamed(tmp, "refdir", "dm.xpt");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-ds", "DM",
                "-rd", ref.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        assertTrue(res.err().contains("reference dataset dm.xpt skipped"), res.err());
        assertEquals(1, uploads.get(), "the colliding reference member must not be uploaded");
        assertTrue(startBody.get().contains("\"datasetFilter\":[\"DM\"]"), startBody.get());
    }


    /**
     * A {@code -d} that does not resolve must read as a bad path, not as *"the -rd translation
     * needs -d"* — the translation guard would otherwise swallow a plain typo, because a missing
     * directory enumerates to zero study members.
     */
    @Test
    void referenceDataWithAMissingDataPathReportsThePathNotTheTranslation(@TempDir Path tmp)
        throws Exception
    {
        Path ref = studyDirNamed(tmp, "refdir", "dm.csv");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d",
                tmp.resolve("not-there").toString(), "-rd", ref.toString(), "-o",
                tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("data path not found"), res.err());
        assertFalse(res.err().contains("needs -d / --data"), res.err());
        assertEquals(0, uploads.get());
    }


    /** A single-file {@code -d} derives a one-entry filter naming that file. */
    @Test
    void singleFileDataWithReferenceDataDerivesThatOneMember(@TempDir Path tmp) throws Exception
    {
        Path single = tmp.resolve("adsl.csv");
        Files.writeString(single, "STUDYID,USUBJID\n1,1\n");
        Path ref = studyDirNamed(tmp, "refdir", "dm.csv");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", single.toString(), "-rd",
                ref.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        assertEquals(2, uploads.get());
        assertTrue(startBody.get().contains("\"datasetFilter\":[\"adsl.csv\"]"), startBody.get());
    }


    /** A single-file {@code -rd} (not a directory) uploads that one file as a reference member. */
    @Test
    void singleFileReferenceDataUploadsAndIsExcludedFromTheFilter(@TempDir Path tmp)
        throws Exception
    {
        Path data = studyDir(tmp, "adsl.csv");
        Path refFile = tmp.resolve("dm.csv");
        Files.writeString(refFile, "STUDYID,USUBJID\n1,1\n");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-rd",
                refFile.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        assertEquals(2, uploads.get());
        assertTrue(startBody.get().contains("\"datasetFilter\":[\"adsl.csv\"]"), startBody.get());
    }


    /** Without {@code -rd}, {@code -ds} is still sent verbatim — the merge must not disturb it. */
    @Test
    void datasetFilterWithoutReferenceDataIsUnchanged(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv", "ae.csv");

        int code = run("--remote", baseUrl(), "-d", data.toString(), "-ds", "DM", "-o",
                tmp.resolve("o.json").toString());

        assertEquals(0, code);
        assertTrue(startBody.get().contains("\"datasetFilter\":[\"DM\"]"), startBody.get());
    }


    /**
     * Without {@code -ds} and without {@code -rd} no filter is sent at all (every member a target).
     */
    @Test
    void noDatasetFilterKeyWhenNeitherDatasetNorReferenceDataGiven(@TempDir Path tmp)
        throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");

        int code = run("--remote", baseUrl(), "-d", data.toString(), "-o",
                tmp.resolve("o.json").toString());

        assertEquals(0, code);
        assertFalse(startBody.get().contains("datasetFilter"), startBody.get());
    }


    @Test
    void multipleRulesFilesNoteWritten(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");
        Path r1 = tmp.resolve("r1.json");
        Path r2 = tmp.resolve("r2.json");
        Files.writeString(r1, "{}");
        Files.writeString(r2, "{}");

        // -mp satisfies the V4 parse-time guard (rules files without a package need -mp);
        // in remote mode the token is forwarded verbatim, never resolved locally.
        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(),
                "--rules-file", r1.toString(), "--rules-file", r2.toString(), "-mp", "sdtmig/3-4",
                "-o", tmp.resolve("o.json").toString());

        assertEquals(0, res.code());
        assertTrue(res.err().contains("uploads only the first --rules-file"), res.err());
    }


    @Test
    void defineXmlMissingAbortsBeforeStartCheck(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");
        Path missingDefine = tmp.resolve("define.xml");

        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(), "-dxp",
                missingDefine.toString(), "-o", tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("define.xml not found"), res.err());
        assertNull(startBody.get(), "a missing define.xml must abort before startCheck");
    }


    @Test
    void rulesFileMissingAbortsBeforeStartCheck(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");
        Path missingRules = tmp.resolve("rules.json");

        // -mp satisfies the V4 parse-time guard so the missing-file check is what fires.
        RunResult res = runCapturingErr("--remote", baseUrl(), "-d", data.toString(),
                "--rules-file", missingRules.toString(), "-mp", "sdtmig/3-4", "-o",
                tmp.resolve("o.json").toString());

        assertEquals(2, res.code());
        assertTrue(res.err().contains("rules file not found"), res.err());
        assertNull(startBody.get(), "a missing rules file must abort before startCheck");
    }


    @Test
    void singleFileDataUploadsExactlyOnce(@TempDir Path tmp) throws Exception
    {
        // -d pointing at a single file (not a directory) takes the else-branch of uploadData.
        Path single = tmp.resolve("dm.csv");
        Files.writeString(single, "STUDYID,USUBJID\n1,1\n");

        int code = run("--remote", baseUrl(), "-d", single.toString(), "-o",
                tmp.resolve("o.json").toString());

        assertEquals(0, code);
        assertEquals(1, uploads.get(), "a single data file must upload exactly once");
    }


    @Test
    void definePresentIsUploadedAndReferencedInRequest(@TempDir Path tmp) throws Exception
    {
        Path data = studyDir(tmp, "dm.csv");
        Path define = tmp.resolve("define.xml");
        Files.writeString(define, "<ODM/>");

        int code = run("--remote", baseUrl(), "-d", data.toString(), "-dxp", define.toString(),
                "-o", tmp.resolve("o.json").toString());

        assertEquals(0, code);
        // 1 data file + 1 define.xml.
        assertEquals(2, uploads.get());
        assertTrue(startBody.get().contains("\"defineXmlFilename\":\"define.xml\""),
                startBody.get());
    }


    @Test
    void remoteUrlResolvedFromSystemProperty(@TempDir Path tmp) throws Exception
    {
        System.setProperty(RemoteConfig.PROP_URL, baseUrl());
        Path data = studyDir(tmp, "dm.csv");
        Path out = tmp.resolve("report.json");

        int code = run("-d", data.toString(), "-o", out.toString());

        assertEquals(0, code);
        assertTrue(Files.exists(out));
    }

    /** Stub of the corej REST endpoints the client drives. */
    private final class StubHandler implements HttpHandler
    {

        @Override
        public void handle(HttpExchange ex) throws IOException
        {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null)
            {
                authHeaders.add(auth);
            }
            byte[] reqBody = ex.getRequestBody().readAllBytes();

            if ("POST".equals(method) && "/api/sessions".equals(path))
            {
                respond(ex, 201, "{\"sessionId\":\"s1\"}");
            }
            else if ("POST".equals(method) && path.endsWith("/files"))
            {
                uploads.incrementAndGet();
                respond(ex, 201, "{\"sessionId\":\"s1\",\"filename\":\"f\",\"size\":1}");
            }
            else if ("POST".equals(method) && path.endsWith("/checks"))
            {
                startBody.set(new String(reqBody, StandardCharsets.UTF_8));
                respond(ex, 201, "{\"checkRunId\":\"r1\"}");
            }
            else if ("GET".equals(method) && path.endsWith("/status"))
            {
                respond(ex, 200, "{\"status\":\"" + runStatus
                        + "\",\"findingCount\":2,\"rulesExecuted\":5,\"message\":\"boom\"}");
            }
            else if ("GET".equals(method) && path.endsWith("/report-v2"))
            {
                respond(ex, 200, REPORT_V2);
            }
            else if ("GET".equals(method) && path.endsWith("/report"))
            {
                respond(ex, 200, reportBody);
            }
            else
            {
                respond(ex, 404, "{}");
            }
        }


        private void respond(HttpExchange ex, int code, String body) throws IOException
        {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody())
            {
                os.write(b);
            }
        }
    }
}
