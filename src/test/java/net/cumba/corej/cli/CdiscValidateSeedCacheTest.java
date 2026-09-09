package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.razorvine.pickle.Pickler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage of the CLI's {@code --seed-cache} maintenance mode.
 *
 * <p>
 * These drive {@code CdiscValidate.run(String[], PrintStream, PrintStream)} — the same entry point
 * {@code main} uses — so option wiring, mutual exclusion, target-directory resolution and exit
 * codes are all exercised together. Seeding runs from a local pickle directory, so no network is
 * involved.
 * </p>
 */
@org.junit.jupiter.api.extension.ExtendWith(WorkingDirectoryStaysCleanExtension.class)
class CdiscValidateSeedCacheTest
{

    /**
     * States the ambient configuration these tests depend on instead of inheriting it silently.
     *
     * <p>
     * {@code CdiscValidate.seedCache} takes its base URL from
     * {@link CdiscLibraryClient#getApiUrl()}, and the seeder turns that URL's path into the cache
     * file-name prefix — so the {@code api_…} names asserted below hold only for the default base
     * URL. {@code getApiUrl} reads the <b>environment</b> variable {@code CDISC_API_URL} first, and
     * a JVM cannot unset its own environment, so this cannot be neutralised: it can only be
     * declared. A developer who exports {@code CDISC_API_URL} now gets this message rather than a
     * pile of file-not-found assertions with no stated cause.
     * </p>
     *
     * <p>
     * The sibling variable {@code CDISC_API_CACHE} cannot leak in: every case below passes
     * {@code -ca}, which wins over the environment default.
     * </p>
     */
    @BeforeAll
    static void theAmbientLibraryUrlMustBeTheDefault()
    {
        assertEquals(CdiscLibraryClient.DEFAULT_BASE_URL, CdiscLibraryClient.getApiUrl(),
                "these tests assert cache file names derived from the default CDISC Library base "
                        + "URL; unset CDISC_API_URL (env) / cdisc.library.api.url (system "
                        + "property) before running them");
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
            code = OfflineCli.run(aArgs, o, e);
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }


    /** A minimal but realistic pickle cache: one standards entry and one CT package. */
    private static Path pickleDir(Path aRoot) throws IOException
    {
        Path dir = Files.createDirectories(aRoot.resolve("pkl"));

        Map<String, Object> self = new LinkedHashMap<>();
        self.put("href", "/mdr/sdtmig/3-4");
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", self);
        Map<String, Object> ig = new LinkedHashMap<>();
        ig.put("_links", links);
        ig.put("name", "SDTMIG");
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("standards/sdtmig/3-4", ig);
        Files.write(dir.resolve("standards_details.pkl"), new Pickler().dumps(standards));

        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("package", "sdtmct-2024-09-27");
        ct.put("codelists", List.of());
        Files.write(dir.resolve("sdtmct-2024-09-27.pkl"), new Pickler().dumps(ct));
        return dir;
    }


    @Test
    void seedsFromALocalDirectoryIntoTheGivenCacheDir(@TempDir Path root) throws Exception
    {
        Path cache = root.resolve("cache");

        Run run = invoke("--seed-cache-from-dir", pickleDir(root).toString(), "-ca",
                cache.toString());

        assertEquals(0, run.exitCode(), run.err());
        // F-cli-05: the summary reaches the OPERATOR's stream, not only the logger. Until now a
        // seeding step said nothing on stderr whatever it did or failed to do.
        assertTrue(run.err().contains("Cache seeded at " + cache + ":"), run.err());
        assertTrue(Files.exists(cache.resolve("api_mdr_sdtmig_3-4%3Fexpand%3Dtrue.json.gz")));
        assertTrue(Files.exists(
                cache.resolve("api_mdr_ct_packages_sdtmct-2024-09-27%3Fexpand%3Dtrue.json.gz")));
        assertTrue(Files.exists(cache.resolve("api_mdr_ct_packages.json.gz")));
    }


    /** Seeding is standalone: it must not demand -d / -dxp, which normal runs require. */
    @Test
    void seedingDoesNotRequireValidationInputs(@TempDir Path root) throws Exception
    {
        Run run = invoke("--seed-cache-from-dir", pickleDir(root).toString(), "-ca",
                root.resolve("cache").toString());

        assertEquals(0, run.exitCode(), run.err());
        assertFalse(run.err().contains("provide -d"), run.err());
    }


    @Test
    void dryRunWritesNothing(@TempDir Path root) throws Exception
    {
        Path cache = root.resolve("cache");

        Run run = invoke("--seed-cache-from-dir", pickleDir(root).toString(), "-ca",
                cache.toString(), "--seed-dry-run");

        assertEquals(0, run.exitCode(), run.err());
        assertFalse(Files.exists(cache.resolve("api_mdr_sdtmig_3-4%3Fexpand%3Dtrue.json.gz")));
    }


    @Test
    void mutuallyExclusiveSourcesAreAUsageError(@TempDir Path root) throws Exception
    {
        Run run = invoke("--seed-cache", "https://example.org/repo", "--seed-cache-from-dir",
                pickleDir(root).toString(), "-ca", root.resolve("cache").toString());

        assertEquals(2, run.exitCode());
        assertTrue(run.err().contains("mutually exclusive"), run.err());
    }


    @Test
    void aMissingPickleDirectoryFailsWithExitCodeOne(@TempDir Path root) throws Exception
    {
        Run run = invoke("--seed-cache-from-dir", root.resolve("absent").toString(), "-ca",
                root.resolve("cache").toString());

        assertEquals(1, run.exitCode());
        assertTrue(run.err().contains("cache seeding failed"), run.err());
    }


    /** A second run skips what is already there unless --seed-overwrite is given. */
    @Test
    void rerunIsIdempotentAndOverwriteForcesRewrite(@TempDir Path root) throws Exception
    {
        Path pkl = pickleDir(root);
        Path cache = root.resolve("cache");
        assertEquals(0, invoke("--seed-cache-from-dir", pkl.toString(), "-ca", cache.toString())
                .exitCode());
        Path entry = cache.resolve("api_mdr_sdtmig_3-4%3Fexpand%3Dtrue.json.gz");
        long firstModified = Files.getLastModifiedTime(entry).toMillis();
        Files.setLastModifiedTime(entry, java.nio.file.attribute.FileTime.fromMillis(0));

        Run reseed = invoke("--seed-cache-from-dir", pkl.toString(), "-ca", cache.toString());
        assertEquals(0, reseed.exitCode(), reseed.err());
        assertEquals(0, Files.getLastModifiedTime(entry).toMillis(),
                "a skip-existing run must not rewrite the entry");
        // C2-07: the per-skip lines are a surface of their own — the loop that prints them
        // survived every mutant because nothing asserted them. A re-seed skips everything, so
        // this is where they are observable.
        assertTrue(reseed.err().contains("  skipped: "), reseed.err());
        // The skip line names the API ENDPOINT that was already cached, not the cache file name.
        assertTrue(reseed.err().contains("  skipped: /api/mdr/sdtmig/3-4?expand=true"),
                reseed.err());

        assertEquals(0, invoke("--seed-cache-from-dir", pkl.toString(), "-ca", cache.toString(),
                "--seed-overwrite").exitCode());
        assertTrue(Files.getLastModifiedTime(entry).toMillis() > 0,
                "--seed-overwrite must rewrite the entry");
        assertTrue(firstModified > 0);
    }


    /**
     * F-cli-05. A source that yields no cache entries at all used to exit <b>0</b> with an empty
     * summary buried in JUL: a CI pipeline saw a green "seed the cache" step followed by a
     * validation run against an empty cache. It now follows the {@code --install-dictionaries}
     * template — summary and diagnosis on stderr, and a non-zero exit.
     */
    @Test
    void aSeedThatYieldsNothingFailsAndSaysWhy(@TempDir Path root) throws Exception
    {
        Path empty = Files.createDirectories(root.resolve("no-pickles"));
        Path cache = root.resolve("cache");

        Run run = invoke("--seed-cache-from-dir", empty.toString(), "-ca", cache.toString());

        assertEquals(1, run.exitCode(), run.err());
        assertTrue(run.err().contains("Error: the seed source yielded no cache entries"),
                run.err());
        assertTrue(run.err().contains(cache.toAbsolutePath().toString()), run.err());
    }


    /**
     * F-cli-05 / BT-E S-02. A dry run must be worded as a dry run on the operator's stream: the
     * ternary that chooses the wording was previously observable only through the logger, so a
     * mutant that made a dry run report "seeded" survived.
     */
    @Test
    void aDryRunIsWordedAsADryRunOnStderr(@TempDir Path root) throws Exception
    {
        Path cache = root.resolve("cache");

        Run run = invoke("--seed-cache-from-dir", pickleDir(root).toString(), "-ca",
                cache.toString(), "--seed-dry-run");

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.err().contains("Cache seeding (dry run) at " + cache + ":"), run.err());
        assertFalse(run.err().contains("Cache seeded at "), run.err());
    }


    /**
     * A pickle CT package whose id prefix is not in {@code PickleCacheSeeder.CT_NAME_PREFIXES}. The
     * seeder synthesises the display name from the raw prefix, <b>writes the entry anyway</b> and
     * records a warning — the "seeded, but not fully" case.
     */
    private static void addCtPackageWithAnUnknownPrefix(Path aPickleDir) throws IOException
    {
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("package", "zzct-2024-09-27");
        ct.put("codelists", List.of());
        Files.write(aPickleDir.resolve("zzct-2024-09-27.pkl"), new Pickler().dumps(ct));
    }


    /**
     * C2-02 / F-cli-05, RULED 2026-09-08: <i>"If the seeding was not fully successful (partially is
     * also not fully successful) then fail."</i>
     *
     * <p>
     * A warning means the entry reached the cache with a <b>synthesised name or omitted fields</b>
     * — seeded, but not as published. The exit code shipped in round 1 without a single test on its
     * triggering branch ({@code seedCache:885-886} were both NO_COVERAGE), so this is the case that
     * makes the ruling observable: the entry IS written, the run still fails, and the warning is on
     * the operator's stream rather than buried in JUL.
     * </p>
     */
    @Test
    void aSeedThatWarnsFailsEvenThoughTheEntryWasWritten(@TempDir Path root) throws Exception
    {
        Path pkl = pickleDir(root);
        addCtPackageWithAnUnknownPrefix(pkl);
        Path cache = root.resolve("cache");

        Run run = invoke("--seed-cache-from-dir", pkl.toString(), "-ca", cache.toString());

        assertEquals(1, run.exitCode(), run.err());
        assertTrue(run.err().contains("  warning: "), run.err());
        assertTrue(run.err().contains("unknown CT prefix"), run.err());
        // Not a "nothing was seeded" failure: the run wrote entries, including the warned one.
        assertFalse(run.err().contains("yielded no cache entries"), run.err());
        assertTrue(
                Files.exists(cache
                        .resolve("api_mdr_ct_packages_zzct-2024-09-27%3Fexpand%3Dtrue.json.gz")),
                "the warned entry is written — that is what makes this PARTIAL rather than failed");
    }


    /**
     * C2-02, the boundaries the warning rule must not swallow: a run that writes nothing because
     * everything is already cached, and a dry run. Both do exactly what was asked, so both stay 0.
     * Without these, "seed → exit 1" could be met by failing far too much.
     */
    @Test
    void aReSeedThatSkipsEverythingAndADryRunBothStayZero(@TempDir Path root) throws Exception
    {
        Path pkl = pickleDir(root);
        Path cache = root.resolve("cache");
        assertEquals(0, invoke("--seed-cache-from-dir", pkl.toString(), "-ca", cache.toString())
                .exitCode());

        Run reseed = invoke("--seed-cache-from-dir", pkl.toString(), "-ca", cache.toString());
        assertEquals(0, reseed.exitCode(), reseed.err());
        assertTrue(reseed.err().contains("  skipped: "), reseed.err());
        assertFalse(reseed.err().contains("  warning: "), reseed.err());

        Run dry = invoke("--seed-cache-from-dir", pkl.toString(), "-ca",
                root.resolve("other").toString(), "--seed-dry-run");
        assertEquals(0, dry.exitCode(), dry.err());
        assertFalse(dry.err().contains("  warning: "), dry.err());
    }

}
