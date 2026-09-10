package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Targeted tests for {@code CdiscValidate.Args.parse}: the {@code --threads} boundary, the required
 * standard/version/data validation, the output-format guard, and the "ignoring unsupported options"
 * note. The thread-count parsing runs only when {@code -h} is absent, so these tests deliberately
 * avoid {@code -h} and reach {@code parse} through the package-private two-arg overload via
 * reflection (it returns the populated {@code Args}, letting us assert the parsed thread count
 * directly — which is what kills the boundary mutant on {@code ruleThreads < 1}).
 */
class CdiscValidateArgsParseTest
{

    @Test
    void parse_threadsExactlyOne_isAccepted() throws Exception
    {
        // Boundary case: 1 is the minimum legal value. A mutant turning "< 1" into "<= 1" would
        // reject this and the assertion on ruleThreads would never be reached (exception instead).
        Object parsed = parse("-d", "/tmp", "-t", "1");
        assertEquals(1, ruleThreads(parsed), "threads=1 must parse to ruleThreads=1");
    }


    @Test
    void parse_threadsLargerValue_isParsedExactly() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-t", "8");
        assertEquals(8, ruleThreads(parsed), "explicit thread count must be parsed exactly");
    }


    @Test
    void parse_noThreadsFlag_defaultsToOne() throws Exception
    {
        Object parsed = parse("-d", "/tmp");
        assertEquals(1, ruleThreads(parsed), "default thread count is 1");
    }


    @Test
    void parse_threadsZero_throwsUsage() throws Exception
    {
        InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> parse("-d", "/tmp", "-t", "0"));
        assertTrue(ite.getCause().getMessage().contains("--threads must be >= 1"),
                ite.getCause().getMessage());
    }


    @Test
    void parse_threadsNegative_throwsUsage()
    {
        InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> parse("-d", "/tmp", "-t", "-3"));
        assertTrue(ite.getCause().getMessage().contains("--threads must be >= 1"),
                ite.getCause().getMessage());
    }


    @Test
    void parse_ignoredOptions_emitsNoteToProvidedStream() throws Exception
    {
        // The note is only written when accepted-but-ignored options are present AND parsing
        // continues past the help short-circuit. We pass -p (progress, ignored) with a real run.
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

        parseWithErr(err, "-d", "/tmp", "-p");

        String note = errBuf.toString(StandardCharsets.UTF_8);
        assertTrue(note.contains("Note: ignoring unsupported options"), note);
        assertTrue(note.contains("-p"), "the ignored option name must be listed: " + note);
    }


    @Test
    void parse_noIgnoredOptions_writesNothing() throws Exception
    {
        // With no ignored options the note branch must NOT fire (negate-conditional mutant would
        // wrongly emit the note here).
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

        parseWithErr(err, "-d", "/tmp");

        assertEquals("", errBuf.toString(StandardCharsets.UTF_8),
                "no note expected when no unsupported options are used");
    }


    /**
     * ⛔ Plan 2 (R5) removed {@code -s} / {@code -v} / {@code -f}: rules are selected by package
     * ({@code -rp}), and the package declares the standard. Each must be refused by name rather
     * than reported as an unknown option, so a user with an old command line is told what to do.
     */
    @Test
    void parse_removedSelectionFlags_throwUsageNamingTheReplacement()
    {
        for (String flag : List.of("-s", "--standard", "-v", "--version", "-f", "--family"))
        {
            InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                    () -> parse(flag, "x", "-d", "/tmp"), flag);
            String message = ite.getCause().getMessage();
            assertTrue(message.contains(flag) && message.contains("was removed"), message);
            assertTrue(message.contains("-rp") || message.contains("package"), message);
        }
    }


    @Test
    void parse_missingDataAndDefine_throwsUsage()
    {
        InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> parse());
        assertTrue(ite.getCause().getMessage().contains("One of --data or --define-xml-path"),
                ite.getCause().getMessage());
    }


    @Test
    void parse_outputFormatXml_throwsUsage()
    {
        InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> parse("-d", "/tmp", "-of", "xml"));
        assertTrue(ite.getCause().getMessage().contains("Only --output-format json"),
                ite.getCause().getMessage());
    }


    @Test
    void parse_outputFormatJson_isAccepted() throws Exception
    {
        // The json case must NOT throw: the noneMatch guard's negation would wrongly reject it.
        Object parsed = parse("-d", "/tmp", "-of", "JSON");
        assertEquals(1, ruleThreads(parsed));
    }

    // ------------------------------------------------------------------
    // -mp / --metadata-products (PLAN-metadata-product-selection Phase 1)
    // ------------------------------------------------------------------


    @Test
    void parse_metadataProducts_splitsOnCommaAndPreservesOrder() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-mp", "adam/adam-occds-1-1,adam/adamig-1-3");
        assertEquals(java.util.List.of("adam/adam-occds-1-1", "adam/adamig-1-3"),
                metadataProducts(parsed), "-mp must split on comma and preserve the user order");
    }


    @Test
    void parse_metadataProducts_repeatedFlagAppendsInOrder() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-mp", "adam/adam-occds-1-1", "-mp", "adam/adamig-1-3");
        assertEquals(java.util.List.of("adam/adam-occds-1-1", "adam/adamig-1-3"),
                metadataProducts(parsed));
    }


    @Test
    void parse_metadataProducts_omitted_isEmptyNeverMandatory() throws Exception
    {
        // R4 — the flag is optional for an ordinary run: it parses with no -mp at all, and the
        // selected rule packages' declared standards supply the products (R7). ⚠ Since V4 the
        // one exception is a --rules-file-only selection, which requires -mp at parse time.
        Object parsed = parse("-d", "/tmp");
        assertEquals(java.util.List.of(), metadataProducts(parsed));
    }


    @Test
    void parse_removedSubstandardFlag_isRejectedNamingTheReplacement()
    {
        // -ss/--substandard is removed OUTRIGHT - not compat-sunk. The pre-parse removed-flag
        // scan (review finding F5) names the replacement for BOTH forms; without it picocli's
        // POSIX clustering would read "-ss" as "-s s" and blame a duplicated --standard - a
        // flag the user typed correctly - while never mentioning -mp.
        for (String flag : new String[]
        {
                "--substandard", "-ss"
        })
        {
            InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                    () -> parse("-d", "/tmp", flag, "adam"));
            String message = ite.getCause().getMessage();
            assertTrue(message.contains("-ss / --substandard was removed"), message);
            assertTrue(message.contains("--metadata-products"), message);
            assertFalse(message.contains("should be specified only once"), message);
        }
    }


    @Test
    void parse_removedSdtmVersionFlag_isRejectedNamingTheReplacement()
    {
        // Same shape (F5): both forms hit the pre-parse scan, never the misleading
        // "-s dtmv" duplicated---standard cluster error.
        for (String flag : new String[]
        {
                "--sdtm-version", "-sdtmv"
        })
        {
            InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                    () -> parse("-d", "/tmp", flag, "3-3"));
            String message = ite.getCause().getMessage();
            assertTrue(message.contains("-sdtmv / --sdtm-version was removed"), message);
            assertTrue(message.contains("--metadata-products"), message);
            assertFalse(message.contains("should be specified only once"), message);
        }
    }


    @Test
    void parse_metadataProducts_typedButEmpty_isAUsageErrorNotASilentNoOp()
    {
        // Review finding F10: -mp "" used to parse to an empty list, silently equivalent to
        // omitting the flag - the run fell back to the -s/-v-implied product with no message.
        // A declaration that was typed but names nothing must fail early (exit 2), like an
        // unresolvable token does.
        for (String value : new String[]
        {
                "", " ", ",,", " , "
        })
        {
            InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                    () -> parse("-d", "/tmp", "-mp", value),
                    "-mp <" + value + "> must be a usage error");
            String message = ite.getCause().getMessage();
            assertTrue(message.contains("names no product"), message);
            assertTrue(message.contains("--metadata-products"), message);
        }
    }


    @Test
    void parse_metadataProducts_blankTokenBetweenCommas_isDroppedNotFatal() throws Exception
    {
        // A stray comma next to real tokens is benign - the declaration still names products,
        // so only the blanks are dropped (F10 errors only when NOTHING is left).
        Object parsed = parse("-d", "/tmp", "-mp", "adam/adamig-1-3,,adam/adam-occds-1-1");
        assertEquals(java.util.List.of("adam/adamig-1-3", "adam/adam-occds-1-1"),
                metadataProducts(parsed));
    }

    // ------------------------------------------------------------------
    // -rp / --rules-package (PLAN-rules-package-selection Phase 1, R1)
    // ------------------------------------------------------------------


    @Test
    void parse_rulesPackage_splitsOnCommaAndPreservesOrder() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-rp", "cdisc-adamig-1-3,pmda-adamig-1-3");
        assertEquals(java.util.List.of("cdisc-adamig-1-3", "pmda-adamig-1-3"),
                rulesPackages(parsed), "-rp must split on comma and preserve the user order");
    }


    @Test
    void parse_rulesPackage_repeatedFlagAppendsInOrder() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-rp", "cdisc-adamig-1-3", "-rp", "pmda-adamig-1-3");
        assertEquals(java.util.List.of("cdisc-adamig-1-3", "pmda-adamig-1-3"),
                rulesPackages(parsed), "R1: -rp must accept repetition as well as commas");
    }


    @Test
    void parse_rulesPackage_longFormAndInlineValue() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "--rules-package=cdisc-adamig-1-3");
        assertEquals(java.util.List.of("cdisc-adamig-1-3"), rulesPackages(parsed));
    }


    /**
     * ⚠ {@code -rp} must not be swallowed by POSIX clustering as {@code -r p} — {@code -r} is the
     * rule-id include filter. A declared option name wins over clustering; this pins it (the
     * {@code -ss} → {@code -s s} misparse is the precedent that made this worth asserting).
     */
    @Test
    void parse_rulesPackage_isNotClusteredAsDashR() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-rp", "cdisc-adamig-1-3");
        assertEquals(java.util.List.of("cdisc-adamig-1-3"), rulesPackages(parsed));
        assertTrue(includeRules(parsed).isEmpty(),
                "-rp must not land in includeRules via -r clustering");
    }

    // ------------------------------------------------------------------
    // reflection plumbing
    // ------------------------------------------------------------------


    @SuppressWarnings("unchecked")
    private static java.util.List<String> rulesPackages(Object parsedArgs) throws Exception
    {
        return (java.util.List<String>) parsedArgs.getClass().getDeclaredField("rulesPackages")
                .get(parsedArgs);
    }


    @SuppressWarnings("unchecked")
    private static java.util.List<String> includeRules(Object parsedArgs) throws Exception
    {
        return (java.util.List<String>) parsedArgs.getClass().getDeclaredField("includeRules")
                .get(parsedArgs);
    }


    @SuppressWarnings("unchecked")
    private static java.util.List<String> metadataProducts(Object parsedArgs) throws Exception
    {
        return (java.util.List<String>) parsedArgs.getClass().getDeclaredField("metadataProducts")
                .get(parsedArgs);
    }


    private static Object parse(String... argv) throws Exception
    {
        Class<?> argsCls = Class.forName(CdiscValidate.class.getName() + "$Args");
        Method m = argsCls.getDeclaredMethod("parse", String[].class);
        m.setAccessible(true);
        return m.invoke(null, (Object) argv);
    }


    private static Object parseWithErr(PrintStream err, String... argv) throws Exception
    {
        Class<?> argsCls = Class.forName(CdiscValidate.class.getName() + "$Args");
        Method m = argsCls.getDeclaredMethod("parse", String[].class, PrintStream.class);
        m.setAccessible(true);
        return m.invoke(null, argv, err);
    }


    private static int ruleThreads(Object args) throws Exception
    {
        var f = args.getClass().getDeclaredField("ruleThreads");
        f.setAccessible(true);
        return f.getInt(args);
    }

    // ------------------------------------------------------------------
    // Plan C §3.4 — --severity-level (the run's severity threshold)
    // ------------------------------------------------------------------


    @Test
    void parse_severityLevel_allFourRungsAndTheDefault() throws Exception
    {
        assertEquals(null, severityThreshold(parse("-d", "/tmp")),
                "absent --severity-level leaves the field null, i.e. 'the engine default'");
        assertEquals("WARNING",
                severityThreshold(parse("-d", "/tmp", "--severity-level", "warning")),
                "case-insensitive, like every other authored severity spelling");
        assertEquals("REJECT", severityThreshold(parse("-d", "/tmp", "-sl", "Reject")));
        assertEquals("INFO", severityThreshold(parse("-d", "/tmp", "-sl", "Info")));
    }


    @Test
    void parse_severityLevel_unknownOrNotice_throwsUsage()
    {
        InvocationTargetException unknown = assertThrows(InvocationTargetException.class,
                () -> parse("-d", "/tmp", "-sl", "Severe"));
        assertTrue(unknown.getCause().getMessage().contains("--severity-level expects one of"),
                unknown.getCause().getMessage());

        InvocationTargetException notice = assertThrows(InvocationTargetException.class,
                () -> parse("-d", "/tmp", "-sl", "Notice"));
        assertTrue(notice.getCause().getMessage().contains("--severity-level expects one of"),
                "Notice is a report-only kind, not a rung: " + notice.getCause().getMessage());
    }


    private static @org.jspecify.annotations.Nullable String severityThreshold(Object args)
        throws Exception
    {
        var f = args.getClass().getDeclaredField("severityThreshold");
        f.setAccessible(true);
        Object v = f.get(args);
        return v == null ? null : ((Enum<?>) v).name();
    }


    private static String stringField(Object args, String name) throws Exception
    {
        var f = args.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(args);
    }


    private static boolean booleanField(Object args, String name) throws Exception
    {
        var f = args.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (boolean) f.get(args);
    }


    @Test
    void parse_seedCacheFromApi_defaultsOffAndParses() throws Exception
    {
        org.junit.jupiter.api.Assertions.assertFalse(
                booleanField(parse("-d", "/tmp"), "seedCacheFromApi"),
                "absent --seed-cache-from-api leaves the flag off");
        org.junit.jupiter.api.Assertions.assertTrue(
                booleanField(parse("-d", "/tmp", "--seed-cache-from-api"), "seedCacheFromApi"));
    }


    /**
     * F-cli-03. Tightening {@code --max-errors-per-rule} from {@code find()} to {@code matches()}
     * must not lose the documented Python-CLI tuple form, which is the reason the regex was ever a
     * search rather than a full match.
     */
    @org.junit.jupiter.api.Test
    void maxErrorsPerRule_acceptsAPlainIntegerAndThePythonTupleForm() throws Exception
    {
        assertEquals(Integer.valueOf(5), maxErrorsPerRule(parse("-d", "/tmp", "-me", "5")));
        assertEquals(Integer.valueOf(5), maxErrorsPerRule(parse("-d", "/tmp", "-me", "(5, True)")));
        assertEquals(Integer.valueOf(5),
                maxErrorsPerRule(parse("-d", "/tmp", "-me", " (5,False) ")));
        assertEquals(Integer.valueOf(0), maxErrorsPerRule(parse("-d", "/tmp", "-me", "0")));
    }


    /**
     * C2-06. {@code ^\(?…\)?$} made each paren <b>independently</b> optional, so {@code "(5"},
     * {@code "5)"} and {@code "(5, True"} were all read as the cap 5, and the tuple's second
     * element was {@code [A-Za-z]+}, which accepts {@code "(5, banana)"}. Half a tuple is a typo,
     * and the cap decides how many findings per rule are materialised — a result change.
     */
    @org.junit.jupiter.api.Test
    void maxErrorsPerRule_halfATupleOrANonBooleanFlagIsAUsageError()
    {
        for (String bad : java.util.List.of("(5", "5)", "(5, True", "5, True)", "(5, banana)",
                "(5 5)"))
        {
            Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> parse("-d", "/tmp", "-me", bad), "accepted as a cap: " + bad);
            org.junit.jupiter.api.Assertions.assertTrue(
                    String.valueOf(thrown.getMessage()).contains("--max-errors-per-rule")
                            || String.valueOf(thrown.getCause()).contains("--max-errors-per-rule"),
                    () -> "not reported as a --max-errors-per-rule usage error: " + bad + " -> "
                            + thrown);
        }
    }


    private static Integer maxErrorsPerRule(Object parsedArgs) throws Exception
    {
        var f = parsedArgs.getClass().getDeclaredField("maxErrorsPerRule");
        f.setAccessible(true);
        return (Integer) f.get(parsedArgs);
    }

}
