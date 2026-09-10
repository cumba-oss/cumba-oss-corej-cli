package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Strong content assertions over {@link CdiscValidate}'s {@code printUsage} output. Each distinct
 * line printed by {@code printUsage} is asserted to be present, so that mutation testing credits a
 * test failure when any single {@code println} call is removed (the common surviving-mutant class
 * for usage text). The help banner is emitted to stdout for {@code -h} and to stderr for a usage
 * error; both routes go through {@code printUsage}.
 */
@org.junit.jupiter.api.extension.ExtendWith(WorkingDirectoryStaysCleanExtension.class)
class CdiscValidateUsageTest
{

    /**
     * Every distinct text fragment {@code printUsage} writes. If any {@code println} is removed by
     * a mutant, the corresponding fragment vanishes and the parameterised test below fails.
     */
    private static final String[] USAGE_FRAGMENTS =
    {
            "Usage: CdiscValidate [options]", "Required:", "-rp, --rules-package <name>",
            "rule package short name, e.g.", "cdisc-sdtmig-3-4 (comma-separated and/or",
            "repeatable)", "Data inputs (one of):", "-d,  --data <path|URI>",
            "data library: directory, single file",
            "(.sas7bdat, .xpt, .xlsx, .rda, .dsj, .parquet,", "), or URI (file://, ssh://, …)",
            "-dxp,--define-xml-path <file>", "define.xml. With -d: metadata enrichment.",
            "Without -d: acts as the data library too.", "Output:", "-o,  --output <file>",
            "output file (default CORE-Report-<ts>.json)", "-of, --output-format json|json-2|xlsx",
            "output format(s); repeatable/comma-separated (default json)",
            "json-2 = combined-finding report (<base>.v2.json)", "-mr, --max-report-rows <n>",
            "per-sheet row cap for xlsx", "-vx, --validate-xml [y|n]",
            "also run Define-XML conformance on -dxp,",
            "writing <output-base>.define.json (local mode)", "--define-rules-dir <dir>",
            "--define-family <sheet>", "REQUIRED for -vx: CDISC and/or PMDA",
            "--define-rules-file <file>", "Optional:", "-mp, --metadata-products <p>[,<p>..]",
            "ordered CDISC Library products consulted", "-uc, --use-case <uc>", "TIG use case",
            "--define-first", "prefer declared define.xml class/subclass",
            "-ct, --controlled-terminology-package <pkg>", "CT package id, repeatable",
            "-dv, --define-version <ver>", "Define-XML version", "-r,  --rules <CORE-id>",
            "include only matching rule ids (repeatable)", "-er, --exclude-rules <CORE-id>",
            "exclude matching rule ids (repeatable)", "-sl, --severity-level <level>",
            "weakest check level evaluated: Reject, Error,",
            "Warning (default) or Info. Levels below it are",
            "not evaluated; a rule with no level at or above",
            "it is SKIPPED, with that reason stated.", "-me, --max-errors-per-rule <n>",
            "findings materialised per rule, per dataset",
            "(default 1000, from -Dcorej.maxErrorsPerRule",
            "then MAX_ERRORS_PER_RULE; 0 = unlimited, a", "negative value is a usage error).",
            "Extra violations are counted, not listed.", "-ds, --dataset <name>[,<name>..]",
            "validate only these library members (others",
            "become lazy references; repeatable, comma-sep)", "-rd, --reference-data <path>",
            "extra reference library (dir with define.xml",
            "or define.xml directly); repeatable. Use to",
            "provide SDTM data when validating ADaM.", "-t,  --threads <n>",
            "rule worker threads per dataset (default 1;",
            "max = available CPU cores; <1 errors out)", "-ca, --cache <file>",
            "unified metadata store zip (default:", "CDISC_METADATA_STORE / cdisc.metadata.store",
            "/ ~/.cumbaDataBrowser/metadata-cache.zip)",
            "Metadata store seeding (runs standalone and exits):", "--seed-cache [<repoUri>]",
            "rebuild the unified metadata store from", "--seed-cache-from-dir <dir>",
            "--seed-cache-from-api", "(needs an API key)", "--seed-overwrite",
            "re-acquire everything, ignoring what the", "store already holds", "--seed-dry-run",
            "report without touching the store", "External dictionaries:",
            "--dictionaries-dir <dir>", "installed-dictionary store (default:",
            "--snomed-version-select <v>", "--install-dictionaries", "--set-default",
            "--skip-installed", "--dry-run", "Java-only extras:", "--rules-dir <dir>",
            "rules directory", "--rules-file <file>", "extra rule package file (repeatable)",
            "--runtime-report <file>", "CSV file for per-(dataset, rule) runtime",
            "(default: <output>.runtime.csv; columns:", "domain,fileName,rowCount,columnCount,",
            "coreId,elapsedMs,status,violationCount)", "-h,  --help", "show this help",
            "Remote mode (run the check on a remote REST service):", "--remote <baseUrl>",
            "REST service base URL; same effect as", "-Dcorej.remote.url. When set, the local data",
            "/ define.xml / rules-file / -rd reference", "data are uploaded and the run executes",
            "server-side; the report is written to -o.", "(-d must be a local path.)",
            "-rd becomes a datasetFilter complement:", "its members are uploaded but never",
            "validated. With -ds the filter is", "-ds MINUS -rd — -rd always wins.",
            "--remote-user <user>", "HTTP Basic user (-Dcorej.remote.user)",
            "--remote-password <pass>", "HTTP Basic password (-Dcorej.remote.password)",
            "--remote-token <token>", "Bearer token (-Dcorej.remote.token); wins",
            "over basic auth when both are set", "Authentication (CDISC Library, local mode):",
            "Set CDISC_API_KEY env var or -Dcdisc.library.api.key system property.",
    };

    @ParameterizedTest(name = "usage banner contains [{0}]")
    @ValueSource(strings =
    {
            "Usage: CdiscValidate [options]", "Required:", "-rp, --rules-package <name>",
            "rule package short name, e.g.", "cdisc-sdtmig-3-4 (comma-separated and/or",
            "repeatable)", "Data inputs (one of):", "-d,  --data <path|URI>",
            "data library: directory, single file",
            "(.sas7bdat, .xpt, .xlsx, .rda, .dsj, .parquet,", "), or URI (file://, ssh://, …)",
            "-dxp,--define-xml-path <file>", "define.xml. With -d: metadata enrichment.",
            "Without -d: acts as the data library too.", "Output:", "-o,  --output <file>",
            "output file (default CORE-Report-<ts>.json)", "-of, --output-format json|json-2|xlsx",
            "output format(s); repeatable/comma-separated (default json)",
            "json-2 = combined-finding report (<base>.v2.json)", "-mr, --max-report-rows <n>",
            "per-sheet row cap for xlsx", "-vx, --validate-xml [y|n]",
            "also run Define-XML conformance on -dxp,",
            "writing <output-base>.define.json (local mode)", "--define-rules-dir <dir>",
            "--define-family <sheet>", "REQUIRED for -vx: CDISC and/or PMDA",
            "--define-rules-file <file>", "Optional:", "-mp, --metadata-products <p>[,<p>..]",
            "ordered CDISC Library products consulted", "-uc, --use-case <uc>", "TIG use case",
            "--define-first", "prefer declared define.xml class/subclass",
            "-ct, --controlled-terminology-package <pkg>", "CT package id, repeatable",
            "-dv, --define-version <ver>", "Define-XML version", "-r,  --rules <CORE-id>",
            "include only matching rule ids (repeatable)", "-er, --exclude-rules <CORE-id>",
            "exclude matching rule ids (repeatable)", "-sl, --severity-level <level>",
            "weakest check level evaluated: Reject, Error,",
            "Warning (default) or Info. Levels below it are",
            "not evaluated; a rule with no level at or above",
            "it is SKIPPED, with that reason stated.", "-me, --max-errors-per-rule <n>",
            "findings materialised per rule, per dataset",
            "(default 1000, from -Dcorej.maxErrorsPerRule",
            "then MAX_ERRORS_PER_RULE; 0 = unlimited, a", "negative value is a usage error).",
            "Extra violations are counted, not listed.", "-ds, --dataset <name>[,<name>..]",
            "validate only these library members (others",
            "become lazy references; repeatable, comma-sep)", "-rd, --reference-data <path>",
            "extra reference library (dir with define.xml",
            "or define.xml directly); repeatable. Use to",
            "provide SDTM data when validating ADaM.", "-t,  --threads <n>",
            "rule worker threads per dataset (default 1;",
            "max = available CPU cores; <1 errors out)", "-ca, --cache <file>",
            "unified metadata store zip (default:", "CDISC_METADATA_STORE / cdisc.metadata.store",
            "/ ~/.cumbaDataBrowser/metadata-cache.zip)",
            "Metadata store seeding (runs standalone and exits):", "--seed-cache [<repoUri>]",
            "rebuild the unified metadata store from", "--seed-cache-from-dir <dir>",
            "--seed-cache-from-api", "(needs an API key)", "--seed-overwrite",
            "re-acquire everything, ignoring what the", "store already holds", "--seed-dry-run",
            "report without touching the store", "External dictionaries:",
            "--dictionaries-dir <dir>", "installed-dictionary store (default:",
            "--snomed-version-select <v>", "--install-dictionaries", "--set-default",
            "--skip-installed", "--dry-run", "Java-only extras:", "--rules-dir <dir>",
            "rules directory", "--rules-file <file>", "extra rule package file (repeatable)",
            "--runtime-report <file>", "CSV file for per-(dataset, rule) runtime",
            "(default: <output>.runtime.csv; columns:", "domain,fileName,rowCount,columnCount,",
            "coreId,elapsedMs,status,violationCount)", "-h,  --help", "show this help",
            "Remote mode (run the check on a remote REST service):", "--remote <baseUrl>",
            "REST service base URL; same effect as", "-Dcorej.remote.url. When set, the local data",
            "/ define.xml / rules-file / -rd reference", "data are uploaded and the run executes",
            "server-side; the report is written to -o.", "(-d must be a local path.)",
            "-rd becomes a datasetFilter complement:", "its members are uploaded but never",
            "validated. With -ds the filter is", "-ds MINUS -rd — -rd always wins.",
            "--remote-user <user>", "HTTP Basic user (-Dcorej.remote.user)",
            "--remote-password <pass>", "HTTP Basic password (-Dcorej.remote.password)",
            "--remote-token <token>", "Bearer token (-Dcorej.remote.token); wins",
            "over basic auth when both are set", "Authentication (CDISC Library, local mode):",
            "Set CDISC_API_KEY env var or -Dcdisc.library.api.key system property.",
    })
    void helpBanner_containsEveryUsageLine(String fragment) throws Exception
    {
        String usage = helpOutput();
        assertTrue(usage.contains(fragment),
                () -> "help banner must contain line fragment: <" + fragment + ">");
    }


    @Test
    void helpBanner_containsAllFragmentsAndIsOrdered() throws Exception
    {
        String usage = helpOutput();
        // Each fragment must appear, in order. Asserting order makes deletion of a single println
        // detectable even if a later identical fragment exists, and pins the help layout.
        int cursor = 0;
        for (String fragment : USAGE_FRAGMENTS)
        {
            int at = usage.indexOf(fragment, cursor);
            assertTrue(at >= 0, () -> "missing or out-of-order usage fragment: <" + fragment + ">");
            cursor = at + fragment.length();
        }
        // printUsage pins the line count so that removing ANY single println (including the
        // blank-line separators) is detected. The four --define-rules-dir/-file lines are part of
        // the -vx block.
        long lineCount = usage.lines().count();
        // 89 since the --seed-cache block (12 option lines + 1 header) plus one blank
        // separator, the two runtime-CSV schema lines of --runtime-report, and Fix #217's five
        // extra --remote lines (reference data upload + the -ds/-rd precedence) were added;
        // 95 since -ss (1 line) and -sdtmv (2 lines) were removed and the 9-line -mp block
        // replaced them (PLAN-metadata-product-selection Phase 1);
        // 96 since Plan 2 (R5): the 2 "Required:" lines -s / -v were replaced by the 3-line
        // -rp / --rules-package block that names the rules instead (+1), and the duplicate -rp
        // block under "Java-only extras" — added in Phase 1, superseded by the Required one —
        // was removed (-2);
        // 118 since PLAN-dictionary-seeder Phase 6b added the 21-line "External dictionaries:"
        // block (--dictionaries-dir, the seven version-select options, --install-dictionaries,
        // --set-default, --dry-run) plus its blank separator;
        // 122 since Phase 7a grew the --install-dictionaries entry from 4 lines to 8 (download
        // behaviour for the credential-free types, plus the --neoplasm input);
        // 125 since Phase 7b added the 3-line --skip-installed entry (idempotent container
        // auto-convert);
        // 128 since PLAN-rules-module-consolidation made --define-family mandatory for -vx: its
        // 2-line entry, plus a second line on --define-rules-file to say the file is now a
        // generated JSON package rather than a single YAML rule. Both were found missing by the
        // terminal review — a required flag absent from --help leaves the user a usage error with
        // no discoverable fix.
        // 136 since the two run options that picocli parsed but no help text mentioned were
        // added to "Optional:" — -sl / --severity-level (4 lines) and -me /
        // --max-errors-per-rule (4 lines). They were found by
        // CdiscValidateOptionCoverageTest, the reflection guard that now fails whenever a
        // non-hidden @Option is absent from this banner.
        // 137 since the C2-01 ruling ("0 is unlimited, negative fails loud") replaced the
        // banner's "<= 0 = unlimited" with a two-line wording that also states the rejection —
        // the guard at Args.parse rejects a negative cap, so the printed contract had to stop
        // promising that one works.
        // 139 since cache 8b-1 re-pointed -ca at the unified metadata store (1 line -> 3, the
        // cascade), retired the 4-line -pc block, and grew the seeding block: --seed-cache-from-api
        // (+2 lines) and a two-line --seed-overwrite wording for refresh semantics (+1).
        assertEquals(139, lineCount, "help banner line count changed: " + lineCount);
        // Exactly 10 of those are blank separator lines (the bare println() calls). Removing one
        // drops the blank-line tally and fails this assertion.
        long blankLines = usage.lines().filter(String::isEmpty).count();
        assertEquals(10, blankLines, "blank separator line count changed: " + blankLines);
    }


    @Test
    void usageError_alsoEmitsBannerToStderr() throws Exception
    {
        // A usage error (unknown option) prints "Error: ..." then the full banner to stderr.
        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "--no-such-flag"
        }, cap.out, cap.err);

        assertEquals(2, rc);
        String err = cap.err();
        assertTrue(err.startsWith("Error: "), "usage error must lead with 'Error: '");
        assertTrue(err.contains("Unknown option"), "usage error names the unknown option");
        // The same banner that -h prints is appended after the error line.
        assertTrue(err.contains("Usage: CdiscValidate [options]"),
                "usage error must include the banner");
        assertTrue(err.contains("Authentication (CDISC Library, local mode):"),
                "usage error banner must run to completion");
    }


    private static String helpOutput() throws Exception
    {
        Captured cap = new Captured();
        int rc = OfflineCli.run(new String[]
        {
                "-h"
        }, cap.out, cap.err);
        assertEquals(0, rc, "help exits 0");
        return cap.out();
    }

    private static final class Captured
    {

        private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();

        private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        final PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);

        final PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

        String out()
        {
            return outBuf.toString(StandardCharsets.UTF_8);
        }


        String err()
        {
            return errBuf.toString(StandardCharsets.UTF_8);
        }
    }
}
