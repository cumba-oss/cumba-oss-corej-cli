package net.cumba.corej.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.corej.core.VersionInfo;
import net.cumba.corej.core.metadata.dictionary.DictionaryConverter;
import net.cumba.corej.core.metadata.dictionary.DictionaryDirectoryResolver;
import net.cumba.corej.core.metadata.dictionary.DictionaryInstaller;
import net.cumba.corej.core.metadata.dictionary.DictionaryLicences;
import net.cumba.corej.core.metadata.dictionary.DictionarySource;
import net.cumba.corej.core.metadata.dictionary.HttpDictionarySource;
import net.cumba.corej.core.metadata.dictionary.InstallReport;
import net.cumba.corej.core.metadata.dictionary.LocalDictionarySource;
import net.cumba.corej.core.metadata.dictionary.LoincConverter;
import net.cumba.corej.core.metadata.dictionary.MedDraConverter;
import net.cumba.corej.core.metadata.dictionary.MedRtConverter;
import net.cumba.corej.core.metadata.dictionary.NeoplasmConverter;
import net.cumba.corej.core.metadata.dictionary.SnomedConverter;
import net.cumba.corej.core.metadata.dictionary.UniiConverter;
import net.cumba.corej.core.metadata.dictionary.WhoDrugConverter;
import net.cumba.corej.core.metadata.pickle.HttpArchivePickleSource;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.pickle.PickleCacheSeeder;
import net.cumba.corej.core.metadata.pickle.PickleSource;
import net.cumba.corej.core.metadata.pickle.ProductKeyResolver;
import net.cumba.corej.core.metadata.pickle.SeedOptions;
import net.cumba.corej.core.metadata.pickle.SeedReport;
import net.cumba.corej.core.report.LibraryValidator;
import net.cumba.corej.core.report.ReportFormat;
import net.cumba.corej.core.report.ReportManager;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.core.report.ServiceReportManager;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.corej.core.run.StudyValidationException;
import net.cumba.corej.core.run.StudyValidationParams;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.core.run.StudyValidationService;
import net.cumba.corej.define.conformance.engine.DefineConformanceEngine;
import net.cumba.corej.define.conformance.engine.DefineConformanceInput;
import net.cumba.corej.define.conformance.engine.DefineConformanceReport;
import net.cumba.corej.define.conformance.engine.JsonReportWriter;
import net.cumba.corej.define.conformance.rule.DefineRuleSelectionException;
import net.cumba.corej.define.conformance.rule.RuleSet;
import net.cumba.datatable.io.Property;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.local.LocalDataTableManager;
import net.cumba.web.api.cache.GzipFileApiCache;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;

/**
 * Command-line entry point for running CDISC validation against a local study and writing a JSON
 * report compatible with the Python {@code core validate} command.
 *
 * <h2>Supported options</h2>
 *
 * <p>
 * The option names mirror the Python tool. Options not implemented by the Java engine are accepted
 * (and warned about) so that scripts written for the Python tool can be reused without
 * modification.
 * </p>
 *
 * <pre>
 * Required:
 *   -rp, --rules-package &lt;name&gt;           rule package short name (e.g. cdisc-sdtmig-3-4);
 *                                          comma-separated and/or repeatable. The package
 *                                          declares the CDISC Library standard it runs against.
 *
 * Data inputs (one of the following):
 *   -d,  --data &lt;path|URI&gt;                data library — directory, single file
 *                                          (.sas7bdat, .xpt, .xlsx, .rda, .dsj, .parquet, …),
 *                                          or remote URI (file://, ssh://, …). All members
 *                                          become validation targets. No define.xml search.
 *   -dxp,--define-xml-path &lt;file&gt;         define.xml. With -d: provides metadata enrichment
 *                                          (variable types, codelists, value-level
 *                                          conditions). Without -d: acts as the data library
 *                                          too (legacy fallback).
 *
 * Output:
 *   -o,  --output &lt;file&gt;                  output file (default CORE-Report-&lt;timestamp&gt;.json)
 *   -of, --output-format json|json-2|xlsx output format(s); repeatable/comma-separated (default json).
 *                                          The list is whatever report-writer modules are on the
 *                                          classpath; json-2 = combined-finding report
 *                                          (&lt;base&gt;.v2.json). The legacy alias json2 is still
 *                                          accepted for json-2.
 *   -mr, --max-report-rows &lt;n&gt;            per-sheet row cap for the xlsx report (default 10000; 0 = unlimited)
 *
 * Optional:
 *   -mp, --metadata-products &lt;p&gt;[,&lt;p&gt;...] ordered CDISC Library products consulted for
 *                                          metadata, highest precedence first (metadata only;
 *                                          rules stay on -s/-v). Default: the product implied
 *                                          by -s/-v.
 *   -uc, --use-case &lt;uc&gt;                  TIG use case
 *   -ct, --controlled-terminology-package &lt;pkg&gt;
 *                                         CT package id, repeatable
 *   -dv, --define-version &lt;ver&gt;           Define-XML version (2-1, 2.0); selects the
 *                                         rule package, so a bogus value is an error
 *        --define-family &lt;sheet&gt;           REQUIRED for -vx: CDISC and/or PMDA
 *   -r,  --rules &lt;CORE-id&gt;                include only matching rule ids (repeatable)
 *   -er, --exclude-rules &lt;CORE-id&gt;        exclude matching rule ids (repeatable)
 *   -ds, --dataset &lt;name&gt;[,&lt;name&gt;...]      validate only these library members; the rest are
 *                                          registered as lazy references for cross-dataset rules
 *                                          (repeatable; values may be comma-separated)
 *   -rd, --reference-data &lt;path&gt;          additional reference library (a directory with a
 *                                          define.xml or a define.xml file directly). Members
 *                                          are visible to cross-dataset rules but never
 *                                          validated as targets. Repeatable. Use this to provide
 *                                          SDTM data when validating ADaM. In --remote mode the
 *                                          members are uploaded and translated into a
 *                                          datasetFilter complement; combined with -ds the
 *                                          filter is -ds MINUS -rd (-rd always wins). A -rd
 *                                          pointing straight at a define.xml file is rejected
 *                                          remotely — the href closure is not resolved there.
 *   -t,  --threads &lt;n&gt;                    rule worker threads per dataset (default 1; max =
 *                                          available CPU cores). Values &lt; 1 error out.
 *   -ca, --cache &lt;dir&gt;                    cache dir for CDISC Library API responses
 *
 * External dictionaries (PLAN-dictionary-seeder Phase 6b):
 *        --dictionaries-dir &lt;dir&gt;         installed-dictionary store root (default:
 *                                          COREJ_DICTIONARIES_DIR env, corej.dictionariesDir
 *                                          sysprop, then ./dictionaries)
 *        --&lt;type&gt;-version &lt;v&gt;             bind an installed dictionary version for the run
 *                                          (meddra/whodrug/loinc/medrt/unii/neoplasm; SNOMED
 *                                          uses --snomed-version-select). Precedence: option,
 *                                          then define.xml ExternalCodeList Version, then the
 *                                          store's selected-versions.json; strictly no
 *                                          substitution — an uninstalled version SKIPs.
 *        --install-dictionaries           install dictionaries into the store, then exit.
 *                                          With no input options, downloads the three
 *                                          credential-free ones (MED-RT and neoplasm from NCI
 *                                          EVS, UNII from precisionFDA) — as does a bare
 *                                          --medrt / --unii / --neoplasm. Local vendor
 *                                          distributions: --meddra --whodrug --loinc --medrt
 *                                          --unii --snomed --neoplasm, each taking a
 *                                          directory; --set-default rebinds an existing
 *                                          selection, --skip-installed leaves an
 *                                          already-installed type/version untouched
 *                                          (idempotent re-runs), --dry-run writes nothing. Outside
 *                                          install mode the input options stay
 *                                          accepted-but-ignored (Python-CLI compatibility).
 *
 * Java-only extras:
 *        --rules-dir &lt;dir&gt;                rules directory (default ./rules; also COREJ_RULES_DIR env / corej.rules.dir sysprop)
 *        --rules-file &lt;file&gt;              extra rule package file to load (repeatable)
 *        --runtime-report &lt;file&gt;          CSV with per-(dataset, rule) runtime
 *                                         (default: &lt;output&gt;.runtime.csv). Columns:
 *                                         domain,fileName,rowCount,columnCount,coreId,
 *                                         elapsedMs,status,violationCount
 * </pre>
 *
 * <h2>System properties</h2>
 * <ul>
 * <li>{@code -Dcorej.studyAnchorPass=false} — routes {@code Sensitivity: "Study"} rules back
 * through the per-dataset path and the cross-dataset collapse instead of executing them once
 * against the study anchor. Same verdicts either way; a diagnostic safety valve. Note the
 * per-dataset path cannot report a study rule when the submission has no analysable datasets.</li>
 * <li>{@code -Dcorej.exprCache.disabled=true} — disables the per-dataset expression-result
 * cache.</li>
 * <li>{@code -Dcorej.degradedDefineFallback=true} — <b>opt-in, default off (Fix #369).</b> When the
 * CDISC Library product fetch fails — most often an expired or absent subscription key, i.e. an
 * ordinary HTTP 401 rather than an outage — every rule that consults the Library reports
 * {@code SKIPPED} by default, because a rule that states it checks the Library has not done so if
 * the source was not the Library. This property permits the study's Define-XML to stand in:
 * whatever the define can actually answer is then answered from the sponsor's own declarations, and
 * whatever it cannot ({@code domain_is_custom}, the SDTM-Model arms, and any operand the define
 * does not carry) still skips. There is no per-arm allow-list — an arm the define cannot serve
 * returns nothing and skips on that basis. ⚠ The substitution engages only when the study metadata
 * really is Define-XML backed; a data-derived fallback is never admitted under it. The run records
 * which basis was used once, in {@code Conformance_Details.Library_Metadata_Basis}.</li>
 * </ul>
 *
 * <h2>Runtime CSV schema</h2> The per-rule runtime CSV carries <b>8</b> columns. A redundant
 * {@code ruleId} column was removed: it repeated {@code coreId} for every corpus rule and was blank
 * or a deterministic function of {@code coreId} otherwise. Consumers parsing the file <b>by column
 * index</b> must be updated; stored {@code *.runtime.csv} files written by earlier versions still
 * carry the old 9-column header.
 *
 * <h2>Authentication</h2> Reads the CDISC Library API key from the {@code CDISC_API_KEY}
 * environment variable, falling back to the {@code cdisc.library.api.key} system property. Without
 * an API key, library metadata enrichment is skipped and the validation may produce SKIPPED rules.
 */
@lombok.CustomLog
public final class CdiscValidate
{

    private static final String CORE_ENGINE_VERSION = VersionInfo
            .forArtifact("cumba-oss-corej-core").version();

    /**
     * The report-writer registry. Every {@code --output-format} decision — the parser's allowlist,
     * the usage banner, the file suffix and the write itself — reads from this and nothing else, so
     * the set of formats this CLI offers is exactly the set of report-writer modules on its
     * classpath (Fix #224).
     */
    private static final ReportManager REPORT_MANAGER = ServiceReportManager.getInstance();

    /** The xlsx writer's declared per-sheet row cap; the option {@code -mr} feeds. */
    private static final String MAX_ROWS_PER_SHEET = "maxRowsPerSheet";

    /** Per-poll long-poll window for {@code --remote} status (server caps this at 60s). */
    private static final int REMOTE_POLL_SECONDS = 30;

    /**
     * The dictionary types the installer can download without credentials (Phase 7a). Everything
     * else — LOINC (free account), SNOMED, MedDRA, WHODrug (licensed distributions) — installs from
     * a local path only. Ordered, so a no-input install run is deterministic.
     */
    private static final List<String> DOWNLOADABLE_TYPES = List.of("medrt", "unii", "neoplasm");

    /** System property overriding the NCI EVS base URL for dictionary downloads. */
    private static final String SP_EVS_BASE_URL = "corej.dictionaries.evsBaseUrl";

    /** System property overriding the precisionFDA UNII archive URL for dictionary downloads. */
    private static final String SP_UNII_ARCHIVE_URL = "corej.dictionaries.uniiArchiveUrl";

    /**
     * The dictionary <b>download</b> seam — the same shape as
     * {@code CdiscLibraryBackedLibraryProvider.ProductSource}, and package-visible for the same
     * reason: so a test can hand the installer a canned or a deliberately-failing source instead of
     * letting it reach the public internet.
     *
     * <p>
     * ⚠ This is not a convenience. {@code installTargetDir} ends at the CWD-relative
     * {@code ./dictionaries} (D4's documented last tier, and the shipped bundle's layout), so a
     * <em>single</em> flipped guard — the negated {@code if (args.installDictionaries)} in
     * {@link #run(Args, PrintStream)} — sends an ordinary validate run down the credential-free
     * download path. With a live {@link #httpDownloads()} that run makes real requests to NCI EVS
     * and precisionFDA: it takes minutes, it is killed by the mutation-testing timeout mid-write,
     * and its half-written store becomes the next JVM's ambient state. Behind this seam the same
     * mutant completes in milliseconds and is reported honestly.
     * </p>
     */
    interface DictionaryDownloads
    {

        /**
         * @param aType
         *            one of {@link #DOWNLOADABLE_TYPES}.
         * @return the source to install that dictionary from.
         * @throws IOException
         *             if the source cannot be reached or built.
         */
        DictionarySource forType(String aType) throws IOException;
    }


    /**
     * How a Define-XML run obtains its CDISC Library provider. Package-visible for the same reason
     * as {@link DictionaryDownloads}: {@link #printLibraryBasis} is only reachable with a provider
     * that has actually recorded a failed lookup, and building one used to require a configured API
     * key plus a real Library outage. Nothing short of that could pin the <b>call site</b> — the
     * method itself was extracted and tested (C2-04), but every covering case ran with
     * {@code apiKey == null}, so the provider was {@code null}, {@code printLibraryBasis} returned
     * at its first {@code if}, and deleting the call from {@link #runDefineConformance} changed no
     * assertion (C3-02).
     */
    interface LibraryAccess
    {

        /**
         * @param aCacheDir
         *            the {@code -ca} cache directory, or {@code null} for the shared default.
         * @return the provider for this run, or {@code null} when no Library access is configured
         *         and no lookup will be attempted.
         */
        @Nullable
        CdiscLibraryBackedLibraryProvider provider(@Nullable String aCacheDir);
    }


    /**
     * Where a {@code --seed-cache} run fetches the Python engine's pickle archive from. The same
     * seam as {@link DictionaryDownloads}, for the same reason and against the same mutant shape:
     * negating {@code if (aArgs.seedCacheFromDir != null)} in {@link #buildPickleSource} sends a
     * local-directory seed down the <b>archive-download</b> path, and with the production binding
     * that mutant fetches a repository archive over HTTP — minutes per minion, killed by the
     * mutation-testing timeout, scored {@code TIMED_OUT} with no test having observed anything.
     */
    interface PickleArchives
    {

        PickleSource forRepo(String aRepo, @Nullable String aRef, String aRepoPath,
                @Nullable String aArchiveUrlTemplate, @Nullable Path aWorkDir)
            throws IOException;
    }

    /** The download seam in force for this invocation; never {@code null}. */
    private final DictionaryDownloads downloads;

    /** The pickle-archive seam in force for this invocation; never {@code null}. */
    private final PickleArchives archives;

    /** The Library seam in force for this invocation; never {@code null}. */
    private final LibraryAccess library;

    /**
     * The only constructor: every outbound channel this CLI has is supplied by the caller. The
     * production bindings ({@link #httpDownloads()}, {@link #configuredLibrary()},
     * {@link #httpArchives()}) are chosen one level up, in
     * {@link #run(String[], PrintStream, PrintStream)}.
     *
     * <p>
     * ⚠ The no-arg, one-arg and two-arg constructors this class used to carry were deleted in round
     * 4: each existed only to fill in a production seam, none had a caller, and the no-arg one's
     * "Production binding" javadoc described a wiring nothing performed.
     * </p>
     */
    CdiscValidate(DictionaryDownloads aDownloads, LibraryAccess aLibrary, PickleArchives aArchives)
    {
        downloads = aDownloads;
        library = aLibrary;
        archives = aArchives;
    }


    /** The production {@link PickleArchives}: a real HTTP fetch of the repository archive. */
    static PickleArchives httpArchives()
    {
        return (aRepo, aRef, aRepoPath, aTemplate, aWorkDir) -> new HttpArchivePickleSource(aRepo,
                aRef, aRepoPath, aTemplate, aWorkDir);
    }


    /**
     * The production {@link LibraryAccess}: a provider over a configured
     * {@link CdiscLibraryClient}, or {@code null} when no API key is set.
     */
    static LibraryAccess configuredLibrary()
    {
        return aCacheDir ->
        {
            String apiKey = CdiscLibraryClient.getApiKey();
            if (apiKey == null || apiKey.isBlank())
            {
                return null;
            }
            CdiscLibraryClient.CdiscBuilder client = CdiscLibraryClient.builder();
            // Same cache resolution as the metadata-enrichment path (CoreLibraryAccessImpl):
            // -ca dir in the Gzip format that path writes, else the shared default cache
            // (~/.cdiscApiCache) rather than the builder's no-op fallback.
            client.cache(aCacheDir != null
                    ? new GzipFileApiCache(Path.of(aCacheDir).toAbsolutePath(), ".json")
                    : CdiscLibraryClient.getCache());
            return CdiscLibraryBackedLibraryProvider.over(client.build());
        };
    }


    public static void main(String[] args) throws Exception
    {
        // The dataviewer's net.cumba.license.LicenseGate call was stripped on copy — Apache 2.0
        // OSS distribution, no license gate.
        // Fix #58 — load /logging.properties from this module's resources before any logger
        // fires, so users get readable "LEVEL: message" output on stderr by default. If the
        // resource is absent (e.g. CdiscValidate run outside its packaged module), JUL's
        // built-in defaults take over — non-fatal.
        try (java.io.InputStream in = CdiscValidate.class
                .getResourceAsStream("/logging.properties"))
        {
            if (in != null)
            {
                java.util.logging.LogManager.getLogManager().readConfiguration(in);
            }
        }
        catch (IOException e)
        {
            System.err.println("Warning: failed to load logging.properties: " + e.getMessage());
        }

        System.exit(run(args, System.out, System.err));
    }


    /**
     * <b>The binding {@link #main(String[])} uses</b>, and the shipped binary's exit-code contract:
     * same behaviour as {@code main} but writing to the supplied streams and <em>returning</em> the
     * process exit code instead of calling {@link System#exit(int)}. Every outbound channel gets
     * its production binding here.
     *
     * <p>
     * ⚠ This return value is load-bearing and must stay asserted for a NON-zero code — see
     * {@code CdiscValidateHelpersTest.run_threeArgOverload_propagatesANonZeroExitCode}. While the
     * only case reaching this line was a {@code -h} run asserting {@code 0}, replacing the
     * delegation with {@code run(…); return 0;} left the whole suite green and made every failure
     * mode of the shipped CLI — bad option, failed install, missing data directory — exit 0.
     * </p>
     *
     * <p>
     * The four-argument and five-argument overloads that used to sit between this method and the
     * one below were deleted in round 4: they had no caller, they were each documented "entry point
     * for tests" while no test used them, and their own delegating returns carried the same
     * unasserted mutant. Tests that need a seam pass all three, through {@code OfflineCli}.
     * </p>
     */
    static int run(String[] args, PrintStream out, PrintStream err) throws Exception
    {
        return run(args, out, err, httpDownloads(), configuredLibrary(), httpArchives());
    }


    /** Entry point for tests that control every outbound channel this CLI has. */
    static int run(String[] args, PrintStream out, PrintStream err, DictionaryDownloads aDownloads,
            LibraryAccess aLibrary, PickleArchives aArchives)
        throws Exception
    {
        Args parsed;
        try
        {
            parsed = Args.parse(args, err);
        }
        catch (UsageException e)
        {
            // Usage error — print to stderr regardless of log config so it's always visible.
            err.println("Error: " + e.getMessage());
            printUsage(err);
            return 2;
        }

        if (parsed.help)
        {
            printUsage(out);
            return 0;
        }

        RemoteConfig remote = RemoteConfig.resolve(parsed);
        if (remote.isRemote())
        {
            if (parsed.installDictionaries)
            {
                // A local maintenance mode cannot run against a remote service: silently
                // proceeding would upload nothing and install nothing, while looking accepted.
                err.println("Error: --install-dictionaries is a local maintenance mode and "
                        + "cannot be combined with --remote. Run it without --remote (on the "
                        + "machine whose store you want to fill).");
                return 2;
            }
            return runRemote(parsed, remote, err);
        }

        try
        {
            return new CdiscValidate(aDownloads, aLibrary, aArchives).run(parsed, err);
        }
        catch (UsageException e)
        {
            // A usage error raised during execution (e.g. an unresolvable --define-rules-dir) —
            // print it and exit 2, consistent with parse-time usage errors above.
            err.println("Error: " + e.getMessage());
            return 2;
        }
    }


    /**
     * Drives a remote REST service instead of running the engine in-process: create a session,
     * upload the local data / define.xml / rules file, start a check, poll to completion, and write
     * the returned report to {@code -o}. Invocation-compatible with local mode (same options).
     */
    private static int runRemote(Args args, RemoteConfig remote, PrintStream err)
    {
        if (args.data != null && isUriLibrary(args.data))
        {
            err.println("Error: --remote requires a local --data path (file or directory), "
                    + "not a URI. The server reads the uploaded files.");
            return 2;
        }
        if (args.outputFormats.stream().anyMatch("xlsx"::equalsIgnoreCase))
        {
            // The remote client fetches the JSON report (v1) / v2 report verbatim; it does not
            // negotiate the server's xlsx rendering, so xlsx is local-mode only. Reject it here
            // rather than write JSON bytes into a .xlsx file.
            err.println("Error: --output-format xlsx is not supported in --remote mode "
                    + "(use local mode for the Excel report).");
            return 2;
        }
        if (!args.controlledTerminologyPackages.isEmpty() || args.cache != null)
        {
            err.println("Note: -ct / -ca are server-side configuration in --remote mode and are "
                    + "ignored by the client.");
        }
        // -rd is translated into a COMPLEMENT datasetFilter (see planReferenceData). Resolved
        // before the session is created so a bad -rd aborts without uploading anything.
        ReferenceDataPlan referencePlan = null;
        if (!args.referenceData.isEmpty())
        {
            referencePlan = planReferenceData(args, err);
            if (referencePlan == null)
            {
                return 2;
            }
            err.println("Note: -rd / --reference-data is translated into a datasetFilter "
                    + "complement in --remote mode: the reference members are uploaded into the "
                    + "session and every member NOT named by -rd is validated. With -ds / "
                    + "--dataset the filter is -ds MINUS -rd — -rd always wins.");
        }
        if (args.validateXml)
        {
            err.println("Note: -vx / --validate-xml (Define-XML conformance) is a local-mode "
                    + "feature and is ignored in --remote mode.");
        }
        if (args.rulesFiles.size() > 1)
        {
            err.println("Note: --remote mode uploads only the first --rules-file.");
        }

        // runRemote is only reached after RemoteConfig.isRemote() (baseUrl != null) in run(...).
        String baseUrl = java.util.Objects.requireNonNull(remote.baseUrl(),
                "baseUrl is non-null when isRemote() is true");
        RemoteValidationClient client = new RemoteValidationClient(baseUrl, remote.authHeader());
        try
        {
            String sessionId = client.createSession();
            LOGGER.log(System.Logger.Level.INFO, "Remote session created: {0}", sessionId);

            if (!uploadData(client, sessionId, args, err))
            {
                return 2;
            }
            List<String> datasetFilter = new ArrayList<>(args.datasets);
            if (referencePlan != null)
            {
                for (Path referenceMember : referencePlan.uploads())
                {
                    client.uploadFile(sessionId, referenceMember, fileName(referenceMember));
                }
                datasetFilter = referencePlan.datasetFilter();
            }
            String defineXmlFilename = uploadNamed(client, sessionId, args.defineXmlPath, err,
                    "define.xml");
            if (args.defineXmlPath != null && defineXmlFilename == null)
            {
                return 2;
            }
            List<String> rulesFilenames = new ArrayList<>();
            for (String rulesFile : args.rulesFiles)
            {
                String name = uploadNamed(client, sessionId, rulesFile, err, "rules file");
                if (name == null)
                {
                    return 2;
                }
                rulesFilenames.add(name);
            }

            String runId = client.startCheck(sessionId,
                    buildRemoteRequest(args, defineXmlFilename, rulesFilenames, datasetFilter));
            LOGGER.log(System.Logger.Level.INFO, "Remote check run started: {0}", runId);

            JsonNode last;
            String status;
            do
            {
                last = client.awaitStatus(runId, REMOTE_POLL_SECONDS);
                status = last.path("status").asText();
                if (!isRemoteTerminal(status))
                {
                    LOGGER.log(System.Logger.Level.INFO, "  remote status={0} rulesExecuted={1}",
                            status, last.path("rulesExecuted").asInt());
                }
            }
            while (!isRemoteTerminal(status));

            if ("SUCCEEDED".equals(status))
            {
                // Write one file per requested format, sharing the base name — same naming rules as
                // local mode. json-2 fetches the combined v2 report (GET /report-v2); every other
                // format fetches the v1 report bytes the server serves verbatim.
                List<String> formats = resolveOutputFormats(args.outputFormats);
                Path outputBase = resolveOutputBase(args.output);
                List<Path> writtenPaths = new ArrayList<>();
                String dictionaryBasis = null;
                for (String format : formats)
                {
                    Path path = reportPathFor(outputBase, format);
                    String body = "json-2".equals(format) ? client.fetchReportV2(runId)
                            : client.fetchReport(runId);
                    Files.writeString(path, body, StandardCharsets.UTF_8);
                    writtenPaths.add(path);
                    if (dictionaryBasis == null)
                    {
                        dictionaryBasis = dictionaryBasisOf(body);
                    }
                }
                // D13 surface 5 holds in remote mode too: the degradation line the local path
                // prints must not vanish just because the engine ran on the server. The fetched
                // report carries it as Conformance_Details/Dictionary_Basis.
                if (dictionaryBasis != null)
                {
                    err.println("Dictionary basis: " + dictionaryBasis);
                }
                LOGGER.log(System.Logger.Level.INFO,
                        "Validation complete (remote): {0} findings. Report: {1}",
                        last.path("findingCount").asInt(0), writtenPaths.stream()
                                .map(Path::toString).collect(Collectors.joining(", ")));
                return 0;
            }

            JsonNode message = last.get("message");
            err.println("Error: remote run " + status
                    + (message != null && !message.isNull() ? ": " + message.asText() : ""));
            return 1;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            err.println("Error: remote validation interrupted.");
            return 2;
        }
        catch (IOException e)
        {
            err.println("Error: remote validation failed: " + e.getMessage());
            return 2;
        }
    }


    /** Uploads the {@code -d} data library (directory contents, or a single file). */
    private static boolean uploadData(RemoteValidationClient client, String sessionId, Args args,
            PrintStream err)
        throws IOException, InterruptedException
    {
        if (args.data == null)
        {
            return true;
        }
        Path dataPath = Path.of(args.data);
        if (!Files.exists(dataPath))
        {
            err.println("Error: data path not found: " + dataPath);
            return false;
        }
        if (Files.isDirectory(dataPath))
        {
            try (var stream = Files.list(dataPath))
            {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList())
                {
                    client.uploadFile(sessionId, file, file.getFileName().toString());
                }
            }
        }
        else
        {
            client.uploadFile(sessionId, dataPath, dataPath.getFileName().toString());
        }
        return true;
    }


    /** Uploads a single named file; returns its bare name, or {@code null} if missing/absent. */
    private static @Nullable String uploadNamed(RemoteValidationClient client, String sessionId,
            @Nullable String path, PrintStream err, String label)
        throws IOException, InterruptedException
    {
        if (path == null)
        {
            return null;
        }
        Path p = Path.of(path);
        if (!Files.exists(p))
        {
            err.println("Error: " + label + " not found: " + p);
            return null;
        }
        String filename = p.getFileName().toString();
        client.uploadFile(sessionId, p, filename);
        return filename;
    }

    /**
     * The remote translation of {@code -rd}: the reference files to stage into the session, and the
     * {@code datasetFilter} that must accompany them so they are <b>not</b> validated.
     *
     * @param uploads
     *            reference members to upload, in {@code -rd} order, collisions already dropped.
     * @param datasetFilter
     *            the positive filter naming exactly the members that stay validation targets. Never
     *            empty — an empty filter means "validate everything" server-side.
     */
    private record ReferenceDataPlan(List<Path> uploads, List<String> datasetFilter)
    {
    }

    /**
     * Translates {@code -rd} / {@code --reference-data} into a <b>complement</b>
     * {@code datasetFilter} for {@code --remote} mode.
     *
     * <p>
     * A REST session is one flat directory that <i>is</i> the data library, so a reference dataset
     * cannot be staged "beside" it — it lands inside it. The engine already draws the
     * target/reference line there: {@code StudyValidationService.loadDatasets} marks a member a
     * validation target iff the filter is empty or names it, and everything else becomes a
     * {@code ReferenceDataset} — visible to cross-dataset rules, never validated. That is exactly
     * {@code -rd}'s contract, so the CLI uploads the reference members and sends a filter that
     * names only the study's own members.
     * </p>
     *
     * <p>
     * <b>Precedence when both {@code -ds} and {@code -rd} are given: {@code -ds} MINUS
     * {@code -rd}.</b> The two are not independent lists and neither silently wins — a name that
     * appears in both is a <i>reference</i>, because letting it stay a target would validate a
     * reference dataset, the exact failure {@code -rd} exists to prevent. Without {@code -ds} the
     * target list is derived from the {@code -d} members the CLI is about to upload.
     * </p>
     *
     * <p>
     * A reference member whose name collides with a study member (after extension-stripping) is
     * dropped rather than uploaded — mirroring {@code loadReferenceLibrary}'s "domain already
     * loaded from primary library" skip, and avoiding a flat-namespace upload conflict.
     * </p>
     *
     * @param args
     *            the parsed arguments; {@code referenceData} is non-empty.
     * @param err
     *            stream for the error / note text.
     * @return the plan, or {@code null} when the translation is impossible (message printed).
     */
    private static @Nullable ReferenceDataPlan planReferenceData(Args args, PrintStream err)
    {
        if (args.data != null && !Files.exists(Path.of(args.data)))
        {
            // Same message and exit code uploadData would produce later: a typo'd -d must read as
            // a bad path, not as "the -rd translation needs -d".
            err.println("Error: data path not found: " + Path.of(args.data));
            return null;
        }
        List<String> studyMembers;
        try
        {
            studyMembers = listStudyMembers(args.data);
        }
        catch (IOException e)
        {
            err.println("Error: cannot list --data directory " + args.data + ": " + e.getMessage());
            return null;
        }
        if (studyMembers.isEmpty() && args.datasets.isEmpty())
        {
            err.println("Error: -rd / --reference-data in --remote mode needs -d / --data (or "
                    + "-ds / --dataset) to name the validation targets: the reference members are "
                    + "excluded from a positive datasetFilter, which cannot be derived from "
                    + "nothing.");
            return null;
        }

        Set<String> studyKeys = new LinkedHashSet<>();
        for (String member : studyMembers)
        {
            studyKeys.add(datasetKey(member));
        }

        Set<String> referenceKeys = new LinkedHashSet<>();
        List<Path> uploads = new ArrayList<>();
        for (String path : args.referenceData)
        {
            List<Path> members = referenceMembers(path, err);
            if (members == null)
            {
                return null;
            }
            for (Path member : members)
            {
                String name = fileName(member);
                String key = datasetKey(name);
                if (studyKeys.contains(key) || !referenceKeys.add(key))
                {
                    err.println("Note: reference dataset " + name + " skipped — " + key
                            + " is already provided by the study library or an earlier -rd path.");
                    continue;
                }
                uploads.add(member);
            }
        }

        // -ds names the targets when given; otherwise every -d member is one. Either way the
        // reference members are subtracted, so -rd wins over -ds on a name in both.
        List<String> targetNames = args.datasets.isEmpty() ? studyMembers
                : List.copyOf(args.datasets);
        List<String> filter = new ArrayList<>();
        for (String target : targetNames)
        {
            if (!referenceKeys.contains(datasetKey(target)))
            {
                filter.add(target);
            }
        }
        if (filter.isEmpty())
        {
            err.println("Error: -rd / --reference-data names every dataset that would have been "
                    + "validated, leaving no validation targets. An empty datasetFilter means "
                    + "\"validate everything\" server-side, which would validate the reference "
                    + "data — refusing rather than inverting -rd's contract.");
            return null;
        }
        return new ReferenceDataPlan(uploads, filter);
    }


    /**
     * Lists the data files a {@code -rd} path contributes as reference members: the regular files
     * of a directory, or the file itself. A {@code define.xml} inside a reference directory is
     * skipped — the session namespace is flat, so it would collide with the study's own define, and
     * the reference members are loaded here as plain tables rather than through a define.
     *
     * @return the members, or {@code null} when the path is unusable (message printed).
     */
    private static @Nullable List<Path> referenceMembers(String path, PrintStream err)
    {
        Path p = Path.of(path);
        if (!Files.exists(p))
        {
            err.println("Error: reference data path not found: " + p);
            return null;
        }
        if (!Files.isDirectory(p))
        {
            if (isDefineXml(fileName(p)))
            {
                err.println("Error: -rd " + p + " names a define.xml file directly, which is not "
                        + "supported in --remote mode: uploads are staged into one flat session "
                        + "directory and the define's href closure is not resolved, so the "
                        + "referenced datasets would never arrive. Pass the directory holding "
                        + "the reference datasets instead.");
                return null;
            }
            return List.of(p);
        }
        List<Path> members = new ArrayList<>();
        try (var stream = Files.list(p))
        {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList())
            {
                if (isDefineXml(fileName(file)))
                {
                    err.println("Note: reference define.xml " + file + " is not uploaded in "
                            + "--remote mode — the flat session namespace would collide with the "
                            + "study's own define.xml. Reference members load as plain tables.");
                    continue;
                }
                members.add(file);
            }
        }
        catch (IOException e)
        {
            err.println("Error: cannot list reference data directory " + p + ": " + e.getMessage());
            return null;
        }
        return members;
    }


    /** Lists the bare file names {@link #uploadData} will stage from {@code -d}. */
    private static List<String> listStudyMembers(@Nullable String data) throws IOException
    {
        if (data == null)
        {
            return List.of();
        }
        // planReferenceData has already rejected a -d that does not resolve.
        Path p = Path.of(data);
        if (!Files.isDirectory(p))
        {
            return List.of(fileName(p));
        }
        try (var stream = Files.list(p))
        {
            return stream.filter(Files::isRegularFile).sorted().map(CdiscValidate::fileName)
                    .toList();
        }
    }


    /** True for the literal {@code define.xml} name the reference-library convention uses. */
    private static boolean isDefineXml(String filename)
    {
        return "define.xml".equalsIgnoreCase(filename);
    }


    /**
     * Normalises a dataset name or file name to the comparison key the engine uses when matching
     * {@code datasetFilter} entries against library members: the part before the last {@code .},
     * upper-cased. Mirrors {@code StudyValidationService.stripExtUpper}, which is package-private
     * in the engine module, so {@code dm.xpt}, {@code dm.json} and {@code DM} all compare equal.
     */
    static String datasetKey(String name)
    {
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        return base.toUpperCase(Locale.ROOT);
    }


    /**
     * Maps parsed args onto the REST check-request body (server-side-only params are omitted).
     * {@code datasetFilter} is supplied by the caller rather than read from {@code args.datasets}:
     * in {@code --remote} mode it carries the {@code -rd} complement (see
     * {@link #planReferenceData}), which is {@code -ds} minus the reference members.
     */
    private static Map<String, Object> buildRemoteRequest(Args args,
            @Nullable String defineXmlFilename, List<String> rulesFilenames,
            List<String> datasetFilter)
    {
        Map<String, Object> req = new LinkedHashMap<>();
        if (!args.metadataProducts.isEmpty())
        {
            // Raw tokens, resolved server-side against the server's own metadata cache.
            req.put("metadataProducts", new ArrayList<>(args.metadataProducts));
        }
        putIfNotNull(req, "useCase", args.useCase);
        putIfNotNull(req, "defineXmlFilename", defineXmlFilename);
        putIfNotNull(req, "defineVersion", args.defineVersion);
        if (!args.includeRules.isEmpty())
        {
            req.put("includeRules", args.includeRules);
        }
        if (!args.excludeRules.isEmpty())
        {
            req.put("excludeRules", args.excludeRules);
        }
        if (!args.rulesPackages.isEmpty())
        {
            req.put("rulesPackages", args.rulesPackages);
        }
        if (!rulesFilenames.isEmpty())
        {
            req.put("rulesFilenames", rulesFilenames);
        }
        if (!datasetFilter.isEmpty())
        {
            req.put("datasetFilter", new ArrayList<>(datasetFilter));
        }
        req.put("ruleThreads", args.ruleThreads);
        return req;
    }


    private static void putIfNotNull(Map<String, Object> map, String key, @Nullable Object value)
    {
        if (value != null)
        {
            map.put(key, value);
        }
    }


    private static boolean isRemoteTerminal(String status)
    {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }


    /**
     * The {@code Dictionary_Basis} value carried by a fetched remote report body, or {@code null}
     * when the run was healthy (the field is present only on a degraded run) or the body is not
     * JSON. {@code findValue} searches the tree, so both the v1 layout
     * ({@code Conformance_Details.Dictionary_Basis}) and any nesting the v2 report uses are found
     * without pinning this client to one shape.
     */
    private static @Nullable String dictionaryBasisOf(String aReportBody)
    {
        try
        {
            JsonNode basis = new com.fasterxml.jackson.databind.ObjectMapper().readTree(aReportBody)
                    .findValue("Dictionary_Basis");
            return basis != null && basis.isTextual() ? basis.asText() : null;
        }
        catch (com.fasterxml.jackson.core.JacksonException _)
        {
            return null; // Not JSON — the written report file still carries whatever was sent.
        }
    }


    /**
     * {@code --seed-cache} / {@code --seed-cache-from-dir}: fills the CDISC Library web-api cache
     * from the Python engine's pickle metadata, so users without an API key get a working cache.
     * Runs standalone and exits; no validation input is read.
     *
     * @param aArgs
     *            the parsed arguments.
     * @param aErr
     *            the error stream for usage and failure messages.
     * @return the process exit code: 0 on success, 2 on a usage error, 1 when the seeder throws,
     *         when the seed report carries warnings, or when the source yielded no cache entries at
     *         all — a seeding step that produced nothing is not a successful one.
     */
    private int seedCache(Args aArgs, PrintStream aErr)
    {
        if (aArgs.seedCache != null && aArgs.seedCacheFromDir != null)
        {
            aErr.println("Error: --seed-cache and --seed-cache-from-dir are mutually exclusive.");
            return 2;
        }

        // Same target resolution as every other Library-backed path: -ca wins, else the
        // CDISC_API_CACHE / cdisc.library.api.cache default (~/.cdiscApiCache).
        Path target = aArgs.cache != null ? Path.of(aArgs.cache).toAbsolutePath()
                : defaultApiCacheDir();
        String baseUrl = CdiscLibraryClient.getApiUrl();

        try (PickleSource source = buildPickleSource(aArgs))
        {
            SeedReport report = new PickleCacheSeeder().seed(SeedOptions
                    .builder(source, target, baseUrl).overwriteExisting(aArgs.seedOverwrite)
                    .dryRun(aArgs.seedDryRun).build());

            String verb = aArgs.seedDryRun ? "seeding (dry run)" : "seeded";
            LOGGER.log(System.Logger.Level.INFO, "Cache {0} at {1}: {2}", verb, target,
                    report.summary());
            // D3, the installDictionaries template: the summary, the skips and the warnings go to
            // the operator's stream, not only to the logger, and the report decides the exit code.
            // Until now every non-throwing seed returned 0 with its findings buried in JUL, so a
            // CI pipeline saw a green "seed the cache" step followed by a validation run against
            // a cache that had never been filled.
            aErr.println("Cache " + verb + " at " + target + ": " + report.summary());
            for (String skip : report.skipped())
            {
                aErr.println("  skipped: " + skip);
            }
            for (String warning : report.warnings())
            {
                LOGGER.log(System.Logger.Level.WARNING, "  {0}", warning);
                aErr.println("  warning: " + warning);
            }
            boolean nothingSeeded = report.written().isEmpty() && report.skipped().isEmpty();
            if (nothingSeeded)
            {
                aErr.println("Error: the seed source yielded no cache entries, so " + target
                        + " is unchanged. Check --seed-cache / --seed-ref / --seed-repo-path "
                        + "(or --seed-cache-from-dir): the run that follows would use an empty "
                        + "cache and reach the CDISC Library for everything.");
            }
            return nothingSeeded || !report.warnings().isEmpty() ? 1 : 0;
        }
        catch (IOException | RuntimeException e)
        {
            aErr.println("Error: cache seeding failed: " + e.getMessage());
            LOGGER.log(System.Logger.Level.DEBUG, "cache seeding failed", e);
            return 1;
        }
    }


    /** Chooses the download or local-directory pickle source from the seeding options. */
    private PickleSource buildPickleSource(Args aArgs) throws IOException
    {
        if (aArgs.seedCacheFromDir != null)
        {
            return new LocalPickleSource(Path.of(aArgs.seedCacheFromDir));
        }
        // arity="0..1" with fallbackValue="" means a bare --seed-cache yields "": use the default.
        String repo = aArgs.seedCache == null || aArgs.seedCache.isBlank()
                ? HttpArchivePickleSource.DEFAULT_REPO_URI
                : aArgs.seedCache;
        String repoPath = aArgs.seedRepoPath == null || aArgs.seedRepoPath.isBlank()
                ? HttpArchivePickleSource.DEFAULT_REPO_PATH
                : aArgs.seedRepoPath;
        return archives.forRepo(repo, aArgs.seedRef, repoPath, aArgs.seedArchiveUrlTemplate,
                aArgs.seedWorkDir == null ? null : Path.of(aArgs.seedWorkDir));
    }


    /**
     * The shared default web-api cache directory, matching {@code CdiscLibraryClient.getCache()}.
     */
    private static Path defaultApiCacheDir()
    {
        // Delegate to the client's own resolver: it checks the environment variable BEFORE the
        // system property. Re-implementing it with the opposite order would seed one directory
        // while CdiscLibraryClient reads another, leaving the user with an apparently-empty cache
        // and a success message.
        String configured = CdiscLibraryClient.retrieveSystemProperty(
                CdiscLibraryClient.ENV_CDISC_API_CACHE, CdiscLibraryClient.SP_CDISC_API_CACHE);
        if (configured != null && !configured.isBlank())
        {
            return Path.of(configured).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home", "."), ".cdiscApiCache").toAbsolutePath();
    }


    /**
     * {@code --install-dictionaries}: installs dictionaries into the versioned store, records each
     * selection in {@code selected-versions.json}, writes each type's licence notice beside its
     * data and its provenance into {@code SOURCES.md}, and exits — a standalone maintenance mode
     * structured like {@link #seedCache} (D3). No validation input is read, and the target
     * directory is <b>created</b> rather than required to exist (D4 exempts install mode from the
     * resolver's hard-error-on-missing rule).
     *
     * <p>
     * Acquisition (Phase 7a, D12: on the operator's machine, nothing redistributed): the three
     * credential-free dictionaries — MED-RT, UNII, neoplasm — are <b>downloaded</b> when their
     * option is given bare, and a run naming no input at all installs exactly those three. A local
     * directory always wins over a download. LOINC needs a free account and SNOMED, MedDRA and
     * WHODrug a licensed distribution, so those four read local paths only; requested bare, they
     * are reported as skipped with the reason, not treated as an error.
     * </p>
     *
     * @param aArgs
     *            the parsed arguments.
     * @param aErr
     *            the stream for the summary, warnings, and usage / failure messages.
     * @return the process exit code: 0 on success, 2 on a usage error, 1 when any type failed —
     *         failures are per type (D11 best-effort): the rest are still attempted and the
     *         summary, skipped and warning lines still print, so a partial install is never
     *         reported as "nothing installed".
     */
    private int installDictionaries(Args aArgs, PrintStream aErr)
    {
        Map<String, String> inputs = aArgs.installerInputs();
        if (inputs.isEmpty())
        {
            // Phase 7a: with no input named, install everything acquirable without credentials —
            // the single-command, zero-configuration route the plan requires for these three.
            inputs = new LinkedHashMap<>();
            for (String type : DOWNLOADABLE_TYPES)
            {
                inputs.put(type, "");
            }
        }
        Path target = installTargetDir(aArgs);
        boolean failed = false;
        try
        {
            if (!aArgs.dictDryRun)
            {
                Files.createDirectories(target);
            }
            DictionaryInstaller installer = new DictionaryInstaller(target, aArgs.dictDryRun,
                    aArgs.dictSkipInstalled);
            for (Map.Entry<String, String> e : inputs.entrySet())
            {
                String type = e.getKey();
                // D11's best-effort contract, matching the container entrypoints' auto-convert
                // loop: one type's failure (a typo'd path, one blocked download host) is recorded
                // and the REMAINING types are still attempted — and the summary below still
                // prints, so the operator is told what DID land. The exit code stays 1.
                try
                {
                    DictionarySource source;
                    if (!e.getValue().isBlank())
                    {
                        source = new LocalDictionarySource(Path.of(e.getValue()));
                    }
                    else if (DOWNLOADABLE_TYPES.contains(type))
                    {
                        source = downloads.forType(type);
                    }
                    else
                    {
                        installer.getReport().skipped(type + ": no local distribution path given — "
                                + ("loinc".equals(type)
                                        ? "downloading LOINC needs a free loinc.org account "
                                                + "and is not automated; download the release "
                                                + "and pass --loinc <dir>"
                                        : "this dictionary is supplied under your own licence; "
                                                + "pass --" + type + " <dir>"));
                        continue;
                    }
                    installer.install(source, converterFor(type),
                            DictionaryLicences.noticeFor(type),
                            DictionaryLicences.extraFilesFor(type), aArgs.setDefaultDictionaries);
                }
                catch (IOException | RuntimeException ex)
                {
                    failed = true;
                    installer.getReport()
                            .warning(type + ": dictionary install failed: " + ex.getMessage()
                                    + " — the remaining dictionaries were still " + "attempted");
                    LOGGER.log(System.Logger.Level.DEBUG, "dictionary install failed for " + type,
                            ex);
                }
            }
            InstallReport report = installer.getReport();
            // D3: verify the write landed before claiming success — a report entry is a claim,
            // the file on disk is the fact. A failed verification no longer aborts the summary:
            // the operator still needs to know what else was installed.
            if (!aArgs.dictDryRun)
            {
                for (Map.Entry<String, String> ins : report.getInstalled().entrySet())
                {
                    Path versionDir = target.resolve(ins.getKey()).resolve(ins.getValue());
                    if (!Files.isRegularFile(versionDir.resolve(ins.getKey() + ".json"))
                            && !Files.isRegularFile(versionDir.resolve(ins.getKey() + ".json.gz")))
                    {
                        failed = true;
                        aErr.println("Error: " + ins.getKey() + " " + ins.getValue()
                                + " was reported installed but no dictionary file exists at "
                                + versionDir);
                    }
                }
            }
            aErr.println((aArgs.dictDryRun ? "Dictionary install (dry run) into "
                    : "Dictionaries installed into ") + target + ": " + report.summary());
            for (String skip : report.getSkipped())
            {
                aErr.println("  skipped: " + skip);
            }
            for (String warning : report.getWarnings())
            {
                aErr.println("  warning: " + warning);
            }
            // Surface where each dictionary's terms landed (D12: the operator, not coreJ, is the
            // party bound by them), and where the provenance record is.
            if (!aArgs.dictDryRun && !report.getInstalled().isEmpty())
            {
                for (Map.Entry<String, String> ins : report.getInstalled().entrySet())
                {
                    aErr.println("  terms: "
                            + target.resolve(ins.getKey()).resolve(ins.getValue())
                                    .resolve("LICENSES")
                            + " — review the licence or terms-of-use notice written there");
                }
                aErr.println("  provenance: " + target.resolve(DictionaryInstaller.SOURCES_FILE));
            }
            return failed ? 1 : 0;
        }
        catch (IOException | RuntimeException e)
        {
            // Only failures OUTSIDE the per-type loop land here (e.g. the store root itself
            // cannot be created) — there is nothing partial to summarise.
            aErr.println("Error: dictionary install failed: " + e.getMessage());
            LOGGER.log(System.Logger.Level.DEBUG, "dictionary install failed", e);
            return 1;
        }
    }


    /**
     * The install target: D4 precedence (explicit flag &gt; {@code COREJ_DICTIONARIES_DIR} &gt;
     * {@code corej.dictionariesDir} &gt; {@code ./dictionaries}) — resolved as a plain path, never
     * through {@code DictionaryDirectoryResolver}, whose hard-error-on-missing contract must not
     * apply to a mode that creates its target.
     */
    private static Path installTargetDir(Args aArgs)
    {
        if (aArgs.dictionariesDir != null && !aArgs.dictionariesDir.isBlank())
        {
            return Path.of(aArgs.dictionariesDir);
        }
        String env = System.getenv(DictionaryDirectoryResolver.ENV_DIR);
        if (env != null && !env.isBlank())
        {
            return Path.of(env);
        }
        String sysProp = System.getProperty(DictionaryDirectoryResolver.SP_DIR);
        if (sysProp != null && !sysProp.isBlank())
        {
            return Path.of(sysProp);
        }
        return Path.of(DictionaryDirectoryResolver.DEFAULT_DIR);
    }


    /** One converter per installable dictionary type (D1: raw layouts are installer inputs). */
    private static DictionaryConverter converterFor(String aType)
    {
        return switch (aType)
        {
        case "meddra" -> new MedDraConverter();
        case "whodrug" -> new WhoDrugConverter();
        case "loinc" -> new LoincConverter();
        case "medrt" -> new MedRtConverter();
        case "unii" -> new UniiConverter();
        case "snomed" -> new SnomedConverter();
        case "neoplasm" -> new NeoplasmConverter();
        default -> throw new IllegalArgumentException("no converter for dictionary type " + aType);
        };
    }


    /**
     * The download source for one of the {@link #DOWNLOADABLE_TYPES}. The base URLs are overridable
     * through system properties — {@value #SP_EVS_BASE_URL} for the two NCI EVS artefact sets and
     * {@value #SP_UNII_ARCHIVE_URL} for precisionFDA — so tests (and a mirror deployment) can point
     * the installer at another host; the defaults are the public authorities.
     */
    static DictionaryDownloads httpDownloads()
    {
        return CdiscValidate::downloadSourceFor;
    }


    private static DictionarySource downloadSourceFor(String aType)
    {
        String evsBase = System.getProperty(SP_EVS_BASE_URL,
                HttpDictionarySource.DEFAULT_EVS_BASE_URL);
        return switch (aType)
        {
        case "medrt" -> HttpDictionarySource.medRt(evsBase);
        case "neoplasm" -> HttpDictionarySource.neoplasm(evsBase);
        case "unii" -> HttpDictionarySource.unii(System.getProperty(SP_UNII_ARCHIVE_URL,
                HttpDictionarySource.DEFAULT_UNII_ARCHIVE_URL));
        default -> throw new IllegalArgumentException("no download source for dictionary type "
                + aType + " — it needs an account or a licensed distribution");
        };
    }


    private int run(Args args, PrintStream err) throws Exception
    {
        // Fix #119: --define-first is a process-wide preference (the RuleRunner gate reads the
        // property directly, having no per-run parameter channel) — set it before any engine work.
        if (args.defineFirst)
        {
            System.setProperty("corej.defineFirst", "true");
        }
        // --dictionaries-dir travels as a per-run StudyValidationParams field (see toParams), NOT
        // as the corej.dictionariesDir system property: the resolver ranks the sysprop BELOW
        // COREJ_DICTIONARIES_DIR, which every Docker image sets, so a sysprop pass-through would
        // silently lose the flag to the container default (and defeat the resolver's hard error
        // on a typo'd path). Install mode reads the option directly (its target may not exist
        // yet); the sysprop stays the documented lowest configured tier for operators who set it
        // themselves.
        if (args.installDictionaries && (args.seedCache != null || args.seedCacheFromDir != null))
        {
            err.println("Error: --install-dictionaries and --seed-cache are mutually exclusive.");
            return 2;
        }
        // Cache seeding is a standalone maintenance mode: it populates the CDISC Library web-api
        // cache from the Python engine's pickles and exits, without touching any validation input.
        if (args.seedCache != null || args.seedCacheFromDir != null)
        {
            return seedCache(args, err);
        }
        // Dictionary installation is the second standalone maintenance mode (D3: same template
        // as --seed-cache): convert local vendor distributions into the store, then exit.
        if (args.installDictionaries)
        {
            return installDictionaries(args, err);
        }
        // The data library / define.xml existence pre-checks stay in the CLI: they own the
        // user-visible "Error: ..." stderr text and the exit-code-2 contract that callers and
        // tests rely on. The service performs equivalent guards for non-CLI callers.
        if (args.data == null && args.defineXmlPath == null)
        {
            err.println("Error: provide -d / --data (any library: directory, file, or URI) "
                    + "or -dxp / --define-xml-path (define.xml).");
            return 2;
        }
        if (args.defineXmlPath != null)
        {
            Path dxp = Path.of(args.defineXmlPath);
            if (!Files.exists(dxp))
            {
                // Usage error — file path supplied by user doesn't resolve. Stderr +
                // non-zero exit so it's visible regardless of log config.
                err.println("Error: define.xml not found: " + dxp);
                return 2;
            }
        }
        // The same pre-check for -d / --data, which the comment above claims ("the existence
        // pre-checks") but did not have. Without it a typo'd data directory reached the engine and
        // surfaced as an uncaught IOException stack trace rather than the exit-2 contract — and,
        // worse, with -vx the Define-XML report had ALREADY been written by then against the
        // typo's PARENT directory (see resolveDefineSubmissionFolder), so the Requires: folder
        // rules were evaluated against a folder the user never named. A URI library has no local
        // path to check; "" is left to resolveLibrary, which names it precisely.
        if (args.data != null && !args.data.isEmpty() && !isUriLibrary(args.data)
                && !Files.exists(Path.of(args.data)))
        {
            err.println("Error: data library not found: " + Path.of(args.data).toAbsolutePath());
            return 2;
        }

        IDataTableManager manager = new LocalDataTableManager();

        List<String> outputFormats = resolveOutputFormats(args.outputFormats);
        Path outputBase = resolveOutputBase(args.output);

        // Phase 7: -vx / --validate-xml runs the standalone Define-XML conformance validator on the
        // -dxp file, independent of the data validation below, and writes <base>.define.json. It
        // runs first so the conformance report is produced even if the data validation later
        // short-circuits (e.g. no rules selected). parse() has already guaranteed a -dxp path when
        // the flag is set, and the file's existence was checked at the top of this method.
        if (args.validateXml)
        {
            runDefineConformance(args, outputBase, err);
        }

        // Representative report path (drives the runtime-report file name, which is derived from
        // the
        // shared stem and so is independent of the chosen extension).
        String primaryExt = outputFormats.contains("json") ? "json" : outputFormats.get(0);
        Path outputPath = reportPathFor(outputBase, primaryExt);
        Path runtimePath = resolveRuntimeReportPath(args, outputPath);
        LOGGER.log(System.Logger.Level.INFO, "Writing per-rule runtime report to: {0}",
                runtimePath);

        StudyValidationResult result;
        try (BufferedWriter runtimeWriter = Files.newBufferedWriter(runtimePath,
                StandardCharsets.UTF_8))
        {
            runtimeWriter.write(
                    "domain,fileName,rowCount,columnCount,coreId,elapsedMs,status,violationCount\n");

            LibraryValidator.RuntimeListener listener = entry ->
            {
                try
                {
                    synchronized (runtimeWriter)
                    {
                        runtimeWriter.write(formatRuntimeRow(entry));
                    }
                }
                catch (IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            };

            StudyValidationParams params = toParams(args, manager, listener);
            try
            {
                result = new StudyValidationService(CORE_ENGINE_VERSION).validate(params);
            }
            catch (StudyValidationException e)
            {
                // Operational failure (no datasets, no rules, …). Stderr + exit so CLI users
                // always see it regardless of log config.
                err.println("Error: " + e.getMessage());
                return 2;
            }
        }

        // Phase 6b, D13 surface 5: a degraded dictionary run must say so where a CLI user
        // actually looks. One stderr line, printed only when the report carries a
        // Dictionary_Basis — i.e. absent whenever every dictionary rule in the run was
        // answerable, including the common case of a run with no dictionary rules at all.
        String dictionaryBasis = result.conformance().dictionaryBasis();
        if (dictionaryBasis != null)
        {
            err.println("Dictionary basis: " + dictionaryBasis);
        }

        // Phase E: write report(s) — one file per requested format, all sharing the base name.
        // Every format goes through the report SPI: the engine assembles neutral sections once and
        // the manager routes them to whichever writer module is on the classpath (Fix #224). There
        // is no per-format branch here any more, which is what makes a new format a pure packaging
        // change.
        ReportSections sections = result.sections();
        List<Path> writtenPaths = new ArrayList<>();
        for (String format : outputFormats)
        {
            Path path = reportPathFor(outputBase, format);
            ReportFormat reportFormat = REPORT_MANAGER.findReportFormat(format);
            if (reportFormat == null)
            {
                // Unreachable via parse() (the allowlist is the same registry) but a writer module
                // could in principle vanish between validation and write.
                err.println("Error: no report writer registered for --output-format " + format);
                return 2;
            }
            REPORT_MANAGER.writeReport(sections, path, reportFormat,
                    writerProperties(reportFormat, args));
            writtenPaths.add(path);
        }

        // Phase E2: per-dataset wall-clock runtime, written alongside the per-rule runtime CSV
        // (report-only, never printed to stdout).
        Path datasetRuntimePath = datasetRuntimePathFor(runtimePath);
        LOGGER.log(System.Logger.Level.INFO, "Writing per-dataset runtime report to: {0}",
                datasetRuntimePath);
        writeDatasetRuntimeReport(datasetRuntimePath, result);

        LOGGER.log(System.Logger.Level.INFO,
                "Validation complete: {0} findings across {1} dataset(s) in {2}s. Report: {3}",
                result.findingCount(), result.datasets().size(),
                String.format(Locale.ROOT, "%.2f", result.totalRuntimeSeconds()),
                writtenPaths.stream().map(Path::toString).collect(Collectors.joining(", ")));
        return 0;
    }


    /** Maps parsed CLI arguments onto the engine-relevant service parameters. */
    static StudyValidationParams toParams(Args args, IDataTableManager manager,
            LibraryValidator.RuntimeListener listener)
    {
        StudyValidationParams.Builder builder = StudyValidationParams.builder().manager(manager)
                .dataLibrary(args.data).defineXmlPath(args.defineXmlPath)
                .referenceData(args.referenceData).useCase(args.useCase)
                .metadataProducts(resolveMetadataProducts(args))
                .controlledTerminologyPackages(args.controlledTerminologyPackages)
                .defineVersion(args.defineVersion).includeRules(args.includeRules)
                .excludeRules(args.excludeRules).rulesDir(args.rulesDir)
                .rulesPackages(args.rulesPackages).rulesFiles(args.rulesFiles)
                .datasetFilter(args.datasets).ruleThreads(args.ruleThreads).cacheDir(args.cache)
                .pickleCacheDir(args.pickleCache).dictionariesDir(args.dictionariesDir)
                .dictionaryVersions(args.dictionaryVersions()).runtimeListener(listener);
        if (args.maxErrorsPerRule != null)
        {
            builder.maxErrorsPerRule(args.maxErrorsPerRule);
        }
        if (args.severityThreshold != null)
        {
            builder.severityThreshold(args.severityThreshold);
        }
        return builder.build();
    }


    /**
     * Resolves the {@code -mp} / {@code --metadata-products} tokens onto {@code standards/...}
     * cache keys via {@link ProductKeyResolver} (unique-suffix match against the configured product
     * catalogue — Phase 7b: the pickle cache's keys unioned with the CDISC Library API's
     * {@code /mdr/products} list, served from the {@code --cache} / {@code CDISC_API_CACHE}
     * directory when cached; with no source available only full-key tokens resolve). A failed
     * resolution is a usage error — every bad token is named at once. An omitted {@code -mp}
     * resolves to nothing here; the selected rule packages' <b>declared standards</b> then supply
     * the products (R7), which is why the flag is never mandatory. ⚠ Since R5 removed
     * {@code -s}/{@code -v} there is no implied default any more: a package that declares nothing
     * AND no {@code -mp} is refused by {@code StudyValidationService.runStandardOf}.
     */
    private static List<String> resolveMetadataProducts(Args args)
    {
        try
        {
            return ProductKeyResolver.resolveAllConfigured(args.metadataProducts, args.pickleCache,
                    args.cache);
        }
        catch (IllegalArgumentException e)
        {
            throw new UsageException(
                    e.getMessage() + " (acceptable product families: SDTM/SEND, ADaM, TIG)");
        }
    }

    // ------------------------------------------------------------------
    // Reporting helpers
    // ------------------------------------------------------------------


    private static String defaultOutputName()
    {
        String ts = LocalDateTime.now(ZoneId.systemDefault()).withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
        return "CORE-Report-" + ts + ".json";
    }

    /**
     * Known report extensions stripped when deriving the shared output base (Python parity). The
     * compound {@code .v2.json} (the combined-finding report) is listed before {@code .json} so the
     * longer suffix is stripped first.
     */
    private static final List<String> KNOWN_REPORT_EXTENSIONS = List.of(".v2.json", ".json",
            ".xlsx", ".xls");

    /**
     * Normalises the requested {@code --output-format} values: lower-cased, de-duplicated, original
     * order preserved; defaults to {@code [json]} when none were supplied.
     */
    private static List<String> resolveOutputFormats(Set<String> requested)
    {
        if (requested.isEmpty())
        {
            return List.of("json");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String f : requested)
        {
            out.add(canonicalFormat(f));
        }
        return List.copyOf(out);
    }


    /**
     * Resolves the shared output base — the absolute output path with any known report extension
     * ({@code .json} / {@code .xlsx} / {@code .xls}) stripped — so every requested format is
     * written from the same stem with only the extension swapped. Falls back to the timestamped
     * default name when {@code --output} is not given.
     */
    private static Path resolveOutputBase(@Nullable String output)
    {
        Path raw = Path.of(output != null ? output : defaultOutputName()).toAbsolutePath();
        String name = fileName(raw);
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : KNOWN_REPORT_EXTENSIONS)
        {
            if (lower.endsWith(ext))
            {
                name = name.substring(0, name.length() - ext.length());
                break;
            }
        }
        return raw.resolveSibling(name);
    }


    /**
     * Builds the report path for one format by appending the suffix the registered
     * {@link ReportFormat} declares. {@code json-2} declares {@code .v2.json}, a double extension,
     * so a combined {@code -of json,json-2} run produces {@code <base>.json} +
     * {@code <base>.v2.json} without collision — the suffix is data on the format, not a special
     * case here. An unregistered format falls back to {@code .<name>} so the error path still
     * produces a sane path.
     */
    private static Path reportPathFor(Path base, String format)
    {
        ReportFormat rf = REPORT_MANAGER.findReportFormat(canonicalFormat(format));
        String suffix = rf != null ? rf.fileSuffix() : "." + format;
        return base.resolveSibling(fileName(base) + suffix);
    }


    /**
     * Maps a user-supplied {@code --output-format} token to a registered format name.
     *
     * <p>
     * Lower-cases, and translates the one legacy alias: the CLI shipped {@code json2} before the
     * SPI made {@code json-2} the canonical name (Fix #224). The alias stays accepted forever so no
     * existing invocation or script breaks; only the canonical name is advertised.
     * </p>
     */
    private static String canonicalFormat(String format)
    {
        String lower = format.toLowerCase(Locale.ROOT);
        return "json2".equals(lower) ? "json-2" : lower;
    }


    /** The names of every registered report format, sorted, for help text and error messages. */
    private static List<String> supportedFormatNames()
    {
        return REPORT_MANAGER.getSupportedReportFormats().stream().map(ReportFormat::name).sorted()
                .toList();
    }


    /**
     * Translates the CLI's own options into the writer's <em>declared</em> properties.
     *
     * <p>
     * Only {@code -mr / --max-report-rows} has a counterpart today, and it is matched by the
     * property <b>name</b> the writer declares rather than by importing the writer's class — which
     * is the whole point: the CLI does not depend on {@code corej-cdisc-report-xlsx}. A format that
     * does not declare {@code maxRowsPerSheet} simply gets an empty map.
     * </p>
     */
    private static Map<Property, String> writerProperties(ReportFormat format, Args args)
    {
        if (args.maxReportRows == null)
        {
            return Map.of();
        }
        Map<Property, String> properties = new LinkedHashMap<>();
        for (Property property : ServiceReportManager.getInstance().getWriterProperties(format))
        {
            if (MAX_ROWS_PER_SHEET.equals(property.name()))
            {
                properties.put(property, String.valueOf(args.maxReportRows));
            }
        }
        return properties;
    }


    /**
     * The path's file name as a string, or empty for a root path ({@code getFileName() == null}).
     */
    private static String fileName(Path path)
    {
        Path name = path.getFileName();
        return name != null ? name.toString() : "";
    }

    // ------------------------------------------------------------------
    // Define-XML conformance (-vx / --validate-xml, Phase 7)
    // ------------------------------------------------------------------


    /**
     * Runs the standalone Define-XML conformance validator on the {@code -dxp} file and writes its
     * JSON report to {@code <output-base>.define.json}.
     *
     * <p>
     * <b>Controlled Terminology is intentionally not bound here.</b> The engine's
     * {@code Requires: ct} skip gate keys off the <em>presence</em> of a {@code CtProvider}, not
     * per-check capability: supplying any provider un-skips every CT-backed check at once. The CT
     * checks that could be served soundly from the CLI's {@code MetadataProvider}
     * ({@code term_in_ct_codelist} membership) share that provider with checks that cannot
     * ({@code nci_code_known} / {@code term_matches_nci_code} need term-level NCI c-codes, which
     * {@code MetadataProvider} does not expose; {@code extended_value_marking} needs the
     * authoritative per-codelist extensible flag). Binding a partial provider would therefore turn
     * clean SKIPs into false-positive findings for those checks. Correctness-first, we pass
     * {@code null} so all {@code Requires: ct} rules SKIP ({@code SKIPPED_MISSING_CT}). A sound
     * binding is a follow-up that needs both a {@code MetadataProvider} term-c-code extension and a
     * per-check capability gate in the conformance engine.
     * </p>
     *
     * <p>
     * The submission folder for {@code Requires: folder} rules is the {@code -d} data directory
     * when one is given (a single {@code -d} file contributes its parent directory), otherwise the
     * define.xml's own parent directory.
     * </p>
     *
     * <p>
     * <b>The IG library IS bound</b> (unlike CT): {@code Requires: library} rules get a
     * {@link CdiscLibraryBackedLibraryProvider} over the Library API whenever an API key is
     * configured ({@code CDISC_API_KEY} / {@code cdisc.library.api.key} — the same signal that
     * gates metadata enrichment), honouring the {@code -ca} cache directory. This is safe where the
     * CT binding is not: the {@code LibraryProvider} SPI treats every empty answer as "out of the
     * rule's reach", so a partial or failing provider can only under-report, never turn a clean
     * SKIP into a false positive. Without an API key the library rules SKIP
     * ({@code SKIPPED_MISSING_LIBRARY}).
     * </p>
     */
    private void runDefineConformance(Args args, Path outputBase, PrintStream err)
        throws IOException
    {
        Path defineXml = Path
                .of(java.util.Objects.requireNonNull(args.defineXmlPath, "defineXmlPath"))
                .toAbsolutePath();
        DefineConformanceInput.Builder builder = DefineConformanceInput.builder(defineXml)
                .useDefaultSubmissionFolder(true);
        @Nullable
        CdiscLibraryBackedLibraryProvider libraryProvider = null;
        Path submissionFolder = resolveDefineSubmissionFolder(args);
        if (submissionFolder != null)
        {
            builder.submissionFolder(submissionFolder);
        }
        String versionOverride = normalizeDefineVersion(args.defineVersion);
        if (versionOverride != null)
        {
            builder.versionOverride(versionOverride);
        }
        libraryProvider = library.provider(args.cache);
        if (libraryProvider != null)
        {
            builder.libraryProvider(libraryProvider);
        }

        // Selection intent, not a corpus: the engine resolves the packages itself, because the
        // (family, version) key is only complete once it has read the document's version.
        builder.rulesDir(args.defineRulesDir == null ? null : Path.of(args.defineRulesDir))
                .families(parseDefineFamilies(args.defineFamilies))
                .rulesFiles(args.defineRulesFiles.stream().map(Path::of).toList());

        // Pre-flight the one input the caller supplies directly. This used to be covered by a
        // blanket catch(UncheckedIOException) around validate(), which also swallowed engine-side
        // I/O faults — XsdValidator's "cannot read vendored schema" from a corrupt bundle jar
        // reached the operator as "invalid Define-XML rules configuration", sending them to
        // inspect their own setup for a packaging fault. Checking here keeps the usage error and
        // lets genuine engine faults surface as themselves.
        // isRegularFile AND isReadable: isReadable alone is true for a DIRECTORY, so `-dxp
        // study/define` (the folder, not the file) cleared this check, reached readAllBytes and
        // threw an uncaught UncheckedIOException — a raw stack trace where the blanket catch this
        // replaced had given a clean usage error. The upstream -dxp guard is Files.exists only,
        // so it does not cover it either.
        if (!Files.isRegularFile(defineXml) || !Files.isReadable(defineXml))
        {
            throw new UsageException("cannot read the define.xml given by -dxp: " + defineXml
                    + (Files.isDirectory(defineXml) ? " (that is a directory)" : ""));
        }

        DefineConformanceInput input = builder.build();
        DefineConformanceReport report;
        try
        {
            report = new DefineConformanceEngine().validate(input);
        }
        catch (DefineRuleSelectionException e)
        {
            // Selection and corpus problems are the caller's to fix, so they surface as usage
            // errors. An evaluation-time fault is NOT, and must propagate: reporting "invalid
            // rules configuration" for a CustomCheck whose constructor throws sends the operator
            // to inspect their own setup for an engine bug. The distinction is carried by the
            // exception TYPE — it was briefly carried by substring-matching the message, which
            // would have silently reclassified errors the first time one was reworded.
            String message = e.getMessage();
            throw new UsageException(
                    message != null ? message : "invalid Define-XML rule selection");
        }

        Path target = outputBase.resolveSibling(fileName(outputBase) + ".define.json");
        JsonReportWriter.write(report, target);

        // The Library counterpart of D13 surface 5 (the "Dictionary basis:" line): a Library
        // outage makes every library-gated rule answer empty, which the SPI treats as "out of the
        // rule's reach" — so a fully degraded run produces a report that looks exactly like one
        // where those rules genuinely passed. The report record itself carries no degradation
        // field (it is the engine's, in cumba-corej), so the CLI at least says so where a CLI user
        // looks, instead of leaving it in a single JUL WARNING.
        printLibraryBasis(libraryProvider, err);

        String byCategory = report.findingsByCategory().entrySet().stream()
                .map(e -> e.getKey().name() + "="
                        + e.getValue().values().stream().mapToLong(Long::longValue).sum())
                .collect(Collectors.joining(", "));
        LOGGER.log(System.Logger.Level.INFO,
                "Define-XML conformance ({0}) complete: {1} findings [{2}]. Report: {3}",
                report.defineVersion(), report.totalFindings(),
                byCategory.isEmpty() ? "none" : byCategory, target);
    }


    /**
     * D13 surface 5's Library counterpart: says on <b>stderr</b> that a CDISC Library outage
     * silently narrowed this Define-XML report.
     *
     * <p>
     * The {@code LibraryProvider} SPI turns every failed lookup into an empty answer, which the
     * engine reads as "out of the rule's reach" — so a fully degraded run produces a report that
     * looks exactly like one where the library-gated rules genuinely passed. The report record
     * carries no degradation field (it is the engine's, in {@code cumba-corej}), so the CLI says it
     * where a CLI user looks instead of leaving it in a single JUL WARNING.
     * </p>
     *
     * <p>
     * Package-private and separate from {@link #runDefineConformance} for one reason: this is the
     * user-facing surface of F-cli-06 and it must be assertable without a live Library outage.
     * Inlined, both its guard and its {@code println} were unkillable (C2-04) — the line could be
     * deleted outright and the suite stayed green.
     * </p>
     *
     * @param aProvider
     *            the run's Library provider, or {@code null} when no API key was configured and no
     *            lookup was ever attempted
     * @param aErr
     *            the operator's stream
     */
    static void printLibraryBasis(@Nullable CdiscLibraryBackedLibraryProvider aProvider,
            PrintStream aErr)
    {
        List<String> degraded = aProvider == null ? List.of() : aProvider.degradedLookups();
        if (degraded.isEmpty())
        {
            return;
        }
        aErr.println("Library basis: " + degraded.size() + " CDISC Library lookup(s) failed ("
                + String.join(", ", degraded) + "); the library-gated Define-XML rules could "
                + "not fire for them, so this report under-reports.");
    }


    /**
     * The submission folder for the Define-XML {@code Requires: folder} rules: the {@code -d} data
     * directory when given (a single {@code -d} file → its parent), or {@code null} to let the
     * {@link DefineConformanceInput} default resolve the define.xml's own parent. A {@code -d} URI
     * has no local folder, so it also falls through to {@code null}.
     */
    private static @Nullable Path resolveDefineSubmissionFolder(Args args)
    {
        if (args.data == null || isUriLibrary(args.data))
        {
            return null;
        }
        Path dataPath = Path.of(args.data).toAbsolutePath();
        if (Files.isDirectory(dataPath))
        {
            return dataPath;
        }
        if (!Files.isRegularFile(dataPath))
        {
            // Neither a directory nor a file. Returning getParent() here invented a SIBLING
            // directory: `-d study/dta` (a typo for study/data) handed study/ to the
            // Requires: folder rules, which then reported on a folder the user never named.
            // Fall through to the documented default — the define.xml's own parent.
            return null;
        }
        return dataPath.getParent();
    }


    /**
     * Whether a {@code -d / --data} value is a URI ({@code scheme://…}) rather than a local path.
     * One spelling shared by remote mode, the {@code -d} existence pre-check and the Define-XML
     * submission-folder resolution — it used to be written out three times.
     */
    private static boolean isUriLibrary(String aData)
    {
        return aData.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    }


    /**
     * Parses {@code --define-family} values into {@link RuleSet} constants, case-insensitively. An
     * unknown name is a usage error naming the accepted values — the alternative, dropping it,
     * would silently narrow the run.
     */
    private static List<RuleSet> parseDefineFamilies(List<String> aRaw)
    {
        List<RuleSet> families = new ArrayList<>();
        for (String raw : aRaw)
        {
            String token = raw.trim();
            if (token.isEmpty())
            {
                continue;
            }
            try
            {
                RuleSet family = RuleSet.valueOf(token.toUpperCase(Locale.ROOT));
                if (!families.contains(family))
                {
                    families.add(family);
                }
            }
            catch (IllegalArgumentException _)
            {
                throw new UsageException("unknown --define-family \"" + token + "\"; expected one"
                        + " of " + java.util.Arrays.toString(RuleSet.values()));
            }
        }
        return families;
    }


    /**
     * Normalises the {@code -dv / --define-version} value. The engine expects {@code "2.0"} /
     * {@code "2.1"}; the flag also accepts the dashed form ({@code "2-1"}). Absent stays absent
     * (the document's own version is used); anything else is a usage error.
     *
     * <p>
     * ⚑ A bogus value used to degrade silently to auto-detect. That was survivable while the
     * version only gated already-loaded rules — it now selects the rule <em>package</em>, so a typo
     * would validate the document against a version nobody chose.
     * </p>
     */
    private static @Nullable String normalizeDefineVersion(@Nullable String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return null;
        }
        String v = raw.trim().replace('-', '.');
        if (!"2.0".equals(v) && !"2.1".equals(v))
        {
            // Until 2026-09-01 a bogus value silently degraded to auto-detect. That was tolerable
            // while the version only gated rules; it now SELECTS the rule package, so a typo would
            // quietly validate against a version the user did not ask for.
            throw new UsageException("-dv / --define-version must be 2.0 or 2.1 (got \"" + raw
                    + "\"). It selects the Define-XML rule package, so it is not guessed.");
        }
        return v;
    }


    private static Path resolveRuntimeReportPath(Args args, Path outputPath)
    {
        if (args.runtimeReport != null)
        {
            return Path.of(args.runtimeReport).toAbsolutePath();
        }
        Path abs = outputPath.toAbsolutePath();
        String name = abs.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return abs.resolveSibling(stem + ".runtime.csv");
    }


    /**
     * Sibling of the per-rule runtime CSV holding per-dataset wall-clock runtime: the per-rule
     * file's name with a trailing {@code .csv} replaced by {@code -datasets.csv} (or
     * {@code -datasets.csv} appended when it carries no {@code .csv} suffix).
     */
    private static Path datasetRuntimePathFor(Path runtimePath)
    {
        Path fileName = runtimePath.getFileName();
        String name = fileName != null ? fileName.toString() : "";
        String stem = name.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? name.substring(0, name.length() - ".csv".length())
                : name;
        return runtimePath.resolveSibling(stem + "-datasets.csv");
    }


    /**
     * Writes one CSV row per dataset: domain, fileName, wall-clock runtimeMs, rulesExecuted,
     * findings.
     */
    private static void writeDatasetRuntimeReport(Path path, StudyValidationResult result)
        throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8))
        {
            writer.write("domain,fileName,runtimeMs,rulesExecuted,findings\n");
            for (DatasetExecutionSummary s : result.executionSummaries())
            {
                StringBuilder sb = new StringBuilder(64);
                appendCsv(sb, s.domain());
                sb.append(',');
                appendCsv(sb, s.fileName());
                sb.append(',').append(s.runtimeMillis());
                sb.append(',').append(s.rulesExecuted());
                sb.append(',').append(s.findings());
                sb.append('\n');
                writer.write(sb.toString());
            }
        }
    }


    private static String formatRuntimeRow(LibraryValidator.RuntimeEntry e)
    {
        StringBuilder sb = new StringBuilder(128);
        appendCsv(sb, e.domain());
        sb.append(',');
        appendCsv(sb, e.fileName());
        sb.append(',').append(e.rowCount());
        sb.append(',').append(e.columnCount());
        sb.append(',');
        appendCsv(sb, e.coreId());
        sb.append(',').append(e.elapsedMillis());
        sb.append(',');
        appendCsv(sb, e.status() != null ? e.status().name() : null);
        sb.append(',').append(e.violationCount());
        sb.append('\n');
        return sb.toString();
    }


    private static void appendCsv(StringBuilder sb, @Nullable String value)
    {
        if (value == null)
        {
            return;
        }
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote)
        {
            sb.append(value);
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c == '"')
            {
                sb.append('"').append('"');
            }
            else
            {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Argument parsing
    // ------------------------------------------------------------------


    private static void printUsage(PrintStream out)
    {
        out.println("Usage: CdiscValidate [options]");
        out.println();
        out.println("Required:");
        out.println("  -rp, --rules-package <name>      rule package short name, e.g.");
        out.println("                                   cdisc-sdtmig-3-4 (comma-separated and/or");
        out.println("                                   repeatable)");
        out.println();
        out.println("Data inputs (one of):");
        out.println("  -d,  --data <path|URI>           data library: directory, single file");
        out.println(
                "                                   (.sas7bdat, .xpt, .xlsx, .rda, .dsj, .parquet,");
        out.println("                                   …), or URI (file://, ssh://, …)");
        out.println("  -dxp,--define-xml-path <file>    define.xml. With -d: metadata enrichment.");
        out.println("                                   Without -d: acts as the data library too.");
        out.println();
        out.println("Output:");
        out.println(
                "  -o,  --output <file>             output file (default CORE-Report-<ts>.json)");
        out.println("  -of, --output-format " + String.join("|", supportedFormatNames()));
        out.println(
                "                                   output format(s); repeatable/comma-separated (default json)");
        out.println(
                "                                   json-2 = combined-finding report (<base>.v2.json)");
        out.println(
                "  -mr, --max-report-rows <n>       per-sheet row cap for xlsx (default 10000; 0 = unlimited)");
        out.println("  -vx, --validate-xml [y|n]        also run Define-XML conformance on -dxp,");
        out.println(
                "                                   writing <output-base>.define.json (local mode)");
        out.println(
                "       --define-rules-dir <dir>    Define-XML rules directory for -vx (default:");
        out.println(
                "                                   $COREJ_DEFINE_RULES_DIR / -Dcorej.define.rules.dir");
        out.println("                                   / ./rules-define)");
        out.println("       --define-family <sheet>     REQUIRED for -vx: CDISC and/or PMDA");
        out.println(
                "                                   (repeatable or comma-separated; no default)");
        out.println("       --define-rules-file <file>  extra Define-XML rule PACKAGE for -vx");
        out.println("                                   (repeatable; generated JSON, not YAML)");
        out.println();
        out.println("Optional:");
        out.println("  -mp, --metadata-products <p>[,<p>..]");
        out.println("                                   ordered CDISC Library products consulted");
        out.println("                                   for metadata, highest precedence first");
        out.println(
                "                                   (e.g. adam/adamig-1-3,adam/adam-occds-1-1;");
        out.println("                                   a bare product id is accepted when");
        out.println("                                   unambiguous). Metadata only - rules are");
        out.println("                                   selected by -rp. Default: the standards");
        out.println("                                   the selected packages declare.");
        out.println("  -uc, --use-case <uc>             TIG use case");
        out.println("  --define-first                   prefer declared define.xml class/subclass "
                + "over heuristics (the default)");
        out.println("  -ct, --controlled-terminology-package <pkg>");
        out.println("                                   CT package id, repeatable");
        out.println("  -dv, --define-version <ver>      Define-XML version");
        out.println(
                "  -r,  --rules <CORE-id>           include only matching rule ids (repeatable)");
        out.println("  -er, --exclude-rules <CORE-id>   exclude matching rule ids (repeatable)");
        out.println(
                "  -sl, --severity-level <level>    weakest check level evaluated: Reject, Error,");
        out.println(
                "                                   Warning (default) or Info. Levels below it are");
        out.println(
                "                                   not evaluated; a rule with no level at or above");
        out.println("                                   it is SKIPPED, with that reason stated.");
        out.println(
                "  -me, --max-errors-per-rule <n>   findings materialised per rule, per dataset");
        out.println(
                "                                   (default 1000, from -Dcorej.maxErrorsPerRule");
        out.println(
                "                                   then MAX_ERRORS_PER_RULE; 0 = unlimited, a");
        out.println("                                   negative value is a usage error).");
        out.println("                                   Extra violations are counted, not listed.");
        out.println(
                "  -ds, --dataset <name>[,<name>..] validate only these library members (others");
        out.println(
                "                                   become lazy references; repeatable, comma-sep)");
        out.println(
                "  -rd, --reference-data <path>     extra reference library (dir with define.xml");
        out.println(
                "                                   or define.xml directly); repeatable. Use to");
        out.println("                                   provide SDTM data when validating ADaM.");
        out.println(
                "  -t,  --threads <n>               rule worker threads per dataset (default 1;");
        out.println("                                   max = available CPU cores; <1 errors out)");
        out.println("  -ca, --cache <dir>               cache dir for CDISC Library API");
        out.println(
                "  -pc, --pickle-cache <dir>        Python pickle metadata cache dir (offline;");
        out.println(
                "                                   SDTM/SEND and ADaM runs; skips the API when it");
        out.println("                                   carries the run's metadata products)");
        out.println();
        out.println("Cache seeding (no API key required; runs standalone and exits):");
        out.println("       --seed-cache [<repoUri>]    fill the CDISC Library web-api cache from");
        out.println("                                   the Python engine's pickle cache. Default");
        out.println(
                "                                   repo: github.com/cdisc-org/cdisc-rules-engine");
        out.println("       --seed-cache-from-dir <dir> seed from an existing pickle directory");
        out.println("       --seed-ref <ref>            branch/tag/commit (default: HEAD branch)");
        out.println(
                "       --seed-repo-path <path>     path in the repo (default resources/cache)");
        out.println("       --seed-archive-url-template <t>");
        out.println("                                   archive URL with {repo} / {ref}");
        out.println("       --seed-work-dir <dir>       keep extracted pickles here");
        out.println("       --seed-overwrite            replace entries that already exist");
        out.println("       --seed-dry-run              report without writing");
        out.println();
        out.println("External dictionaries:");
        out.println("       --dictionaries-dir <dir>    installed-dictionary store (default:");
        out.println("                                   COREJ_DICTIONARIES_DIR /");
        out.println("                                   -Dcorej.dictionariesDir / ./dictionaries)");
        out.println("       --meddra-version <v>  --whodrug-version <v>  --loinc-version <v>");
        out.println("       --medrt-version <v>   --unii-version <v>     --neoplasm-version <v>");
        out.println("       --snomed-version-select <v>");
        out.println("                                   bind an installed dictionary version for");
        out.println("                                   this run. Precedence: these options, then");
        out.println("                                   the define.xml ExternalCodeList Version,");
        out.println("                                   then the store's selected-versions.json.");
        out.println(
                "                                   A version that is not installed SKIPs that");
        out.println(
                "                                   dictionary's rules (never a substitution).");
        out.println("       --install-dictionaries      install dictionaries into the store, then");
        out.println("                                   exit. With no inputs, downloads the");
        out.println("                                   credential-free MED-RT, UNII and neoplasm");
        out.println(
                "                                   (so does a bare --medrt/--unii/--neoplasm).");
        out.println("                                   Local vendor distributions:");
        out.println(
                "                                   --meddra <dir> --whodrug <dir> --loinc <dir>");
        out.println("                                   --medrt <dir> --unii <dir> --snomed <dir>");
        out.println("                                   --neoplasm <dir>");
        out.println("       --set-default               rebind an existing manifest selection to");
        out.println("                                   the version being installed");
        out.println("       --skip-installed            leave a type/version that is already in");
        out.println("                                   the store untouched (idempotent re-runs,");
        out.println("                                   e.g. a container entrypoint)");
        out.println("       --dry-run                   report what an install would write,");
        out.println("                                   writing nothing");
        out.println();
        out.println("Java-only extras:");
        out.println("       --rules-dir <dir>           rules directory");
        out.println("       --rules-file <file>         extra rule package file (repeatable)");
        out.println("       --runtime-report <file>     CSV file for per-(dataset, rule) runtime");
        out.println("                                   (default: <output>.runtime.csv; columns:");
        out.println("                                   domain,fileName,rowCount,columnCount,");
        out.println("                                   coreId,elapsedMs,status,violationCount)");
        out.println();
        out.println("  -h,  --help                      show this help");
        out.println();
        out.println("Remote mode (run the check on a remote REST service):");
        out.println("       --remote <baseUrl>          REST service base URL; same effect as");
        out.println(
                "                                   -Dcorej.remote.url. When set, the local data");
        out.println("                                   / define.xml / rules-file / -rd reference");
        out.println("                                   data are uploaded and the run executes");
        out.println("                                   server-side; the report is written to -o.");
        out.println("                                   (-d must be a local path.)");
        out.println("                                   -rd becomes a datasetFilter complement:");
        out.println("                                   its members are uploaded but never");
        out.println("                                   validated. With -ds the filter is");
        out.println("                                   -ds MINUS -rd — -rd always wins.");
        out.println("       --remote-user <user>        HTTP Basic user (-Dcorej.remote.user)");
        out.println(
                "       --remote-password <pass>    HTTP Basic password (-Dcorej.remote.password)");
        out.println("       --remote-token <token>      Bearer token (-Dcorej.remote.token); wins");
        out.println("                                   over basic auth when both are set");
        out.println();
        out.println("Authentication (CDISC Library, local mode):");
        out.println("  Set CDISC_API_KEY env var or -Dcdisc.library.api.key system property.");
    }

    /** Parsed CLI arguments. */
    @Command(name = "CdiscValidate")
    static final class Args
    {

        @Option(names =
        {
                "-mp", "--metadata-products"
        }, split = ",",
                description = "Ordered CDISC Library products consulted for metadata, highest "
                        + "precedence first (e.g. adam/adamig-1-3,adam/adam-occds-1-1). A bare "
                        + "product id is accepted when unambiguous. Selects METADATA only - rules "
                        + "are selected by -rp / --rules-package. Default: the standards the "
                        + "selected rule packages declare.")
        List<String> metadataProducts = new ArrayList<>();

        @Option(names =
        {
                "-uc", "--use-case"
        })
        @Nullable
        String useCase;

        @Option(names =
        {
                "--define-first"
        }, description = "Prefer declared Define-XML class/subclass values (def:Class / "
                + "def:SubClass) over the column heuristics when determining a dataset's ADaM "
                + "data structure and subclass for Scope.Data_Structures / Scope.Subclasses "
                + "scoped rules. This is the DEFAULT since Fix #154, so the flag now only makes "
                + "it explicit; pass -Dcorej.defineFirst=false to get the old columns-first "
                + "behaviour, where the declaration is only a fallback. Sets the process-wide "
                + "corej.defineFirst property (Fix #119).")
        boolean defineFirst;

        @Option(names =
        {
                "-d", "--data"
        })
        @Nullable
        String data;

        @Option(names =
        {
                "-dxp", "--define-xml-path"
        })
        @Nullable
        String defineXmlPath;

        @Option(names =
        {
                "-dv", "--define-version"
        })
        @Nullable
        String defineVersion;

        @Option(names =
        {
                "-ct", "--controlled-terminology-package"
        })
        List<String> controlledTerminologyPackages = new ArrayList<>();

        @Option(names =
        {
                "-o", "--output"
        })
        @Nullable
        String output;

        @Option(names =
        {
                "-of", "--output-format"
        }, split = ",")
        Set<String> outputFormats = new LinkedHashSet<>();

        @Option(names =
        {
                "-mr", "--max-report-rows"
        })
        @Nullable
        Integer maxReportRows;

        @Option(names =
        {
                "-r", "--rules"
        })
        List<String> includeRules = new ArrayList<>();

        @Option(names =
        {
                "-er", "--exclude-rules"
        })
        List<String> excludeRules = new ArrayList<>();

        @Option(names =
        {
                "-ds", "--dataset"
        }, split = ",")
        Set<String> datasets = new LinkedHashSet<>();

        @Option(names =
        {
                "-rd", "--reference-data"
        })
        List<String> referenceData = new ArrayList<>();

        @Option(names =
        {
                "-ca", "--cache"
        })
        @Nullable
        String cache;

        @Option(names = "--seed-cache", arity = "0..1", fallbackValue = "",
                description = "Seed the CDISC Library web-api cache from the Python engine's "
                        + "pickle cache, then exit. Optional value is the repository URI "
                        + "(default: " + HttpArchivePickleSource.DEFAULT_REPO_URI + "). Writes to "
                        + "-ca / --cache, else CDISC_API_CACHE, else ~/.cdiscApiCache.")
        @Nullable
        String seedCache;

        @Option(names = "--seed-cache-from-dir",
                description = "Seed from an existing pickle directory instead of downloading "
                        + "(mutually exclusive with --seed-cache).")
        @Nullable
        String seedCacheFromDir;

        @Option(names = "--seed-ref",
                description = "Branch, tag or commit to fetch; default is the repository's "
                        + "advertised default branch.")
        @Nullable
        String seedRef;

        @Option(names = "--seed-repo-path",
                description = "Path inside the repository holding the " + "*.pkl files (default: "
                        + HttpArchivePickleSource.DEFAULT_REPO_PATH + ").")
        @Nullable
        String seedRepoPath;

        @Option(names = "--seed-archive-url-template",
                description = "Archive URL template with {repo} and {ref} placeholders "
                        + "(default: " + HttpArchivePickleSource.DEFAULT_ARCHIVE_URL_TEMPLATE
                        + "). GitLab needs {repo}/-/archive/{ref}/x.tar.gz.")
        @Nullable
        String seedArchiveUrlTemplate;

        @Option(names = "--seed-work-dir",
                description = "Keep extracted pickles here instead of a deleted temp directory.")
        @Nullable
        String seedWorkDir;

        @Option(names = "--seed-overwrite",
                description = "Overwrite cache entries that already exist (default: skip them).")
        boolean seedOverwrite;

        @Option(names = "--seed-dry-run",
                description = "Report what seeding would write without writing anything.")
        boolean seedDryRun;

        @Option(names =
        {
                "-pc", "--pickle-cache"
        }, description = "Python pickle metadata cache dir (offline). Overrides "
                + "CDISC_PICKLE_CACHE_DIR / cdisc.pickle.cache.dir. Serves SDTM-family "
                + "(SDTMIG/SENDIG) and ADaM-family (ADaMIG, and a declared TIG ADaM leg) runs; "
                + "when set and it carries the run's metadata products, the CDISC Library API is "
                + "not contacted.")
        @Nullable
        String pickleCache;

        @Option(names = "--rules-dir")
        @Nullable
        String rulesDir;

        @Option(names =
        {
                "-rp", "--rules-package"
        }, split = ",",
                description = "Rule packages to run, by short name (e.g. cdisc-adamig-1-3 for "
                        + "rules-cdisc-adamig-1-3.json). Comma-separated and/or repeatable. The "
                        + "rules- prefix and .json suffix are invariant. Unioned with "
                        + "--rules-file; together they replace the conventional "
                        + "family/standard/version selection.")
        List<String> rulesPackages = new ArrayList<>();

        @Option(names = "--rules-file")
        List<String> rulesFiles = new ArrayList<>();

        @Option(names = "--define-rules-dir")
        @Nullable
        String defineRulesDir;

        @Option(names = "--define-rules-file")
        List<String> defineRulesFiles = new ArrayList<>();

        /**
         * Which Define-XML rule sheets to validate against. Repeatable and MANDATORY for -vx:
         * before 2026-09-01 an unspecified selection silently meant "every rule of both sheets",
         * which is a choice, not the absence of one. The version half is taken from the document
         * (override with -dv), so this is the only part the user must state.
         */
        @Option(names = "--define-family", split = ",")
        List<String> defineFamilies = new ArrayList<>();

        @Option(names = "--runtime-report")
        @Nullable
        String runtimeReport;

        @Option(names = "--remote")
        @Nullable
        String remote;

        @Option(names = "--remote-user")
        @Nullable
        String remoteUser;

        @Option(names = "--remote-password")
        @Nullable
        String remotePassword;

        @Option(names = "--remote-token")
        @Nullable
        String remoteToken;

        @Option(names =
        {
                "-t", "--threads"
        })
        @Nullable
        String threadsRaw;

        /**
         * Per-rule findings cap (per dataset). Accepts a plain integer; for Python-CLI
         * compatibility a tuple form like {@code "(1000, True)"} is tolerated — the leading integer
         * is used. {@code 0} means unlimited; a <b>negative</b> cap is a usage error and never
         * reaches the engine (2026-09-08 ruling — {@code EngineLimits.resolve} would treat it as
         * unlimited too, but a negative is far more likely a typo than an intent). Null follows the
         * engine default ({@code -Dcorej.maxErrorsPerRule}, then {@code MAX_ERRORS_PER_RULE}, then
         * 1000).
         */
        @Option(names =
        {
                "-me", "--max-errors-per-rule"
        }, description = "Max findings to materialise per rule (per dataset); extra violations are "
                + "counted but not listed. Default follows -Dcorej.maxErrorsPerRule, then "
                + "MAX_ERRORS_PER_RULE, then 1000; 0 = unlimited, a negative value is "
                + "rejected.")
        @Nullable
        String maxErrorsRaw;

        /** Parsed {@link #maxErrorsRaw}; {@code null} = use the engine default. */
        @Nullable
        Integer maxErrorsPerRule;

        /**
         * The run's <b>severity threshold</b> (Plan C §3.4, ruling 4): the weakest check level to
         * evaluate. A rule's declared levels below it are not evaluated, and a rule whose every
         * declared level is below it is SKIPPED with that reason — never reported as a silent pass.
         *
         * <p>
         * Accepts {@code Reject} / {@code Error} / {@code Warning} / {@code Info},
         * case-insensitively. Null follows the engine default, {@code Warning} — so a default run
         * evaluates {@code Reject}+{@code Error}+{@code Warning} and excludes {@code Info}.
         * </p>
         *
         * <p>
         * ⚑ It is a <b>run</b> option and nothing else: the same value the REST
         * {@code CheckRunRequest.severityThreshold} field and the {@code .cdt} {@code #runLevel}
         * directive set. No rule package and no rule may carry one.
         * </p>
         */
        @Option(names =
        {
                "-sl", "--severity-level"
        }, description = "Weakest check level to evaluate: Reject, Error, Warning (default) or "
                + "Info. Levels below it are not evaluated; a rule with no level at or above it is "
                + "skipped with a stated reason.")
        @Nullable
        String severityLevelRaw;

        /** Parsed {@link #severityLevelRaw}; {@code null} = use the engine default (Warning). */
        net.cumba.datatable.report.@Nullable Severity severityThreshold;

        @Option(names =
        {
                "-h", "--help"
        }, usageHelp = true)
        boolean help;

        /**
         * Accepted-but-ignored Python-compat options, plus the removed {@code -dp}, bound here so
         * picocli does not reject them. {@code -dp} (an error) and the ignore note are handled in
         * {@link #parse} from the raw arguments, so this field is intentionally write-only.
         */
        @Option(names =
        {
                "-ft", "--filetype", "-ps", "--pool-size", "-l", "--log-level", "-rt",
                "--report-template", "-rr", "--raw-report", "--snomed-version", "--snomed-edition",
                "--snomed-url", "-lr", "--local-rules", "-cs", "--custom-standard", "-p",
                "--progress", "-jcf", "--jsonata-custom-functions", "-e", "--encoding", "-dp",
                "--dataset-path"
        }, hidden = true, arity = "0..1")
        List<String> compatSink = new ArrayList<>();

        // ------------------------------------------------------------------
        // External dictionaries (PLAN-dictionary-seeder Phase 6b)
        // ------------------------------------------------------------------

        /**
         * The installed dictionary store root: read by a validate run (carried per-run as
         * {@code StudyValidationParams#dictionariesDir()}, where
         * {@code DictionaryDirectoryResolver} gives it top precedence — above
         * {@code COREJ_DICTIONARIES_DIR} — and hard-errors if it does not exist), and the
         * created-if-missing install target of {@code --install-dictionaries}. Never smuggled
         * through the {@code corej.dictionariesDir} system property: the resolver ranks that BELOW
         * the environment variable, which every Docker image sets.
         */
        @Option(names = "--dictionaries-dir",
                description = "Installed-dictionary store root (validate mode: read; "
                        + "--install-dictionaries: created and written). Default: "
                        + "COREJ_DICTIONARIES_DIR, -Dcorej.dictionariesDir, then ./dictionaries.")
        @Nullable
        String dictionariesDir;

        @Option(names = "--install-dictionaries",
                description = "Install dictionaries into the store, then exit (standalone "
                        + "maintenance mode; no validation input is read). With no input options, "
                        + "downloads the credential-free MED-RT, UNII and neoplasm; local vendor "
                        + "distributions come from --meddra/--whodrug/--loinc/--medrt/--unii/"
                        + "--snomed/--neoplasm.")
        boolean installDictionaries;

        @Option(names = "--set-default",
                description = "With --install-dictionaries: rebind the selection-manifest entry to "
                        + "the version being installed even when one already exists (default: an "
                        + "existing entry is left alone).")
        boolean setDefaultDictionaries;

        @Option(names = "--skip-installed",
                description = "With --install-dictionaries: leave a type whose resolved version "
                        + "is already in the store untouched instead of re-converting it — makes "
                        + "repeated installs (a container entrypoint's boot-time auto-convert) "
                        + "idempotent.")
        boolean dictSkipInstalled;

        @Option(names = "--dry-run",
                description = "With --install-dictionaries: acquire, convert and validate exactly "
                        + "as a real install, but write nothing.")
        boolean dictDryRun;

        /*
         * Raw vendor distribution directories — installer inputs ONLY (D1): meaningful with
         * --install-dictionaries and nowhere else. Promoted out of the compat sink, but in a
         * validate run they keep behaving exactly as before — accepted and reported in the
         * "ignoring unsupported options" note — so Python-CLI invocations are unaffected.
         * arity = "0..1" keeps the bare form legal (as it was in the sink); in install mode a
         * bare option means "requested, no path" and is reported as skipped.
         */

        @Option(names = "--meddra", arity = "0..1", fallbackValue = "",
                description = "MedDRA MedAscii distribution directory (install mode only).")
        @Nullable
        String meddraDir;

        @Option(names = "--whodrug", arity = "0..1", fallbackValue = "",
                description = "WHODrug B3-format distribution directory (install mode only).")
        @Nullable
        String whodrugDir;

        @Option(names = "--loinc", arity = "0..1", fallbackValue = "",
                description = "LOINC release directory (install mode only).")
        @Nullable
        String loincDir;

        @Option(names = "--medrt", arity = "0..1", fallbackValue = "",
                description = "MED-RT release directory (install mode only).")
        @Nullable
        String medrtDir;

        @Option(names = "--unii", arity = "0..1", fallbackValue = "",
                description = "FDA UNII records directory (install mode only).")
        @Nullable
        String uniiDir;

        @Option(names = "--snomed", arity = "0..1", fallbackValue = "",
                description = "Unpacked SNOMED CT release directory (install mode only; this "
                        + "design installs SNOMED from a local package — --snomed-url and "
                        + "--snomed-edition stay accepted-but-ignored).")
        @Nullable
        String snomedDir;

        @Option(names = "--neoplasm", arity = "0..1", fallbackValue = "",
                description = "SEND CT distribution directory for the neoplasm codelist (install "
                        + "mode only); bare, downloads it from NCI EVS.")
        @Nullable
        String neoplasmDir;

        /*
         * Per-type version selection for a validate run (D6): the highest-precedence source,
         * above a define.xml ExternalCodeList/@Version, which is above the store's
         * selected-versions.json manifest. Strict (Q12): a requested version that is not
         * installed SKIPs that type's rules — never a silent substitution. SNOMED's option is
         * --snomed-version-select because --snomed-version is a Python-compat option (an API
         * version string) that stays accepted-but-ignored.
         */

        @Option(names = "--meddra-version",
                description = "Installed MedDRA version to bind for this run.")
        @Nullable
        String meddraVersion;

        @Option(names = "--whodrug-version",
                description = "Installed WHODrug version to bind for this run.")
        @Nullable
        String whodrugVersion;

        @Option(names = "--loinc-version",
                description = "Installed LOINC version to bind for this run.")
        @Nullable
        String loincVersion;

        @Option(names = "--medrt-version",
                description = "Installed MED-RT version to bind for this run.")
        @Nullable
        String medrtVersion;

        @Option(names = "--unii-version",
                description = "Installed UNII version to bind for this run.")
        @Nullable
        String uniiVersion;

        @Option(names = "--snomed-version-select",
                description = "Installed SNOMED version to bind for this run (--snomed-version is "
                        + "a Python-compat option and is ignored).")
        @Nullable
        String snomedVersionSelect;

        @Option(names = "--neoplasm-version",
                description = "Installed neoplasm-codelist version to bind for this run.")
        @Nullable
        String neoplasmVersion;

        /**
         * The requested dictionary versions, keyed by lower-cased house type — what
         * {@code StudyValidationParams#dictionaryVersions()} carries into the store's selection
         * (blank values contribute nothing).
         */
        Map<String, String> dictionaryVersions()
        {
            Map<String, String> out = new LinkedHashMap<>();
            putVersion(out, "meddra", meddraVersion);
            putVersion(out, "whodrug", whodrugVersion);
            putVersion(out, "loinc", loincVersion);
            putVersion(out, "medrt", medrtVersion);
            putVersion(out, "unii", uniiVersion);
            putVersion(out, "snomed", snomedVersionSelect);
            putVersion(out, "neoplasm", neoplasmVersion);
            return out;
        }


        private static void putVersion(Map<String, String> aMap, String aType,
                @Nullable String aVersion)
        {
            if (aVersion != null && !aVersion.isBlank())
            {
                aMap.put(aType, aVersion);
            }
        }


        /**
         * The raw-distribution inputs for install mode, keyed by house type, in a fixed install
         * order. An empty-string value means the type was requested with no path (bare option) —
         * install mode reports it as skipped.
         */
        Map<String, String> installerInputs()
        {
            Map<String, String> out = new LinkedHashMap<>();
            putInput(out, "meddra", meddraDir);
            putInput(out, "whodrug", whodrugDir);
            putInput(out, "loinc", loincDir);
            putInput(out, "medrt", medrtDir);
            putInput(out, "unii", uniiDir);
            putInput(out, "snomed", snomedDir);
            putInput(out, "neoplasm", neoplasmDir);
            return out;
        }


        private static void putInput(Map<String, String> aMap, String aType, @Nullable String aDir)
        {
            if (aDir != null)
            {
                aMap.put(aType, aDir);
            }
        }

        /**
         * {@code -vx / --validate-xml} — promoted from the accepted-but-ignored compat sink into a
         * real local-mode flag (plan §1 / §3.6, Phase 7). When enabled it runs the standalone
         * Define-XML conformance validator on the {@code -dxp} define.xml and writes its JSON
         * report to {@code <output-base>.define.json}, alongside the normal data-validation report.
         *
         * <p>
         * Python-CLI invocation-compatible: the Python tool takes a {@code y|n} value, so this
         * option accepts {@code -vx} bare (via {@code fallbackValue}) or
         * {@code -vx y|yes|n|no|...}; presence enables it unless the value is an explicit falsey
         * token. Parsed into {@link #validateXml} in {@link #parse}.
         * </p>
         */
        @Option(names =
        {
                "-vx", "--validate-xml"
        }, arity = "0..1", fallbackValue = "y")
        @Nullable
        String validateXmlRaw;

        /** Parsed {@link #validateXmlRaw}: {@code true} when {@code -vx} is present and truthy. */
        boolean validateXml;

        /** Rule worker threads; parsed from {@link #threadsRaw} in {@link #parse}. */
        int ruleThreads = 1;

        /** Accepted-but-ignored option names (for the one-time note). Excludes -dp (an error). */
        private static final Set<String> IGNORED_NAMES = Set.of("-ft", "--filetype", "-ps",
                "--pool-size", "-l", "--log-level", "-rt", "--report-template", "-rr",
                "--raw-report", "--snomed-version", "--snomed-edition", "--snomed-url", "-lr",
                "--local-rules", "-cs", "--custom-standard", "-p", "--progress", "-jcf",
                "--jsonata-custom-functions", "-e", "--encoding");

        /**
         * Installer-input options (D1): real options now, but meaningful only with
         * {@code --install-dictionaries} — outside install mode they join the ignore note, keeping
         * the pre-promotion behaviour (and Python-CLI compatibility) bit-for-bit.
         */
        private static final Set<String> INSTALLER_INPUT_NAMES = Set.of("--meddra", "--whodrug",
                "--loinc", "--medrt", "--unii", "--snomed", "--neoplasm");

        /**
         * Install-mode modifiers that are silent no-ops in a validate run — joined to the ignore
         * note there so the operator is told (the third such flag, {@code --dry-run}, is instead a
         * usage error outside install mode: its universal meaning is "write nothing").
         */
        private static final Set<String> INSTALL_MODE_MODIFIER_NAMES = Set.of("--set-default",
                "--skip-installed");

        /**
         * The per-type version-<em>selection</em> options (D6): meaningful in a validate run,
         * silent no-ops in the two standalone maintenance modes — joined to the ignore note there,
         * so {@code --install-dictionaries --meddra-version 27.0} does not read as having bound
         * anything.
         */
        private static final Set<String> VERSION_SELECT_NAMES = Set.of("--meddra-version",
                "--whodrug-version", "--loinc-version", "--medrt-version", "--unii-version",
                "--snomed-version-select", "--neoplasm-version");

        static Args parse(String[] argv)
        {
            return parse(argv, System.err);
        }


        static Args parse(String[] argv, PrintStream err)
        {
            Args a = new Args();
            // Removed options error BEFORE picocli parses (and regardless of -h, matching the
            // old parser). Scanning here rather than after the parse is what lets a removed
            // option name its replacement at all: once -s / -v / -f no longer exist, picocli
            // would simply report "Unknown option", which tells the user nothing about -rp.
            // ⚑ The historical reason was narrower — POSIX clustering resolved "-ss" as "-s s"
            // and "-sdtmv" as "-s dtmv", producing a misleading "duplicated --standard". That
            // specific hazard died with -s itself; the scan survives for the message quality.
            boolean mpTyped = false;
            for (String token : argv)
            {
                String name = optionName(token);
                if ("-dp".equals(name) || "--dataset-path".equals(name))
                {
                    throw new UsageException("-dp / --dataset-path was removed in favour of "
                            + "-d / --data which now accepts any library shape (directory, single "
                            + "file, or URI). Pass your dataset file via -d.");
                }
                if ("-s".equals(name) || "--standard".equals(name))
                {
                    throw new UsageException("-s / --standard was removed. Rules are selected by "
                            + "package: -rp / --rules-package (e.g. -rp cdisc-sdtmig-3-4). The "
                            + "package declares the CDISC Library standard it runs against.");
                }
                if ("-v".equals(name) || "--version".equals(name))
                {
                    throw new UsageException("-v / --version was removed. Rules are selected by "
                            + "package: -rp / --rules-package (e.g. -rp cdisc-sdtmig-3-4), and the "
                            + "version is part of the package name.");
                }
                if ("-f".equals(name) || "--family".equals(name))
                {
                    throw new UsageException("-f / --family was removed. The family is part of the "
                            + "package name: -rp cdisc-sdtmig-3-4, -rp fda-sdtmig-3-4. Name each "
                            + "package you want (comma-separated or repeated).");
                }
                if ("-ss".equals(name) || "--substandard".equals(name))
                {
                    throw new UsageException("-ss / --substandard was removed. Declare the "
                            + "metadata products directly via -mp / --metadata-products "
                            + "(e.g. -mp tig/1-0/adam).");
                }
                if ("-sdtmv".equals(name) || "--sdtm-version".equals(name))
                {
                    throw new UsageException("-sdtmv / --sdtm-version was removed. Declare the "
                            + "companion SDTM product via -mp / --metadata-products "
                            + "(e.g. -mp sdtmig/3-3).");
                }
                if ("-mp".equals(name) || "--metadata-products".equals(name))
                {
                    mpTyped = true;
                }
            }
            ParseResult pr;
            try
            {
                pr = new CommandLine(a).parseArgs(argv);
            }
            catch (CommandLine.UnmatchedArgumentException e)
            {
                throw new UsageException("Unknown option: " + String.join(", ", e.getUnmatched()));
            }
            catch (CommandLine.MissingParameterException e)
            {
                throw new UsageException("Missing value for an option: " + e.getMessage());
            }
            catch (CommandLine.ParameterException e)
            {
                String message = e.getMessage();
                throw new UsageException(message != null ? message : "invalid argument");
            }

            if (a.help)
            {
                return a;
            }

            // --dry-run means "write nothing" everywhere it exists; here it exists only for
            // --install-dictionaries. Accepting it on a validate run — which then writes a full
            // report — would invert the flag's universal meaning, so the combination is rejected
            // rather than noted-and-ignored.
            if (a.dictDryRun && !a.installDictionaries)
            {
                throw new UsageException("--dry-run is only meaningful with "
                        + "--install-dictionaries; a validate run always writes its report. "
                        + "Add --install-dictionaries, or drop --dry-run.");
            }

            // A typed -mp that names nothing must not be silently equivalent to omitting it -
            // the run would otherwise fall back to the -s/-v-implied product with no message.
            // Blank tokens between commas ("a,,b") are dropped; a declaration left with no
            // token at all is a usage error (fail early, matching an unresolvable token).
            a.metadataProducts.removeIf(String::isBlank);
            if (mpTyped && a.metadataProducts.isEmpty())
            {
                throw new UsageException("-mp / --metadata-products was given but names no "
                        + "product. Give at least one product key (e.g. adam/adamig-1-3), or "
                        + "omit the flag to run with the standards the selected rule packages "
                        + "declare.");
            }

            if (a.threadsRaw != null)
            {
                try
                {
                    a.ruleThreads = Integer.parseInt(a.threadsRaw.trim());
                }
                catch (NumberFormatException nfe)
                {
                    throw new UsageException(
                            "--threads expects a positive integer, got: " + a.threadsRaw);
                }
                if (a.ruleThreads < 1)
                {
                    throw new UsageException("--threads must be >= 1 (got " + a.ruleThreads + ")");
                }
            }

            if (a.severityLevelRaw != null)
            {
                a.severityThreshold = net.cumba.datatable.report.Severity
                        .parseOrNull(a.severityLevelRaw);
                if (a.severityThreshold == null
                        || a.severityThreshold == net.cumba.datatable.report.Severity.NOTICE)
                {
                    throw new UsageException("--severity-level expects one of Reject, Error, "
                            + "Warning, Info — got: " + a.severityLevelRaw);
                }
            }

            if (a.maxErrorsRaw != null)
            {
                // The WHOLE token must be an integer, or the Python-CLI tuple form "(N, bool)".
                // This used to be a find() over "-?\\d+", which scans ANYWHERE in the string: a
                // typo'd "--max-errors-per-rule v2.1" silently capped at 2, and "(-3, True)" was
                // accepted as -3. The cap changes how many findings per rule are materialised, so
                // a mistyped value is a RESULT change, not a cosmetic one — it fails loudly.
                // C2-06: the parens must be BALANCED and the tuple's second element must be a
                // Python bool. "^\\(?…\\)?$" made each paren independently optional, so "(5",
                // "5)" and "(5, True" were read as 5, and "[A-Za-z]+" accepted "(5, banana)".
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^(?:(-?\\d+)"
                                + "|\\(\\s*(-?\\d+)\\s*(?:,\\s*(?:[Tt]rue|[Ff]alse)\\s*)?\\))$")
                        .matcher(a.maxErrorsRaw.trim());
                if (!m.matches())
                {
                    throw new UsageException("--max-errors-per-rule expects an integer or the "
                            + "Python-CLI \"(N, bool)\" tuple form, got: " + a.maxErrorsRaw);
                }
                int cap;
                try
                {
                    cap = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
                }
                catch (NumberFormatException _)
                {
                    throw new UsageException("--max-errors-per-rule is out of range for a 32-bit "
                            + "integer, got: " + a.maxErrorsRaw);
                }
                if (cap < 0)
                {
                    throw new UsageException("--max-errors-per-rule must not be negative (a "
                            + "negative cap has no meaning), got: " + a.maxErrorsRaw);
                }
                a.maxErrorsPerRule = cap;
            }

            // Cache seeding and dictionary installation are standalone maintenance modes: they
            // read no study and target no standard, so the validation-input requirements below
            // do not apply to them.
            boolean seeding = a.seedCache != null || a.seedCacheFromDir != null;
            boolean installing = a.installDictionaries;
            if (!seeding && !installing && a.data == null && a.defineXmlPath == null)
            {
                throw new UsageException("One of --data or --define-xml-path is required");
            }
            // V4 (review R-7): a run selected ONLY by --rules-file has no CDISC Library
            // standard to resolve metadata against — a custom rules file declares no
            // standards and matches no manifest entry — so without -mp it is certain to
            // fail deep inside the run. Fail here, at parse time, naming the flag that
            // fixes it.
            if (!seeding && !installing && !a.rulesFiles.isEmpty() && a.rulesPackages.isEmpty()
                    && a.metadataProducts.isEmpty())
            {
                throw new UsageException("--rules-file without a rules package requires "
                        + "-mp / --metadata-products: a custom rules file declares no CDISC "
                        + "Library standard, so the run would have no metadata to validate "
                        + "against. Name the product(s) to use (e.g. -mp sdtmig/3-4), or "
                        + "select a bundled package with -rp / --rules-package.");
            }
            if (a.validateXmlRaw != null)
            {
                a.validateXml = isValidateXmlEnabled(a.validateXmlRaw);
            }
            if (a.validateXml && a.defineXmlPath == null)
            {
                throw new UsageException("-vx / --validate-xml requires -dxp / --define-xml-path "
                        + "(the define.xml to validate for conformance)");
            }
            // Local mode only: --remote ignores -vx entirely and says so, so demanding a family
            // for a run that will never use one would replace that note with a usage error.
            if (a.validateXml && a.remote == null && a.defineFamilies.isEmpty())
            {
                throw new UsageException("-vx / --validate-xml requires --define-family: name the"
                        + " Define-XML rule sheet(s) to validate against (CDISC and/or PMDA)."
                        + " There is deliberately no default — running every sheet is a choice,"
                        + " and it used to be made silently.");
            }
            // The allowlist IS the registry: a format is supported exactly when a writer module on
            // the classpath registered it (Fix #224). Nothing here is hard-coded, so adding a
            // writer module adds a legal -of value with no CLI edit.
            List<String> unsupportedFormats = a.outputFormats.stream()
                    .filter(f -> REPORT_MANAGER.findReportFormat(canonicalFormat(f)) == null)
                    .toList();
            if (!unsupportedFormats.isEmpty())
            {
                throw new UsageException(
                        "Only --output-format " + String.join(", ", supportedFormatNames())
                                + " are supported by this tool (got "
                                + String.join(",", a.outputFormats) + ")");
            }

            boolean maintenance = a.installDictionaries || seeding;
            List<String> ignoredUsed = pr.originalArgs().stream().map(Args::optionName)
                    .filter(n -> IGNORED_NAMES.contains(n)
                            || (!a.installDictionaries && INSTALLER_INPUT_NAMES.contains(n))
                            || (!a.installDictionaries && INSTALL_MODE_MODIFIER_NAMES.contains(n))
                            || (maintenance && VERSION_SELECT_NAMES.contains(n)))
                    .distinct().toList();
            if (!ignoredUsed.isEmpty())
            {
                err.println(
                        "Note: ignoring unsupported options: " + String.join(", ", ignoredUsed));
            }
            return a;
        }


        private static String optionName(String token)
        {
            if (token == null || !token.startsWith("-"))
            {
                return token;
            }
            int eq = token.indexOf('=');
            return eq > 0 ? token.substring(0, eq) : token;
        }


        /**
         * Interprets the {@code -vx / --validate-xml} value (Python-CLI {@code y|n} compatibility).
         * A bare {@code -vx} yields the {@code fallbackValue} {@code "y"}; presence enables the
         * flag unless the value is an explicit falsey token ({@code n|no|false|0|f}).
         */
        private static boolean isValidateXmlEnabled(String raw)
        {
            String v = raw.trim().toLowerCase(Locale.ROOT);
            return !("n".equals(v) || "no".equals(v) || "false".equals(v) || "0".equals(v)
                    || "f".equals(v));
        }
    }


    private static final class UsageException extends RuntimeException
    {

        private static final long serialVersionUID = 1L;

        UsageException(String message)
        {
            super(message);
        }
    }
}
