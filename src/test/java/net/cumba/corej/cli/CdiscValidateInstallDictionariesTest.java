package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.metadata.dictionary.DictionaryDirectoryResolver;
import net.cumba.corej.core.metadata.dictionary.DictionaryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code PLAN-dictionary-seeder} Phase 6b — the CLI's dictionary surface, driven end to end through
 * {@code CdiscValidate.run(String[], PrintStream, PrintStream)} like
 * {@link CdiscValidateSeedCacheTest}: the {@code --install-dictionaries} maintenance mode, the
 * promoted-but-inert installer inputs, the per-run {@code --dictionaries-dir} params carry, the
 * version-select options, and the stderr {@code Dictionary basis} line (D13 surface 5).
 *
 * <p>
 * Install-mode cases use a synthetic UNII raw distribution (the smallest vendor layout) and no
 * network. The two end-of-run basis-line cases run a real validation and therefore need CDISC
 * Library metadata; like the other end-to-end CLI tests they tolerate an unreachable Library by
 * skipping their assertions rather than failing on infrastructure.
 * </p>
 */
class CdiscValidateInstallDictionariesTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    /**
     * States the ambient configuration these tests depend on instead of inheriting it silently: the
     * degraded-run case asserts that NO dictionary loads, which holds only while nothing in the
     * environment quietly supplies one. The conventional {@code ./dictionaries} is resolved against
     * the surefire working directory (this module), where none exists.
     */
    @BeforeAll
    static void noAmbientDictionaryStoreMayBeConfigured()
    {
        assertNull(System.getenv(DictionaryDirectoryResolver.ENV_DIR),
                "unset " + DictionaryDirectoryResolver.ENV_DIR + " before running these tests");
        assertNull(System.getProperty(DictionaryDirectoryResolver.SP_DIR),
                "clear -D" + DictionaryDirectoryResolver.SP_DIR + " before running these tests");
        assertFalse(Files.isDirectory(Path.of(DictionaryDirectoryResolver.DEFAULT_DIR)),
                "a ./dictionaries directory in the module would leak into the degraded-run case");
    }


    /**
     * One case ({@code installFallsBackToTheSystemPropertyTarget}) sets the
     * {@code corej.dictionariesDir} system property deliberately; clear it so no case inherits
     * another's store. The download-URL overrides are cleared for the same reason — and so that no
     * later case could ever fall back to the real authorities.
     */
    @AfterEach
    void clearTheDictionaryProperties()
    {
        System.clearProperty(DictionaryDirectoryResolver.SP_DIR);
        System.clearProperty("corej.dictionaries.evsBaseUrl");
        System.clearProperty("corej.dictionaries.uniiArchiveUrl");
    }

    /**
     * A loopback stand-in for the three credential-free authorities, wired in through the
     * URL-override system properties for the lifetime of one test: NCI EVS serving MED-RT (flat
     * file + DTS zip) and the SEND CT pair (space-named files), and precisionFDA serving the UNII
     * zip behind its 308 redirect. Unmatched paths get the EVS SPA shell — HTTP 200,
     * {@code text/html} — exactly like the real host.
     */
    private static final class StubAuthorities implements AutoCloseable
    {

        private static final byte[] SPA_SHELL = ("<!doctype html><html><head><title>NCI EVS"
                + "</title></head><body><div id=\"root\"></div></body></html>")
                        .getBytes(StandardCharsets.UTF_8);

        private final com.sun.net.httpserver.HttpServer server;

        private final Map<String, byte[]> files = new java.util.LinkedHashMap<>();

        StubAuthorities() throws IOException
        {
            files.put("/ftp1/MED-RT/MEDRT.txt",
                    "Cyclooxygenase Inhibitors [MoA]\tN0000000160\tMED-RT\n"
                            .getBytes(StandardCharsets.UTF_8));
            files.put("/ftp1/MED-RT/Core_MEDRT_DTS.zip",
                    zip("MEDRT_Release_Notes_20260706.txt", "2026.07.06\n"));
            files.put("/ftp1/CDISC/SEND/SEND Terminology.txt", String.join("\n",
                    "Code\tCodelist Code\tExtensible\tCodelist Name\tCDISC Submission Value",
                    "C3677\tC88025\tNo\tNeoplasm Type\tADENOMA, BENIGN",
                    "C3678\tC88025\tNo\tNeoplasm Type\tADENOMA, MALIGNANT", "")
                    .getBytes(StandardCharsets.UTF_8));
            files.put("/ftp1/CDISC/SEND/SEND Publication Date Stamp.txt",
                    "2026-03-27\n".getBytes(StandardCharsets.UTF_8));
            files.put("/gateway/UNII_Data.zip", zip("UNII_Records_4Aug2026.txt",
                    "UNII\tDISPLAY_NAME\nR16CO5Y76E\tASPIRIN\nH4L5F6D7S8\tSODIUM CHLORIDE\n"));

            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0),
                    0);
            server.createContext("/", this::dispatch);
            server.createContext("/uniisearch/", this::redirect);
            server.start();
            String base = "http://" + java.net.InetAddress.getLoopbackAddress().getHostAddress()
                    + ":" + server.getAddress().getPort();
            System.setProperty("corej.dictionaries.evsBaseUrl", base + "/ftp1/");
            System.setProperty("corej.dictionaries.uniiArchiveUrl",
                    base + "/uniisearch/archive/latest/UNII_Data.zip");
        }


        private void dispatch(com.sun.net.httpserver.HttpExchange aExchange) throws IOException
        {
            byte[] body = files.get(aExchange.getRequestURI().getPath());
            aExchange.getResponseHeaders().set("Content-Type",
                    body == null ? "text/html" : "application/octet-stream");
            byte[] payload = body == null ? SPA_SHELL : body;
            aExchange.sendResponseHeaders(200, payload.length);
            try (java.io.OutputStream out = aExchange.getResponseBody())
            {
                out.write(payload);
            }
        }


        private void redirect(com.sun.net.httpserver.HttpExchange aExchange) throws IOException
        {
            aExchange.getResponseHeaders().set("Location",
                    "http://" + java.net.InetAddress.getLoopbackAddress().getHostAddress() + ":"
                            + server.getAddress().getPort() + "/gateway/UNII_Data.zip");
            aExchange.sendResponseHeaders(308, -1);
            aExchange.close();
        }


        private static byte[] zip(String aEntryName, String aContent) throws IOException
        {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var out = new java.util.zip.ZipOutputStream(bytes))
            {
                out.putNextEntry(new java.util.zip.ZipEntry(aEntryName));
                out.write(aContent.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            return bytes.toByteArray();
        }


        @Override
        public void close()
        {
            server.stop(0);
            System.clearProperty("corej.dictionaries.evsBaseUrl");
            System.clearProperty("corej.dictionaries.uniiArchiveUrl");
        }
    }


    private record Run(int exitCode, String out, String err)
    {
    }

    private static Run invoke(String... aArgs) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8))
        {
            code = CdiscValidate.run(aArgs, o, e);
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }


    /** A minimal-but-valid UNII raw distribution; the version rides in the file name. */
    private Path uniiRawDir(String version) throws IOException
    {
        Path raw = Files.createDirectories(tempDir.resolve("raw-" + version));
        Files.writeString(raw.resolve("UNII_Records_" + version + ".txt"),
                String.join("\n", "UNII\tDISPLAY_NAME\tMF\tINCHIKEY",
                        "R16CO5Y76E\tASPIRIN\tC9H8O4\tBSYNRYMUTXBXSQ",
                        "H4L5F6D7S8\tSODIUM CHLORIDE\tClNa\t", "") + "\n",
                StandardCharsets.UTF_8);
        return raw;
    }


    private Path store()
    {
        return tempDir.resolve("store");
    }


    private Map<String, String> manifest() throws IOException
    {
        return MAPPER.readValue(Files.readAllBytes(store().resolve(DictionaryStore.MANIFEST)),
                MAPPER.getTypeFactory().constructMapType(java.util.LinkedHashMap.class,
                        String.class, String.class));
    }

    // ------------------------------------------------------------------
    // --install-dictionaries (standalone maintenance mode)
    // ------------------------------------------------------------------


    /**
     * The happy path: no validation inputs, the target store is CREATED (install mode is exempt
     * from the resolver's hard-error-on-missing rule), the document lands under
     * {@code <store>/<type>/<version>/}, and the selection manifest binds the version.
     */
    @Test
    void installsAUniiDistributionIntoAVersionedStore() throws Exception
    {
        Run run = invoke("--install-dictionaries", "--dictionaries-dir", store().toString(),
                "--unii", uniiRawDir("4Aug2026").toString());

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(
                Files.isRegularFile(
                        store().resolve("unii").resolve("4Aug2026").resolve("unii.json")),
                "the converted document must land in the versioned layout");
        assertEquals("4Aug2026", manifest().get("unii"), "the first install binds the selection");
        assertTrue(run.err().contains("1 installed"), run.err());
    }


    /** What the CLI installs is exactly what a validate run's store then binds. */
    @Test
    void whatTheCliInstallsIsWhatTheStoreLoads() throws Exception
    {
        invoke("--install-dictionaries", "--dictionaries-dir", store().toString(), "--unii",
                uniiRawDir("4Aug2026").toString());

        assertEquals("4Aug2026", DictionaryStore.load(store(), Map.of()).versionOf("unii"));
    }


    /** {@code --dry-run} converts and validates but writes nothing — not even the store root. */
    @Test
    void dryRunWritesNothingNotEvenTheStoreDirectory() throws Exception
    {
        Run run = invoke("--install-dictionaries", "--dry-run", "--dictionaries-dir",
                store().toString(), "--unii", uniiRawDir("4Aug2026").toString());

        assertEquals(0, run.exitCode(), run.err());
        assertFalse(Files.exists(store()), "a dry run must write nothing at all");
        assertTrue(run.err().contains("dry run"), run.err());
        assertTrue(run.err().contains("1 installed"),
                "the report still says what a real run would do: " + run.err());
    }


    /**
     * Phase 7a's single-command route: an install run naming no input downloads and installs the
     * three credential-free dictionaries — MED-RT, UNII, neoplasm — licences and provenance
     * included. The authorities are a loopback stub (via the URL-override system properties);
     * nothing here touches the public internet.
     */
    @Test
    void noInputInstallsTheThreeCredentialFreeDictionariesByDownload() throws Exception
    {
        try (StubAuthorities _ = new StubAuthorities())
        {
            Run run = invoke("--install-dictionaries", "--dictionaries-dir", store().toString());

            assertEquals(0, run.exitCode(), run.err());
            assertTrue(run.err().contains("3 installed"), run.err());
            assertTrue(Files.isRegularFile(
                    store().resolve("medrt").resolve("2026.07.06").resolve("medrt.json")));
            assertTrue(Files.isRegularFile(
                    store().resolve("unii").resolve("4Aug2026").resolve("unii.json")));
            assertTrue(
                    Files.isRegularFile(store().resolve("neoplasm").resolve("2026-03-27")
                            .resolve("neoplasm.json")),
                    "neoplasm must install with a real version from the fetched date stamp");
            assertTrue(
                    Files.isRegularFile(store().resolve("medrt").resolve("2026.07.06")
                            .resolve("LICENSES").resolve("MEDRT.txt")),
                    "each type's licence notice is written beside its data");
            String sources = Files.readString(store().resolve("SOURCES.md"));
            assertTrue(sources.contains("## medrt"), sources);
            assertTrue(sources.contains("## unii"), sources);
            assertTrue(sources.contains("## neoplasm"), sources);
            assertTrue(sources.contains("SHA-256 "), sources);
            assertTrue(run.err().contains("terms: "),
                    "the terms location must be surfaced at install time: " + run.err());
        }
    }


    /** A bare downloadable option fetches exactly that type, and no other. */
    @Test
    void aBareDownloadableOptionDownloadsOnlyThatType() throws Exception
    {
        try (StubAuthorities _ = new StubAuthorities())
        {
            Run run = invoke("--install-dictionaries", "--dictionaries-dir", store().toString(),
                    "--medrt");

            assertEquals(0, run.exitCode(), run.err());
            assertTrue(run.err().contains("1 installed"), run.err());
            assertTrue(Files.isDirectory(store().resolve("medrt")));
            assertFalse(Files.exists(store().resolve("unii")),
                    "a named input must not trigger the other downloads");
            assertFalse(Files.exists(store().resolve("neoplasm")));
        }
    }


    /**
     * A type that cannot be downloaded (LOINC needs a free account), requested with no path, is
     * reported as skipped with the reason — never treated as success-with-silence or an error.
     */
    @Test
    void anAccountGatedTypeRequestedWithNoPathIsReportedSkipped() throws Exception
    {
        Run run = invoke("--install-dictionaries", "--dictionaries-dir", store().toString(),
                "--loinc");

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.err().contains("skipped: loinc"), run.err());
        assertTrue(run.err().contains("pass --loinc <dir>"),
                "the message must name the fix: " + run.err());
        assertFalse(Files.exists(store().resolve("loinc")), "nothing may be written for it");
    }


    @Test
    void aMissingRawDirectoryFailsWithExitCodeOne() throws Exception
    {
        Run run = invoke("--install-dictionaries", "--dictionaries-dir", store().toString(),
                "--unii", tempDir.resolve("no-such-raw").toString());

        assertEquals(1, run.exitCode());
        assertTrue(run.err().contains("dictionary install failed"), run.err());
    }


    /**
     * ⛔ Batch B4 — one failed type must not abort the rest or discard the report. D11's best-effort
     * contract: MedDRA's typo'd path fails (recorded, exit 1), but LOINC — later in the fixed
     * install order — still installs, and the summary still prints so the operator is told what DID
     * land.
     */
    @Test
    void oneFailedTypeDoesNotAbortTheRestAndTheSummaryStillPrints() throws Exception
    {
        Path loincRaw = Files.createDirectories(tempDir.resolve("loinc-raw"));
        Files.writeString(loincRaw.resolve("Loinc.csv"),
                "\"LOINC_NUM\",\"LONG_COMMON_NAME\",\"VersionLastChanged\"\n"
                        + "\"1558-6\",\"Fasting glucose [Mass/volume] in Serum or Plasma\","
                        + "\"2.80\"\n",
                StandardCharsets.UTF_8);

        Run run = invoke("--install-dictionaries", "--dictionaries-dir", store().toString(),
                "--meddra", tempDir.resolve("no-such-meddra").toString(), "--loinc",
                loincRaw.toString());

        assertEquals(1, run.exitCode(), "a failure anywhere still fails the run: " + run.err());
        assertTrue(run.err().contains("meddra: dictionary install failed"),
                "the failed type is named: " + run.err());
        assertTrue(
                Files.isRegularFile(store().resolve("loinc").resolve("2.80").resolve("loinc.json")),
                "the type AFTER the failure must still have been attempted and installed");
        assertTrue(run.err().contains("1 installed"),
                "the summary must still print so the partial install is visible: " + run.err());
    }


    /**
     * Batch B11 — {@code --dry-run} universally means "write nothing"; a validate run writes a full
     * report, so the combination is rejected rather than silently ignored.
     */
    @Test
    void dryRunOutsideInstallModeIsAUsageError() throws Exception
    {
        Run run = invoke("--dry-run", "-d", "/tmp");

        assertEquals(2, run.exitCode(), run.err());
        assertTrue(run.err().contains("--dry-run"), run.err());
        assertTrue(run.err().contains("--install-dictionaries"),
                "the message names the mode the flag belongs to: " + run.err());
    }


    /** Batch B11 — the other install-mode modifiers are noted as ignored in a validate run. */
    @Test
    void installModifiersJoinTheIgnoreNoteInAValidateRun() throws Exception
    {
        Run run = invoke("--set-default", "--skip-installed", "-dxp",
                tempDir.resolve("no-define.xml").toString());

        assertEquals(2, run.exitCode(), "the run proceeds to (and fails) input validation");
        assertTrue(run.err().contains("Note: ignoring unsupported options"), run.err());
        assertTrue(run.err().contains("--set-default"), run.err());
        assertTrue(run.err().contains("--skip-installed"), run.err());
    }


    /**
     * Batch B11 — the version-<em>selection</em> options select nothing in install mode; saying so
     * keeps {@code --install-dictionaries --meddra-version 27.0} from reading as having bound a
     * version.
     */
    @Test
    void versionSelectOptionsAreNotedIgnoredInInstallMode() throws Exception
    {
        Run run = invoke("--install-dictionaries", "--dictionaries-dir", store().toString(),
                "--unii", uniiRawDir("4Aug2026").toString(), "--unii-version", "4Aug2026");

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.err().contains("Note: ignoring unsupported options"), run.err());
        assertTrue(run.err().contains("--unii-version"), run.err());
    }


    /** Batch B11 — a local maintenance mode must not be silently swallowed by {@code --remote}. */
    @Test
    void installDictionariesIsRejectedInRemoteMode() throws Exception
    {
        Run run = invoke("--remote", "http://localhost:1", "--install-dictionaries",
                "--dictionaries-dir", store().toString(), "--unii",
                uniiRawDir("4Aug2026").toString());

        assertEquals(2, run.exitCode(), run.err());
        assertTrue(run.err().contains("--install-dictionaries"), run.err());
        assertTrue(run.err().contains("--remote"), run.err());
        assertFalse(Files.exists(store()), "nothing may be installed on the rejected path");
    }


    /**
     * Manifest discipline through the CLI: a second release installs but does NOT re-point the
     * selection unless {@code --set-default} says so.
     */
    @Test
    void aSecondInstallKeepsTheSelectionUnlessSetDefault() throws Exception
    {
        invoke("--install-dictionaries", "--dictionaries-dir", store().toString(), "--unii",
                uniiRawDir("4Aug2026").toString());

        Run second = invoke("--install-dictionaries", "--dictionaries-dir", store().toString(),
                "--unii", uniiRawDir("5Sep2026").toString());
        assertEquals(0, second.exitCode(), second.err());
        assertEquals("4Aug2026", manifest().get("unii"), "the bound version is unchanged");
        assertTrue(second.err().contains("--set-default"),
                "the operator is told how to rebind: " + second.err());

        Run rebind = invoke("--install-dictionaries", "--set-default", "--dictionaries-dir",
                store().toString(), "--unii", uniiRawDir("5Sep2026").toString());
        assertEquals(0, rebind.exitCode(), rebind.err());
        assertEquals("5Sep2026", manifest().get("unii"), "--set-default rebinds deliberately");
    }


    /**
     * Phase 7b / D11 — {@code --skip-installed} is what a container entrypoint passes so its
     * boot-time auto-convert is idempotent: re-running the same install leaves an already-installed
     * type/version byte-for-byte alone and says so, instead of re-converting and rewriting it on
     * every start.
     */
    @Test
    void skipInstalledMakesAReinstallANoOp() throws Exception
    {
        invoke("--install-dictionaries", "--dictionaries-dir", store().toString(), "--unii",
                uniiRawDir("4Aug2026").toString());
        Path written = store().resolve("unii").resolve("4Aug2026").resolve("unii.json");
        java.nio.file.attribute.FileTime before = Files.getLastModifiedTime(written);

        Run again = invoke("--install-dictionaries", "--skip-installed", "--dictionaries-dir",
                store().toString(), "--unii", uniiRawDir("4Aug2026").toString());

        assertEquals(0, again.exitCode(), again.err());
        assertTrue(again.err().contains("already installed"),
                "the skip is reported: " + again.err());
        assertEquals(before, Files.getLastModifiedTime(written),
                "the stored document was not rewritten");
        // A DIFFERENT version is still installed normally — the skip is per type/version.
        Run other = invoke("--install-dictionaries", "--skip-installed", "--dictionaries-dir",
                store().toString(), "--unii", uniiRawDir("5Sep2026").toString());
        assertEquals(0, other.exitCode(), other.err());
        assertTrue(
                Files.isRegularFile(
                        store().resolve("unii").resolve("5Sep2026").resolve("unii.json")),
                "a version not yet in the store installs despite --skip-installed");
    }


    /**
     * D4 fallback: with no {@code --dictionaries-dir}, install mode targets the
     * {@code corej.dictionariesDir} system property (then the env var, then {@code ./dictionaries})
     * — resolved as a plain path, since install mode creates its target.
     */
    @Test
    void installFallsBackToTheSystemPropertyTarget() throws Exception
    {
        System.setProperty(DictionaryDirectoryResolver.SP_DIR, store().toString());

        Run run = invoke("--install-dictionaries", "--unii", uniiRawDir("4Aug2026").toString());

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(
                Files.isRegularFile(
                        store().resolve("unii").resolve("4Aug2026").resolve("unii.json")),
                "the property-named store must be created and written");
    }


    /**
     * Wiring smoke test: each installer-input option must route to its own converter. An empty
     * distribution can never install — the converter either reports "no version" (skipped, exit 0)
     * or rejects the layout (exit 1) — but both prove the dispatch, and a write must never happen.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "--{0} routes to its converter")
    @org.junit.jupiter.params.provider.ValueSource(strings =
    {
            "meddra", "whodrug", "loinc", "medrt", "unii", "snomed", "neoplasm"
    })
    void eachInstallerInputRoutesToItsConverter(String type) throws Exception
    {
        Path raw = Files.createDirectories(tempDir.resolve("empty-" + type));

        Run run = invoke("--install-dictionaries", "--dictionaries-dir", store().toString(),
                "--" + type, raw.toString());

        assertTrue(run.exitCode() == 0 || run.exitCode() == 1,
                "empty raw dir must end in a skip or a named failure, got " + run.exitCode() + ": "
                        + run.err());
        assertFalse(Files.exists(store().resolve(type)), "nothing may be written");
    }


    /** Two standalone maintenance modes in one invocation is a contradiction — usage error. */
    @Test
    void installAndSeedCacheAreMutuallyExclusive() throws Exception
    {
        Run run = invoke("--install-dictionaries", "--seed-cache", "--dictionaries-dir",
                store().toString(), "--unii", uniiRawDir("4Aug2026").toString());

        assertEquals(2, run.exitCode());
        assertTrue(run.err().contains("mutually exclusive"), run.err());
    }

    // ------------------------------------------------------------------
    // Validate mode: the promoted options stay inert, the store options wire through
    // ------------------------------------------------------------------


    /**
     * D1: outside install mode the promoted raw-distribution options keep their pre-promotion
     * behaviour bit-for-bit — accepted, and reported in the "ignoring unsupported options" note —
     * so Python-CLI invocations are unaffected. The run here stops at the define.xml existence
     * check (exit 2), proving the options changed nothing before it.
     */
    @Test
    void promotedInstallerInputsAreReportedIgnoredInAValidateRun() throws Exception
    {
        Run run = invoke("--meddra", "/some/meddra", "--snomed", "/some/snomed", "-dxp",
                tempDir.resolve("no-define.xml").toString());

        assertEquals(2, run.exitCode(), "the run proceeds to (and fails) input validation");
        assertTrue(run.err().contains("Note: ignoring unsupported options"), run.err());
        assertTrue(run.err().contains("--meddra"), run.err());
        assertTrue(run.err().contains("--snomed"), run.err());
        assertTrue(run.err().contains("define.xml not found"), run.err());
    }


    /** The legacy SNOMED compat trio stays in the sink, ignored as before. */
    @Test
    void theSnomedCompatOptionsStayIgnored() throws Exception
    {
        Run run = invoke("--snomed-version", "X", "--snomed-edition", "Y", "--snomed-url", "Z",
                "-dxp", tempDir.resolve("no-define.xml").toString());

        assertEquals(2, run.exitCode());
        assertTrue(run.err().contains("Note: ignoring unsupported options"), run.err());
        assertTrue(run.err().contains("--snomed-version"), run.err());
    }


    /**
     * ⛔ Batch B1 — {@code --dictionaries-dir} is carried per-run as
     * {@code StudyValidationParams#dictionariesDir()}, the resolver's TOP-precedence explicit
     * argument. It must NOT be smuggled through the {@code corej.dictionariesDir} system property:
     * the resolver ranks that below {@code COREJ_DICTIONARIES_DIR}, which every Docker image sets,
     * so a sysprop pass-through made the flag silently lose to the container default — install
     * wrote where you asked and validate read somewhere else. (The decisive
     * with-the-env-var-actually-SET case lives with the service:
     * {@code StudyValidationServiceDictionaryDirTest}.)
     */
    @Test
    void dictionariesDirIsCarriedAsAParamsFieldNotASystemProperty() throws Exception
    {
        CdiscValidate.Args a = CdiscValidate.Args.parse(new String[]
        {
                "-d", "/tmp", "--dictionaries-dir", store().toString()
        }, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertEquals(store().toString(), CdiscValidate
                .toParams(a, new net.cumba.datatable.manager.local.LocalDataTableManager(), _ ->
                {
                }).dictionariesDir(),
                "the flag must reach the service as the explicit params field");

        Run run = invoke("--dictionaries-dir", store().toString(), "-dxp",
                tempDir.resolve("no-define.xml").toString());
        assertEquals(2, run.exitCode(), "stops at input validation");
        assertNull(System.getProperty(DictionaryDirectoryResolver.SP_DIR),
                "the flag must no longer leak into the process-wide system property, where it "
                        + "would rank BELOW the env var");
    }


    /** The version-select options parse into the per-type request map, keyed by house type. */
    @Test
    void versionSelectOptionsFeedTheRequestMap()
    {
        CdiscValidate.Args a = CdiscValidate.Args.parse(new String[]
        {
                "-d", "/tmp", "--meddra-version", "27.0", "--whodrug-version", "SEP_2020",
                "--loinc-version", "2.80", "--medrt-version", "2026.07.06", "--unii-version",
                "4Aug2026", "--snomed-version-select", "2024-09-01", "--neoplasm-version",
                "2026-03-27"
        }, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertEquals(Map.of("meddra", "27.0", "whodrug", "SEP_2020", "loinc", "2.80", "medrt",
                "2026.07.06", "unii", "4Aug2026", "snomed", "2024-09-01", "neoplasm", "2026-03-27"),
                a.dictionaryVersions());
    }


    /** The Python-compat {@code --snomed-version} must NOT feed the request map. */
    @Test
    void theCompatSnomedVersionDoesNotSelectAVersion()
    {
        CdiscValidate.Args a = CdiscValidate.Args.parse(new String[]
        {
                "-d", "/tmp", "--snomed-version", "20240901"
        }, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        assertTrue(a.dictionaryVersions().isEmpty(), a.dictionaryVersions().toString());
    }

    // ------------------------------------------------------------------
    // D13 surface 5 — the stderr Dictionary basis line
    // ------------------------------------------------------------------


    /**
     * A run whose dictionary rule cannot be answered (nothing installed) must say so on stderr in
     * one line; a run with no dictionary rules must not. Both need a real validation, so an
     * unreachable CDISC Library skips the assertion (infrastructure, not behaviour) — the pattern
     * of the other end-to-end CLI tests.
     */
    @Test
    void aDegradedRunPrintsTheDictionaryBasisLineToStderr() throws Exception
    {
        Integer rc = runMinimalPackage(
                """
                        {
                          "rules": {
                            "u1": {
                              "id": "u1",
                              "Core": {"Id": "CORE-DICT-001"},
                              "Executability": "Fully Executable",
                              "Operations": [{"id": "$terms", "expression":
                                  "valid_external_dictionary_value(USUBJID, external_dictionary_type=\\"meddra\\", dictionary_term_type=\\"PT\\")"}],
                              "Check": {"all": [{"name": "$terms", "operator": "non_empty"}]}
                            }
                          }
                        }
                        """,
                basisErr);

        if (rc == null)
        {
            return; // Library unreachable — nothing to assert about run output.
        }
        assertEquals(0, rc.intValue(), basisErr.toString(StandardCharsets.UTF_8));
        String err = basisErr.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Dictionary basis: "), err);
        assertTrue(err.contains("0 of 1 dictionary rules in this run were answerable"), err);
    }


    @Test
    void aHealthyRunPrintsNoBasisLine() throws Exception
    {
        Integer rc = runMinimalPackage("""
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-TEST-001"},
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """, basisErr);

        if (rc == null)
        {
            return;
        }
        assertEquals(0, rc.intValue(), basisErr.toString(StandardCharsets.UTF_8));
        assertFalse(basisErr.toString(StandardCharsets.UTF_8).contains("Dictionary basis:"),
                "no dictionary rule in the run — the line must not appear");
    }

    private final ByteArrayOutputStream basisErr = new ByteArrayOutputStream();

    /**
     * Runs a one-rule sdtmig-3-4 package against the bundled dm/vs CSVs; returns the exit code, or
     * {@code null} when the CDISC Library was unreachable (network-dependent infrastructure).
     */
    private @org.jspecify.annotations.Nullable Integer runMinimalPackage(String rulesJson,
            ByteArrayOutputStream errBuf)
        throws Exception
    {
        Path source = Files.createDirectory(tempDir.resolve("src-" + System.nanoTime()));
        for (String csv : new String[]
        {
                "dm.csv", "vs.csv"
        })
        {
            try (var in = CdiscValidateInstallDictionariesTest.class.getResourceAsStream("/" + csv))
            {
                org.junit.jupiter.api.Assertions.assertNotNull(in);
                Files.copy(in, source.resolve(csv));
            }
        }
        Path rulesDir = Files.createDirectory(tempDir.resolve("rules-" + System.nanoTime()));
        Path rulesFile = rulesDir.resolve("rules-sdtmig-3-4.json");
        Files.writeString(rulesFile, rulesJson);
        new net.cumba.corej.core.RulePackageManifest("test",
                java.util.List.of(new net.cumba.corej.core.RulePackageManifest.Entry(
                        rulesFile.getFileName().toString(), "CDISC", "sdtmig", "3-4", 1)))
                                .writeTo(rulesDir);

        try (PrintStream o = new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8);
                PrintStream e = new PrintStream(errBuf, true, StandardCharsets.UTF_8))
        {
            return CdiscValidate.run(new String[]
            {
                    "-d", source.toString(), "-o", tempDir.resolve("rep.json").toString(), "-rp",
                    "sdtmig-3-4", "-mp", "sdtmig/3-4", "--rules-dir", rulesDir.toString()
            }, o, e);
        }
        catch (RuntimeException ex)
        {
            // Library API may be unreachable; skip rather than fail on infrastructure.
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (!msg.contains("cdisc") && !msg.contains("library") && !msg.contains("network")
                    && !msg.contains("timeout") && !msg.contains("connect"))
            {
                throw ex;
            }
            return null;
        }
    }

}
