package net.cumba.corej.cli;

import java.io.IOException;
import java.io.PrintStream;
import net.cumba.corej.core.metadata.dictionary.DictionarySource;

/**
 * Runs the CLI with dictionary downloads <b>denied</b>.
 *
 * <p>
 * ⚠⚠ Every end-to-end case in this module must go through here rather than through
 * {@link CdiscValidate#run(String[], java.io.PrintStream, java.io.PrintStream)}, whose seam is the
 * production one — real HTTP to NCI EVS and precisionFDA. The reason is not politeness towards
 * those hosts. {@code installTargetDir} ends at the CWD-relative {@code ./dictionaries}, so the
 * single negated conditional {@code if (args.installDictionaries)} in {@code run} sends an
 * <em>ordinary validate case</em> down the credential-free download path: with the production seam
 * that case downloads three dictionaries into the module root, is killed by the mutation-testing
 * timeout part-way through, and leaves a half-written store that becomes the next JVM's ambient
 * state. That is the mechanism behind the two {@code TIMED_OUT} mutants of the 2026-09-07 run and
 * behind the false {@code KILLED} verdicts the following minions then produced (a mutant scored
 * detected because {@code WorkingDirectoryStaysCleanExtension.beforeEach} failed on the inherited
 * leak, not because any assertion observed it).
 * </p>
 *
 * <p>
 * A test that genuinely wants a download source injects its own
 * {@link CdiscValidate.DictionaryDownloads} through the four-argument overload; a test that wants
 * the production wiring asserts {@link CdiscValidate#httpDownloads()} directly, without running it.
 * </p>
 */
final class OfflineCli
{

    /**
     * The denying seam. It fails the way an air-gapped machine does — an {@link IOException} per
     * type, which {@code installDictionaries} records as that type's warning and carries on with
     * the rest (D11 best-effort) — so the code path under test is the real one, minus the network.
     */
    static final CdiscValidate.DictionaryDownloads DENIED = aType ->
    {
        throw new IOException("dictionary downloads are denied in tests (type " + aType
                + "): inject a CdiscValidate.DictionaryDownloads if this case needs one");
    };

    private OfflineCli()
    {
    }

    /**
     * The denying pickle-archive seam — the {@code --seed-cache} counterpart of {@link #DENIED},
     * and needed for the same reason: a negated {@code seedCacheFromDir != null} turns a local seed
     * into a repository-archive download.
     */
    static final CdiscValidate.PickleArchives NO_ARCHIVE = (aRepo, aRef, aPath, aTemplate,
            aWorkDir) ->
    {
        throw new IOException("pickle-archive downloads are denied in tests (repo " + aRepo + ")");
    };

    /** As {@code CdiscValidate.run(args, out, err)}, but with {@link #DENIED} downloads. */
    static int run(String[] aArgs, PrintStream aOut, PrintStream aErr) throws Exception
    {
        return CdiscValidate.run(aArgs, aOut, aErr, DENIED, CdiscValidate.configuredLibrary(),
                NO_ARCHIVE);
    }


    /**
     * As {@link #run(String[], PrintStream, PrintStream)}, but with the CDISC Library provider
     * supplied by the caller — the only way to drive the degraded-Library surface without a live
     * outage and a configured API key.
     */
    static int run(String[] aArgs, PrintStream aOut, PrintStream aErr,
            CdiscValidate.LibraryAccess aLibrary)
        throws Exception
    {
        return CdiscValidate.run(aArgs, aOut, aErr, DENIED, aLibrary, NO_ARCHIVE);
    }


    /**
     * A seam that yields {@code aSource} for every type — for a case that wants a canned install.
     */
    static CdiscValidate.DictionaryDownloads always(DictionarySource aSource)
    {
        return aType -> aSource;
    }
}
