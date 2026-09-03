package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code --output-format} / {@code --max-report-rows} parsing and the shared-base
 * filename derivation that lets {@code json,xlsx} write both files from one stem (Phase 4 of the
 * Excel-report plan). The private static helpers are reached via reflection so they can stay
 * private.
 */
class CdiscValidateOutputFormatTest
{

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    void parse_outputFormatXlsx_isAccepted() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-of", "xlsx");
        assertTrue(outputFormats(parsed).contains("xlsx"));
    }


    @Test
    void parse_outputFormatJsonAndXlsx_commaSeparated() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-of", "json,xlsx");
        assertTrue(outputFormats(parsed).contains("json"));
        assertTrue(outputFormats(parsed).contains("xlsx"));
    }


    @Test
    void parse_outputFormatRepeatable() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-of", "json", "-of", "xlsx");
        assertEquals(2, outputFormats(parsed).size());
    }


    @Test
    void parse_unsupportedFormat_throwsUsage()
    {
        InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> parse("-d", "/tmp", "-of", "pdf"));
        assertTrue(ite.getCause().getMessage().contains("xlsx"), ite.getCause().getMessage());
    }


    @Test
    void parse_outputFormatJson2_isAccepted() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-of", "json2");
        assertTrue(outputFormats(parsed).contains("json2"));
    }


    @Test
    void parse_outputFormatJsonAndJson2_commaSeparated() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-of", "json,json2");
        assertTrue(outputFormats(parsed).contains("json"));
        assertTrue(outputFormats(parsed).contains("json2"));
    }


    @Test
    void parse_maxReportRows_isParsed() throws Exception
    {
        Object parsed = parse("-d", "/tmp", "-mr", "500");
        assertEquals(500, maxReportRows(parsed));
    }


    @Test
    void parse_maxReportRowsNonNumeric_throwsUsage()
    {
        assertThrows(InvocationTargetException.class, () -> parse("-d", "/tmp", "-mr", "lots"));
    }

    // ------------------------------------------------------------------
    // resolveOutputFormats
    // ------------------------------------------------------------------


    @Test
    void resolveOutputFormats_emptyDefaultsToJson() throws Exception
    {
        assertEquals(List.of("json"), resolveOutputFormats(Set.of()));
    }


    @Test
    void resolveOutputFormats_lowercasesAndDedups() throws Exception
    {
        // LinkedHashSet preserves order; values are lower-cased.
        Set<String> in = new java.util.LinkedHashSet<>(List.of("XLSX", "Json"));
        assertEquals(List.of("xlsx", "json"), resolveOutputFormats(in));
    }

    // ------------------------------------------------------------------
    // resolveOutputBase + reportPathFor
    // ------------------------------------------------------------------


    @Test
    void resolveOutputBase_stripsKnownExtensions() throws Exception
    {
        assertEquals("report", resolveOutputBase("report.json").getFileName().toString());
        assertEquals("report", resolveOutputBase("report.xlsx").getFileName().toString());
        assertEquals("report", resolveOutputBase("report.xls").getFileName().toString());
    }


    @Test
    void resolveOutputBase_keepsUnknownExtension() throws Exception
    {
        // Unknown extension is part of the stem (Python parity → report.txt.json).
        assertEquals("report.txt", resolveOutputBase("report.txt").getFileName().toString());
    }


    @Test
    void resolveOutputBase_stripsV2JsonCompoundExtension() throws Exception
    {
        // The compound .v2.json is stripped to the bare stem (longer suffix first), so
        // --output report.v2.json forms the same base as report.json.
        assertEquals("report", resolveOutputBase("report.v2.json").getFileName().toString());
    }


    @Test
    void reportPathFor_json2_mapsToV2JsonDoubleExtension() throws Exception
    {
        Path base = resolveOutputBase("/out/report.json");
        Path json = reportPathFor(base, "json");
        Path json2 = reportPathFor(base, "json2");
        assertEquals("report.json", json.getFileName().toString());
        assertEquals("report.v2.json", json2.getFileName().toString());
        assertEquals(json.getParent(), json2.getParent());
    }


    @Test
    void reportPathFor_sharesStemSwapsExtension() throws Exception
    {
        Path base = resolveOutputBase("/out/report.json");
        Path json = reportPathFor(base, "json");
        Path xlsx = reportPathFor(base, "xlsx");
        assertEquals("report.json", json.getFileName().toString());
        assertEquals("report.xlsx", xlsx.getFileName().toString());
        assertEquals(json.getParent(), xlsx.getParent());
    }


    @Test
    void defaultBase_jsonAndXlsxShareStem() throws Exception
    {
        // No -o supplied: both formats derive from the same CORE-Report-<ts> stem.
        Path base = resolveOutputBase(null);
        String stem = base.getFileName().toString();
        assertTrue(stem.startsWith("CORE-Report-"), stem);
        assertNull(extension(stem), "default base must carry no extension");
        assertEquals(stem + ".json", reportPathFor(base, "json").getFileName().toString());
        assertEquals(stem + ".xlsx", reportPathFor(base, "xlsx").getFileName().toString());
    }

    // ------------------------------------------------------------------
    // reflection plumbing
    // ------------------------------------------------------------------


    private static Object parse(String... argv) throws Exception
    {
        Class<?> argsCls = Class.forName(CdiscValidate.class.getName() + "$Args");
        Method m = argsCls.getDeclaredMethod("parse", String[].class);
        m.setAccessible(true);
        return m.invoke(null, (Object) argv);
    }


    @SuppressWarnings("unchecked")
    private static Set<String> outputFormats(Object args) throws Exception
    {
        var f = args.getClass().getDeclaredField("outputFormats");
        f.setAccessible(true);
        return (Set<String>) f.get(args);
    }


    private static Integer maxReportRows(Object args) throws Exception
    {
        var f = args.getClass().getDeclaredField("maxReportRows");
        f.setAccessible(true);
        return (Integer) f.get(args);
    }


    @SuppressWarnings("unchecked")
    private static List<String> resolveOutputFormats(Set<String> requested) throws Exception
    {
        Method m = CdiscValidate.class.getDeclaredMethod("resolveOutputFormats", Set.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, requested);
    }


    private static Path resolveOutputBase(String output) throws Exception
    {
        Method m = CdiscValidate.class.getDeclaredMethod("resolveOutputBase", String.class);
        m.setAccessible(true);
        return (Path) m.invoke(null, output);
    }


    private static Path reportPathFor(Path base, String format) throws Exception
    {
        Method m = CdiscValidate.class.getDeclaredMethod("reportPathFor", Path.class, String.class);
        m.setAccessible(true);
        return (Path) m.invoke(null, base, format);
    }


    private static String extension(String name)
    {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : null;
    }
}
