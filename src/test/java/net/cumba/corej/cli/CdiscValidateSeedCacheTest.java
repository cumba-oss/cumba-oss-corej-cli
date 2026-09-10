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
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.razorvine.pickle.Pickler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage of the CLI's {@code --seed-cache} maintenance mode — since cache 8b-1 the
 * rebuild of the <b>unified metadata store</b> (one zip) rather than the retired web-api cache
 * directory.
 *
 * <p>
 * These drive {@code CdiscValidate.run(String[], PrintStream, PrintStream)} — the same entry point
 * {@code main} uses — so option wiring, mutual exclusion, target resolution and exit codes are all
 * exercised together. Seeding runs from a local pickle directory, so no network is involved.
 * </p>
 */
@org.junit.jupiter.api.extension.ExtendWith(WorkingDirectoryStaysCleanExtension.class)
class CdiscValidateSeedCacheTest
{

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
    void seedsFromALocalDirectoryIntoTheGivenStoreFile(@TempDir Path root) throws Exception
    {
        Path store = root.resolve("metadata-cache.zip");

        Run run = invoke("--seed-cache-from-dir", pickleDir(root).toString(), "-ca",
                store.toString());

        assertEquals(0, run.exitCode(), run.err());
        // F-cli-05: the summary reaches the OPERATOR's stream, not only the logger. Until now a
        // seeding step said nothing on stderr whatever it did or failed to do.
        assertTrue(run.err().contains("Metadata store seeded at " + store + ":"), run.err());
        // ⚠ The load-bearing check: the written file must open through the ENGINE's own reader —
        // a store the seeder "wrote" but the reader rejects is silently inert.
        try (MetadataStore opened = MetadataStore.open(store))
        {
            assertEquals(List.of("sdtmct-2024-09-27"), opened.publishedCtPackages());
            assertTrue(opened.product("standards/sdtmig/3-4").isPresent(),
                    "the projected IG product must be readable");
        }
    }


    /** Seeding is standalone: it must not demand -d / -dxp, which normal runs require. */
    @Test
    void seedingDoesNotRequireValidationInputs(@TempDir Path root) throws Exception
    {
        Run run = invoke("--seed-cache-from-dir", pickleDir(root).toString(), "-ca",
                root.resolve("metadata-cache.zip").toString());

        assertEquals(0, run.exitCode(), run.err());
        assertFalse(run.err().contains("provide -d"), run.err());
    }


    @Test
    void dryRunWritesNothing(@TempDir Path root) throws Exception
    {
        Path store = root.resolve("metadata-cache.zip");

        Run run = invoke("--seed-cache-from-dir", pickleDir(root).toString(), "-ca",
                store.toString(), "--seed-dry-run");

        assertEquals(0, run.exitCode(), run.err());
        assertFalse(Files.exists(store), "a dry run must not create the store");
    }


    @Test
    void mutuallyExclusiveSourcesAreAUsageError(@TempDir Path root) throws Exception
    {
        Run run = invoke("--seed-cache", "https://example.org/repo", "--seed-cache-from-dir",
                pickleDir(root).toString(), "-ca", root.resolve("metadata-cache.zip").toString());

        assertEquals(2, run.exitCode());
        assertTrue(run.err().contains("mutually exclusive"), run.err());
    }


    /** The third source is mutually exclusive with each of the other two as well. */
    @Test
    void fromApiBesideFromDirIsAUsageError(@TempDir Path root) throws Exception
    {
        Run run = invoke("--seed-cache-from-dir", pickleDir(root).toString(),
                "--seed-cache-from-api", "-ca", root.resolve("metadata-cache.zip").toString());

        assertEquals(2, run.exitCode());
        assertTrue(run.err().contains("mutually exclusive"), run.err());
    }


    /**
     * {@code --seed-cache-from-api} without an API key is a usage error naming the keyless
     * alternative — never a silent no-op and never a network attempt.
     */
    @Test
    void fromApiWithoutAnApiKeyIsAUsageErrorNamingTheAlternative(@TempDir Path root)
        throws Exception
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("CDISC_API_KEY") == null
                        && System.getProperty("cdisc.library.api.key") == null,
                "a CDISC Library API key is configured — the keyless refusal is untestable");
        Run run = invoke("--seed-cache-from-api", "-ca",
                root.resolve("metadata-cache.zip").toString());

        assertEquals(2, run.exitCode(), run.err());
        assertTrue(run.err().contains("needs a CDISC Library API key"), run.err());
        assertTrue(run.err().contains("--seed-cache"), run.err());
        assertFalse(Files.exists(root.resolve("metadata-cache.zip")), run.err());
    }


    @Test
    void aMissingPickleDirectoryFailsWithExitCodeOne(@TempDir Path root) throws Exception
    {
        Run run = invoke("--seed-cache-from-dir", root.resolve("absent").toString(), "-ca",
                root.resolve("metadata-cache.zip").toString());

        assertEquals(1, run.exitCode());
        assertTrue(run.err().contains("metadata store seeding failed"), run.err());
    }


    /**
     * Re-seeding carries forward what the store already holds (plan §5.2: CT packages are immutable
     * once published, presence suffices) and {@code --seed-overwrite} forces a full re-acquisition.
     * Observable through the report's fetched/carried split.
     */
    @Test
    void rerunCarriesInsteadOfRefetchingAndOverwriteForcesReacquisition(@TempDir Path root)
        throws Exception
    {
        Path pkl = pickleDir(root);
        Path store = root.resolve("metadata-cache.zip");
        Run first = invoke("--seed-cache-from-dir", pkl.toString(), "-ca", store.toString());
        assertEquals(0, first.exitCode(), first.err());
        assertTrue(first.err().contains("1 CT packages fetched, 0 carried"), first.err());

        Run reseed = invoke("--seed-cache-from-dir", pkl.toString(), "-ca", store.toString());
        assertEquals(0, reseed.exitCode(), reseed.err());
        assertTrue(reseed.err().contains("0 CT packages fetched, 1 carried"),
                "a re-seed must carry the published package instead of re-reading its pickle: "
                        + reseed.err());

        Run refresh = invoke("--seed-cache-from-dir", pkl.toString(), "-ca", store.toString(),
                "--seed-overwrite");
        assertEquals(0, refresh.exitCode(), refresh.err());
        assertTrue(refresh.err().contains("1 CT packages fetched, 0 carried"),
                "--seed-overwrite must ignore the existing store and re-acquire: " + refresh.err());
    }


    /**
     * F-cli-05. A source that yields no metadata at all used to exit <b>0</b> with an empty summary
     * buried in JUL: a CI pipeline saw a green "seed the store" step followed by a validation run
     * against an empty store. It now follows the {@code --install-dictionaries} template — summary
     * and diagnosis on stderr, and a non-zero exit.
     */
    @Test
    void aSeedThatYieldsNothingFailsAndSaysWhy(@TempDir Path root) throws Exception
    {
        Path empty = Files.createDirectories(root.resolve("no-pickles"));
        Path store = root.resolve("metadata-cache.zip");

        Run run = invoke("--seed-cache-from-dir", empty.toString(), "-ca", store.toString());

        assertEquals(1, run.exitCode(), run.err());
        assertTrue(run.err().contains("Error: the seed source yielded no metadata"), run.err());
        assertTrue(run.err().contains(store.toAbsolutePath().toString()), run.err());
    }


    /**
     * F-cli-05 / BT-E S-02. A dry run must be worded as a dry run on the operator's stream: the
     * ternary that chooses the wording was previously observable only through the logger, so a
     * mutant that made a dry run report "seeded" survived.
     */
    @Test
    void aDryRunIsWordedAsADryRunOnStderr(@TempDir Path root) throws Exception
    {
        Path store = root.resolve("metadata-cache.zip");

        Run run = invoke("--seed-cache-from-dir", pickleDir(root).toString(), "-ca",
                store.toString(), "--seed-dry-run");

        assertEquals(0, run.exitCode(), run.err());
        assertTrue(run.err().contains("Metadata store seeding (dry run) at " + store + ":"),
                run.err());
        assertFalse(run.err().contains("Metadata store seeded at "), run.err());
    }


    /**
     * A {@code *ct-*} pickle whose stem the store's id grammar rejects: the seeder excludes it from
     * the store AND its published enumeration, with a warning.
     */
    private static void addCtPackageWithAnInvalidId(Path aPickleDir) throws IOException
    {
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("package", "Xct-2024-09-27");
        ct.put("codelists", List.of());
        Files.write(aPickleDir.resolve("Xct-2024-09-27.pkl"), new Pickler().dumps(ct));
    }


    /**
     * C2-02 / F-cli-05, RULED 2026-09-08: <i>"If the seeding was not fully successful (partially is
     * also not fully successful) then fail."</i> A warning here means part of the source was
     * excluded from the store — seeded, but not fully. The rest IS written, the run still fails,
     * and the warning is on the operator's stream rather than buried in JUL.
     */
    @Test
    void aSeedThatWarnsFailsEvenThoughTheStoreWasWritten(@TempDir Path root) throws Exception
    {
        Path pkl = pickleDir(root);
        addCtPackageWithAnInvalidId(pkl);
        Path store = root.resolve("metadata-cache.zip");

        Run run = invoke("--seed-cache-from-dir", pkl.toString(), "-ca", store.toString());

        assertEquals(1, run.exitCode(), run.err());
        assertTrue(run.err().contains("  warning: "), run.err());
        assertTrue(run.err().contains("not a valid CT package id"), run.err());
        // Not a "nothing was seeded" failure: the run wrote the store, minus the warned entry.
        assertFalse(run.err().contains("yielded no metadata"), run.err());
        try (MetadataStore opened = MetadataStore.open(store))
        {
            assertEquals(List.of("sdtmct-2024-09-27"), opened.publishedCtPackages(),
                    "the store is written WITHOUT the excluded id — partial, not failed");
        }
    }


    /**
     * C2-02, the boundaries the warning rule must not swallow: a re-seed that carries everything,
     * and a dry run. Both do exactly what was asked, so both stay 0.
     */
    @Test
    void aCarryEverythingReSeedAndADryRunBothStayZero(@TempDir Path root) throws Exception
    {
        Path pkl = pickleDir(root);
        Path store = root.resolve("metadata-cache.zip");
        assertEquals(0, invoke("--seed-cache-from-dir", pkl.toString(), "-ca", store.toString())
                .exitCode());

        Run reseed = invoke("--seed-cache-from-dir", pkl.toString(), "-ca", store.toString());
        assertEquals(0, reseed.exitCode(), reseed.err());
        assertFalse(reseed.err().contains("  warning: "), reseed.err());

        Run dry = invoke("--seed-cache-from-dir", pkl.toString(), "-ca",
                root.resolve("other.zip").toString(), "--seed-dry-run");
        assertEquals(0, dry.exitCode(), dry.err());
        assertFalse(dry.err().contains("  warning: "), dry.err());
    }

}
