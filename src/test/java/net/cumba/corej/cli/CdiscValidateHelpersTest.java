package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.report.LibraryValidator;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code private static} helper methods on {@link CdiscValidate}:
 * {@code buildRemoteRequest}, {@code putIfNotNull}, {@code isRemoteTerminal},
 * {@code formatRuntimeRow}, {@code appendCsv}, and {@code Args.optionName}.
 *
 * <p>
 * These are the methods whose mutants survived the existing suite because they were either never
 * exercised or executed without assertions. Reflection is used (mirroring the existing
 * {@code argsParse_defaultStderr_usesSystemErr} test) so the production methods can stay private.
 * The assertions pin exact return values / map contents so that negate-conditional, empty-return,
 * removed-call and boundary mutants all fail.
 * </p>
 */
@SuppressWarnings("unchecked")
class CdiscValidateHelpersTest
{

    // ------------------------------------------------------------------
    // buildRemoteRequest + putIfNotNull
    // ------------------------------------------------------------------

    @Test
    void buildRemoteRequest_minimal_onlyRequiredAndThreads() throws Exception
    {
        CdiscValidate.Args a = new CdiscValidate.Args();
        a.ruleThreads = 1;

        Map<String, Object> req = buildRemoteRequest(a, null, List.of());

        // ruleThreads is always present with its value (kills the put-mutation on it). ⚑ Plan 2
        // (R5) removed 'standard' / 'version' from the wire; 'rulesPackages' replaced them and is
        // CONDITIONAL, so its absence is asserted with the other optional keys below.
        assertEquals(1, req.get("ruleThreads"));
        // Optional keys absent when their source is null/empty — putIfNotNull must NOT add them,
        // and the empty-collection guards must NOT add the list/set keys.
        assertFalse(req.containsKey("metadataProducts"));
        assertFalse(req.containsKey("useCase"));
        assertFalse(req.containsKey("defineXmlFilename"));
        assertFalse(req.containsKey("defineVersion"));
        assertFalse(req.containsKey("rulesFilenames"));
        assertFalse(req.containsKey("rulesPackages"));
        assertFalse(req.containsKey("includeRules"));
        assertFalse(req.containsKey("excludeRules"));
        assertFalse(req.containsKey("datasetFilter"));
    }


    @Test
    void buildRemoteRequest_full_allOptionalKeysPopulated() throws Exception
    {
        CdiscValidate.Args a = new CdiscValidate.Args();
        a.rulesPackages = new ArrayList<>(List.of("cdisc-adamig-1-3"));
        a.metadataProducts = new ArrayList<>(List.of("adam/adamig-1-3", "tig/1-0/adam"));
        a.useCase = "tig";
        a.defineVersion = "2-1";
        a.ruleThreads = 4;
        a.includeRules = new ArrayList<>(List.of("CORE-1", "CORE-2"));
        a.excludeRules = new ArrayList<>(List.of("CORE-9"));
        a.datasets = new java.util.LinkedHashSet<>(List.of("DM", "VS"));

        Map<String, Object> req = buildRemoteRequest(a, "define.xml", List.of("rules.json"));

        assertEquals(List.of("cdisc-adamig-1-3"), req.get("rulesPackages"));
        // -mp travels as the RAW tokens: the server resolves them against ITS cache.
        assertEquals(List.of("adam/adamig-1-3", "tig/1-0/adam"), req.get("metadataProducts"));
        assertEquals("tig", req.get("useCase"));
        assertEquals("define.xml", req.get("defineXmlFilename"));
        assertEquals("2-1", req.get("defineVersion"));
        assertEquals(List.of("rules.json"), req.get("rulesFilenames"));
        assertEquals(4, req.get("ruleThreads"));
        assertEquals(List.of("CORE-1", "CORE-2"), req.get("includeRules"));
        assertEquals(List.of("CORE-9"), req.get("excludeRules"));
        // datasetFilter is materialised as an ArrayList copy of the set, order-preserved.
        assertEquals(List.of("DM", "VS"), req.get("datasetFilter"));
    }


    @Test
    void buildRemoteRequest_emptyCollections_keysOmitted() throws Exception
    {
        CdiscValidate.Args a = new CdiscValidate.Args();
        a.ruleThreads = 2;
        // includeRules / excludeRules / datasets default to empty — the !isEmpty() guards must
        // skip them. Negating those conditionals would wrongly add empty keys.
        a.includeRules = new ArrayList<>();
        a.excludeRules = new ArrayList<>();
        a.datasets = new java.util.LinkedHashSet<>();

        Map<String, Object> req = buildRemoteRequest(a, null, List.of());

        assertFalse(req.containsKey("includeRules"));
        assertFalse(req.containsKey("excludeRules"));
        assertFalse(req.containsKey("datasetFilter"));
        assertEquals(2, req.get("ruleThreads"));
    }


    @Test
    void buildRemoteRequest_datasetFilterComesFromTheParameterNotArgs() throws Exception
    {
        // Fix #217: in --remote mode the filter carries the -rd complement, so buildRemoteRequest
        // must send what it is handed and must NOT read args.datasets. A mutant restoring the old
        // `new ArrayList<>(args.datasets)` fails both assertions below.
        CdiscValidate.Args a = new CdiscValidate.Args();
        a.ruleThreads = 1;
        a.datasets = new java.util.LinkedHashSet<>(List.of("ADSL", "DM"));

        Map<String, Object> req = buildRemoteRequest(a, null, List.of(), List.of("ADSL"));
        assertEquals(List.of("ADSL"), req.get("datasetFilter"));

        // An empty complement omits the key entirely — it must never be sent as [], which the
        // server reads as "validate everything".
        Map<String, Object> empty = buildRemoteRequest(a, null, List.of(), List.of());
        assertFalse(empty.containsKey("datasetFilter"));
    }


    @Test
    void datasetKey_stripsTheLastExtensionAndUpperCases()
    {
        // Mirrors StudyValidationService.stripExtUpper, so dm.xpt / dm.json / DM all compare equal.
        assertEquals("DM", CdiscValidate.datasetKey("dm.xpt"));
        assertEquals("DM", CdiscValidate.datasetKey("dm.json"));
        assertEquals("DM", CdiscValidate.datasetKey("DM"));
        // Only the LAST dot is a suffix boundary.
        assertEquals("AE.PART1", CdiscValidate.datasetKey("ae.part1.csv"));
        // A leading dot leaves an empty base rather than throwing.
        assertEquals("", CdiscValidate.datasetKey(".xpt"));
    }


    @Test
    void putIfNotNull_addsValueWhenPresent_skipsWhenNull() throws Exception
    {
        Method m = CdiscValidate.class.getDeclaredMethod("putIfNotNull", Map.class, String.class,
                Object.class);
        m.setAccessible(true);

        Map<String, Object> map = new LinkedHashMap<>();
        m.invoke(null, map, "k1", "v1");
        assertEquals("v1", map.get("k1"), "non-null value must be inserted");
        assertEquals(1, map.size());

        m.invoke(null, map, "k2", null);
        assertFalse(map.containsKey("k2"), "null value must NOT be inserted");
        assertEquals(1, map.size());
    }

    // ------------------------------------------------------------------
    // isRemoteTerminal
    // ------------------------------------------------------------------


    @Test
    void isRemoteTerminal_trueOnlyForTerminalStatuses() throws Exception
    {
        assertTrue(isRemoteTerminal("SUCCEEDED"));
        assertTrue(isRemoteTerminal("FAILED"));
        assertTrue(isRemoteTerminal("CANCELLED"));
    }


    @Test
    void isRemoteTerminal_falseForNonTerminalStatuses() throws Exception
    {
        assertFalse(isRemoteTerminal("PENDING"));
        assertFalse(isRemoteTerminal("RUNNING"));
        assertFalse(isRemoteTerminal("QUEUED"));
        assertFalse(isRemoteTerminal(""));
    }

    // ------------------------------------------------------------------
    // formatRuntimeRow + appendCsv
    // ------------------------------------------------------------------


    @Test
    void formatRuntimeRow_plainValues_noQuoting() throws Exception
    {
        LibraryValidator.RuntimeEntry e = new LibraryValidator.RuntimeEntry("DM", "dm.csv", 100L, 7,
                "CORE-000001", 250L, RuleExecutionStatus.EXECUTED, 3);

        String row = formatRuntimeRow(e);

        assertEquals("DM,dm.csv,100,7,CORE-000001,250,EXECUTED,3\n", row);
    }


    @Test
    void formatRuntimeRow_nullStatusAndNullStrings_emitEmptyFields() throws Exception
    {
        // appendCsv emits nothing for null; status==null leaves that field blank too.
        LibraryValidator.RuntimeEntry e = new LibraryValidator.RuntimeEntry(null, null, 0L, 0, null,
                0L, null, 0);

        String row = formatRuntimeRow(e);

        assertEquals(",,0,0,,0,,0\n", row);
    }


    @Test
    void formatRuntimeRow_distinctNumericFields_notTransposed() throws Exception
    {
        // Distinct numbers in each numeric slot so a removed/duplicated append is detectable.
        LibraryValidator.RuntimeEntry e = new LibraryValidator.RuntimeEntry("VS", "vs.csv", 11L, 22,
                "CORE-2", 33L, RuleExecutionStatus.SKIPPED, 44);

        String row = formatRuntimeRow(e);

        assertEquals("VS,vs.csv,11,22,CORE-2,33,SKIPPED,44\n", row);
    }


    @Test
    void formatRuntimeRow_commaInField_isQuoted() throws Exception
    {
        LibraryValidator.RuntimeEntry e = new LibraryValidator.RuntimeEntry("DM", "a,b.csv", 1L, 1,
                "CORE,1", 5L, RuleExecutionStatus.ERROR, 1);

        String row = formatRuntimeRow(e);

        // Fields containing a comma must be wrapped in double quotes.
        assertEquals("DM,\"a,b.csv\",1,1,\"CORE,1\",5,ERROR,1\n", row);
    }


    @Test
    void appendCsv_null_appendsNothing() throws Exception
    {
        StringBuilder sb = new StringBuilder("X");
        appendCsv(sb, null);
        assertEquals("X", sb.toString(), "null value must append nothing");
    }


    @Test
    void appendCsv_plain_appendsVerbatim() throws Exception
    {
        StringBuilder sb = new StringBuilder();
        appendCsv(sb, "plain");
        assertEquals("plain", sb.toString(), "value without special chars is appended as-is");
    }


    @Test
    void appendCsv_comma_isQuoted() throws Exception
    {
        StringBuilder sb = new StringBuilder();
        appendCsv(sb, "a,b");
        assertEquals("\"a,b\"", sb.toString());
    }


    @Test
    void appendCsv_embeddedQuote_isDoubledAndWrapped() throws Exception
    {
        StringBuilder sb = new StringBuilder();
        appendCsv(sb, "he said \"hi\"");
        // Each embedded double-quote is doubled, whole value wrapped in quotes.
        assertEquals("\"he said \"\"hi\"\"\"", sb.toString());
    }


    @Test
    void appendCsv_newlineAndCarriageReturn_areQuoted() throws Exception
    {
        StringBuilder lf = new StringBuilder();
        appendCsv(lf, "line1\nline2");
        assertEquals("\"line1\nline2\"", lf.toString(), "embedded LF forces quoting");

        StringBuilder cr = new StringBuilder();
        appendCsv(cr, "line1\rline2");
        assertEquals("\"line1\rline2\"", cr.toString(), "embedded CR forces quoting");
    }

    // ------------------------------------------------------------------
    // Args.optionName
    // ------------------------------------------------------------------


    @Test
    void optionName_stripsInlineValue_andPassesThroughNonOptions() throws Exception
    {
        assertEquals("--standard", optionName("--standard=sdtmig"),
                "inline '=value' must be stripped");
        assertEquals("-s", optionName("-s"), "bare option returned unchanged");
        assertEquals("plain", optionName("plain"), "non-option token returned unchanged");
        assertNull(optionName(null), "null returned unchanged");
        // A '-' that is the first char with no '=' -> token returned whole.
        assertEquals("-x", optionName("-x"));
        // '=' at index 0 with a non-'-' lead: eq>0 is false, so whole token is returned.
        assertEquals("=foo", optionName("=foo"));
    }

    // ------------------------------------------------------------------
    // reflection plumbing
    // ------------------------------------------------------------------


    private static Map<String, Object> buildRemoteRequest(CdiscValidate.Args a, String defineXml,
            List<String> rulesFiles)
        throws Exception
    {
        return buildRemoteRequest(a, defineXml, rulesFiles, new ArrayList<>(a.datasets));
    }


    private static Map<String, Object> buildRemoteRequest(CdiscValidate.Args a, String defineXml,
            List<String> rulesFiles, List<String> datasetFilter)
        throws Exception
    {
        Method m = CdiscValidate.class.getDeclaredMethod("buildRemoteRequest",
                CdiscValidate.Args.class, String.class, List.class, List.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(null, a, defineXml, rulesFiles, datasetFilter);
    }


    private static boolean isRemoteTerminal(String status) throws Exception
    {
        Method m = CdiscValidate.class.getDeclaredMethod("isRemoteTerminal", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, status);
    }


    @Test
    void datasetRuntimePathFor_replacesCsvSuffixWithDatasetsSibling() throws Exception
    {
        // The default per-rule path ends in .runtime.csv; the per-dataset sibling appends
        // -datasets.
        assertEquals(Path.of("out", "study.runtime-datasets.csv"),
                datasetRuntimePathFor(Path.of("out", "study.runtime.csv")));
    }


    @Test
    void datasetRuntimePathFor_noCsvSuffix_appendsDatasetsCsv() throws Exception
    {
        assertEquals(Path.of("rt-datasets.csv"), datasetRuntimePathFor(Path.of("rt")));
    }


    private static Path datasetRuntimePathFor(Path runtimePath) throws Exception
    {
        Method m = CdiscValidate.class.getDeclaredMethod("datasetRuntimePathFor", Path.class);
        m.setAccessible(true);
        return (Path) m.invoke(null, runtimePath);
    }


    private static String formatRuntimeRow(LibraryValidator.RuntimeEntry e) throws Exception
    {
        Method m = CdiscValidate.class.getDeclaredMethod("formatRuntimeRow",
                LibraryValidator.RuntimeEntry.class);
        m.setAccessible(true);
        return (String) m.invoke(null, e);
    }


    private static void appendCsv(StringBuilder sb, String value) throws Exception
    {
        Method m = CdiscValidate.class.getDeclaredMethod("appendCsv", StringBuilder.class,
                String.class);
        m.setAccessible(true);
        m.invoke(null, sb, value);
    }

    // ------------------------------------------------------------------
    // resolveMetadataProducts (-mp -> resolved standards/... keys)
    // ------------------------------------------------------------------


    @Test
    void resolveMetadataProducts_fullFormTokenResolvesAgainstAnEmptyCache(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path emptyDir)
        throws Exception
    {
        CdiscValidate.Args a = new CdiscValidate.Args();
        a.metadataProducts = new ArrayList<>(List.of("adam/adamig-1-3"));
        // A real full-form key resolves whatever the catalogue holds: with the API cache
        // configured (Phase 7b) it matches the catalogue key; with no source at all it passes
        // verbatim in full-key form. Either way the resolved key is identical.
        a.pickleCache = emptyDir.toString();
        assertEquals(List.of("standards/adam/adamig-1-3"), resolveMetadataProducts(a));
    }


    @Test
    void resolveMetadataProducts_badTokenBecomesAUsageError(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path emptyDir)
        throws Exception
    {
        CdiscValidate.Args a = new CdiscValidate.Args();
        // ⚠ Phase 7b superseded "no pickle cache => full-key tokens only": a bare token of a real
        // product may now resolve through the API-side catalogue (CDISC_API_CACHE). A token no
        // source publishes stays a usage error in every configuration.
        a.metadataProducts = new ArrayList<>(List.of("adamig-9-9"));
        a.pickleCache = emptyDir.toString();
        java.lang.reflect.InvocationTargetException ite = org.junit.jupiter.api.Assertions
                .assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> resolveMetadataProducts(a));
        // The cause is the CLI's UsageException (exit code 2), naming the offending token.
        assertEquals("UsageException", ite.getCause().getClass().getSimpleName());
        assertTrue(ite.getCause().getMessage().contains("adamig-9-9"), ite.getCause().getMessage());
    }


    @SuppressWarnings("unchecked")
    private static List<String> resolveMetadataProducts(CdiscValidate.Args a) throws Exception
    {
        Class<?> argsCls = Class.forName(CdiscValidate.class.getName() + "$Args");
        Method m = CdiscValidate.class.getDeclaredMethod("resolveMetadataProducts", argsCls);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, a);
    }

    // ------------------------------------------------------------------
    // F3b: catalogue-branch sensitivity - a BARE token whose resolved form
    // differs from its verbatim form, against a fabricated pickle catalogue
    // ------------------------------------------------------------------


    /**
     * Branch-sensitive resolution proof (review finding F3b): the token below is a <b>bare</b>
     * product id whose resolved form ({@code standards/zzz/zzztestig-9-9}) differs from its
     * verbatim form, and the key it must resolve to exists <b>only</b> in the pickle catalogue
     * fabricated into the temp dir - the CDISC Library API union cannot supply it, and the
     * empty-catalogue verbatim-passthrough branch cannot resolve a bare token at all. If the
     * catalogue returned empty unconditionally, this test fails with a usage error instead of
     * resolving.
     */
    @Test
    void resolveMetadataProducts_bareTokenResolvesThroughTheCatalogue_notVerbatim(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
        throws Exception
    {
        writeStandardsPickle(dir, "standards/zzz/zzztestig-9-9", "standards/zzz/zzzotherig-1-0");
        CdiscValidate.Args a = new CdiscValidate.Args();
        a.metadataProducts = new ArrayList<>(List.of("zzztestig-9-9"));
        a.pickleCache = dir.toString();
        assertEquals(List.of("standards/zzz/zzztestig-9-9"), resolveMetadataProducts(a));
    }


    /** Writes a {@code standards_details.pkl} holding the given keys, pickled as Python would. */
    private static void writeStandardsPickle(java.nio.file.Path dir, String... keys)
        throws java.io.IOException
    {
        Map<String, Object> standards = new LinkedHashMap<>();
        for (String key : keys)
        {
            standards.put(key, Map.of());
        }
        java.nio.file.Files.write(dir.resolve("standards_details.pkl"),
                new net.razorvine.pickle.Pickler().dumps(standards));
    }

    // ------------------------------------------------------------------
    // F3a: toParams wiring - parsed -mp tokens must land on the built params
    // ------------------------------------------------------------------


    /**
     * Review finding F3a: nothing exercised the path from CLI args to the built
     * {@link net.cumba.corej.core.run.StudyValidationParams} - deleting the
     * {@code .metadataProducts(resolveMetadataProducts(args))} line in {@code toParams} left the
     * whole suite green. This test reds on exactly that deletion: without the wiring the params
     * carry NO metadata products at all (Plan 2 R5 removed {@code -s}/{@code -v} and with them the
     * implied default), so the list below is empty instead of the two resolved keys.
     */
    @Test
    void toParams_declaredMetadataProducts_landOnTheBuiltParamsInOrder(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path emptyPickleDir)
        throws Exception
    {
        CdiscValidate.Args a = new CdiscValidate.Args();
        a.data = "/tmp";
        a.pickleCache = emptyPickleDir.toString();
        // Full-form tokens resolve in every catalogue configuration (verbatim with no source,
        // identically via the catalogue) - this test pins the WIRING, the catalogue-branch
        // test above pins the resolution.
        a.metadataProducts = new ArrayList<>(List.of("adam/adam-occds-1-1", "adam/adamig-1-3"));

        net.cumba.corej.core.run.StudyValidationParams p = toParams(a);

        assertEquals(List.of("standards/adam/adam-occds-1-1", "standards/adam/adamig-1-3"),
                p.metadataProducts(),
                "declared -mp products must reach StudyValidationParams, resolved, in order");
    }


    private static net.cumba.corej.core.run.StudyValidationParams toParams(CdiscValidate.Args a)
        throws Exception
    {
        Class<?> argsCls = Class.forName(CdiscValidate.class.getName() + "$Args");
        Method m = CdiscValidate.class.getDeclaredMethod("toParams", argsCls,
                net.cumba.datatable.manager.IDataTableManager.class,
                LibraryValidator.RuntimeListener.class);
        m.setAccessible(true);
        return (net.cumba.corej.core.run.StudyValidationParams) m.invoke(null, a,
                new net.cumba.datatable.manager.local.LocalDataTableManager(), null);
    }


    private static String optionName(String token) throws Exception
    {
        Class<?> argsCls = Class.forName(CdiscValidate.class.getName() + "$Args");
        Method m = argsCls.getDeclaredMethod("optionName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, token);
    }
}
