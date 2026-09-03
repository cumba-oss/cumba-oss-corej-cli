package net.cumba.dataviewer.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.provider.DataTableProviderFactory;
import net.cumba.datatable.io.FileInfo;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.provider.csv.CsvProviderSupplier;
import net.cumba.datatable.provider.csv.CsvTableProvider;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Fix #161 — <b>a blank cell must not depend on the file format.</b>
 * <p>
 * The defect Fix #161 fixed was not a tidiness problem — <b>the same study, the same rule and the
 * same blank cell produced different findings depending only on which file format the sponsor
 * wrote.</b> Two loaders caused it by mapping a blank cell to {@code MissingValue} <em>regardless
 * of column type</em>: the Parquet reader for any null cell, and the CSV reader for a ragged row
 * (one carrying fewer fields than the header declares).
 *
 * <h2>⚠ Re-based: the goal is unchanged, the mechanism moved</h2>
 * <p>
 * Fix #161 secured that goal <b>at ingestion</b>, by never letting a {@code MissingValue} into a
 * character column (<em>"for STRING we map from null to empty string"</em>,
 * {@code AbstractDataBuffer.createDataValue}). That is no longer the mechanism. A format that can
 * express an explicit {@code null} in a character column — Dataset-JSON and Parquet — now loads it
 * as a {@code MissingValue}, so the <em>representations differ by format again</em> and the old
 * assertion here ({@code assertFalse(dv.isMissingOrInvalid())} on every character cell) is red by
 * construction.
 * <p>
 * Finding-equivalence is instead guaranteed <b>at consumption</b>: every blankness consumer asks
 * {@code isEmptyOrMissing()}, for which a {@code MissingValue} and a {@code ""} are both blank.
 * <b>So this test now pins the predicate, not the representation</b> — which is the stronger claim,
 * because it is the one rule evaluation actually depends on. The representation is pinned too, but
 * only where the format cannot express a null, so no loader can quietly manufacture one.
 * <p>
 * ⚠ Never delete a guard whose original defect is still real. The defect is still real; only the
 * layer that prevents it has moved.
 *
 * <h2>The measured capability table</h2>
 * <p>
 * {@link Format#canExpressCharacterNull} is a <em>measurement</em>, not an assumption — it is what
 * each loader actually does today, and {@link #aBlankCellResolvesByColumnTypeInEveryFormat(Format)}
 * fails if a loader stops matching it:
 * <ul>
 * <li><b>Dataset-JSON</b> — yes, a JSON {@code null}.</li>
 * <li><b>Parquet</b> — yes, an unset optional field.</li>
 * <li><b>CSV</b> — no; an empty field is {@code ""} and a ragged row is deliberately {@code ""} too
 * (inventing a null for the ragged case would re-create the "same file reports differently
 * depending on whether the row was short" defect).</li>
 * <li><b>CDT</b> — no, by two independent mechanisms. {@code CdtValues.missingFor} answers
 * {@code MissingValue.MIS} for a numeric column but {@code null} for a CHAR column, and
 * {@code AbstractDataBuffer.createDataValue} maps that {@code null} to {@code ""}. Separately,
 * {@code CdtParser} folds an <em>unquoted</em> {@code .} to {@code ""} for a column of any type,
 * character included, before {@code CdtValues.parseValue} ever sees it — so a literal {@code .}
 * character value has to be quoted, and {@code parseValue}'s own {@code aType != CHAR} guard on the
 * {@code .} sentinel is unreachable for a bare one. A CDT row must also carry exactly the declared
 * field count ({@code CdtParser} throws otherwise), so there is no short-row path either.</li>
 * <li><b>XLSX</b> — no; {@code ExcelTableProvider}'s STRING arm stores
 * {@code row.getStringValue(...)} unconditionally, so a wholly blank spreadsheet cell and a cell
 * holding {@code ""} collapse to the same {@code ""}. (Excel itself <em>could</em> tell them apart;
 * the loader does not.)</li>
 * <li><b>SAS7BDAT / XPT</b> — no; a SAS character variable cannot be null.</li>
 * </ul>
 * <p>
 * This test lives in the CLI module because that is the one module where <em>every</em> registered
 * data-table provider is on the classpath at once. It is parameterised over the formats rather than
 * duplicated per provider, and {@link #everyRegisteredProviderFormatIsCovered()} fails when a
 * provider registers a format no case here exercises — so the next provider added is covered by
 * construction rather than by someone remembering.
 */
class BlankCellFormatIndependenceTest
{

    /** Character column name used by every synthetic fixture. */
    private static final String CHARCOL = "CHARCOL";

    /** Numeric column name used by every synthetic fixture. */
    private static final String NUMCOL = "NUMCOL";

    /** Row 0 of the two null-capable fixtures: a real value, the control. */
    private static final long ROW_POPULATED = 0L;

    /** Row 1 of the two null-capable fixtures: an explicit source {@code null}. */
    private static final long ROW_SOURCE_NULL = 1L;

    /** Row 2 of the two null-capable fixtures: an empty string the file genuinely contains. */
    private static final long ROW_EMPTY_STRING = 2L;

    /**
     * Registered formats that are an alternate encoding handled by a provider a {@link Format} case
     * already exercises, mapped to the case that covers that provider. Keeping this explicit is
     * what makes {@link #everyRegisteredProviderFormatIsCovered()} a real guard: a genuinely new
     * provider is not listed here, so it turns the guard red.
     */
    private static final Map<String, Format> ALTERNATE_ENCODINGS = Map.of(//
            "xls", Format.XLSX, // same ExcelTableProvider, older container
            "ndjson", Format.DATASET_JSON, // same DsjTableProvider, newline-delimited
            "dsjc", Format.DATASET_JSON); // same DsjTableProvider, compressed

    @TempDir
    Path tempDir;

    @FunctionalInterface
    interface Fixture
    {

        IDataTable load(Path aDir) throws Exception;
    }


    /**
     * One case per data-table format coreJ can read. Each builds a table carrying at least one
     * blank CHARACTER cell, and — where the format can express one at all — at least one blank
     * NUMERIC cell.
     */
    enum Format
    {

        /** Ordinary CSV: every row is full width, so the ragged branch is never reached. */
        CSV("csv", true, false, BlankCellFormatIndependenceTest::loadCsv),

        /**
         * Ragged CSV: the last row carries a single field, so the two cells under test are
         * <em>absent</em> from the record and only the ragged branch can produce them. The ordinary
         * branch is structurally unreachable for those column indices, so the two paths cannot mask
         * each other — which is the whole point, since a fixture where both paths could produce the
         * blank would pin nothing.
         */
        CSV_RAGGED("csv", true, false, BlankCellFormatIndependenceTest::loadRaggedCsv),

        /**
         * Cumba Data Table, the engine's own text format.
         * <p>
         * ⚠ {@code carriesBlankNumeric} is <b>false</b>, and not by choice: a blank {@code Num}
         * cell cannot be loaded through {@code CdtTableProvider} at all.
         * {@code CdtValues.parseValue} maps the empty field to {@code null} and
         * {@code CdtTableBuilder.populate} hands that straight to
         * {@code CachedDataTableColumn.addElement}, where {@code DataBufferDouble} rejects it with
         * {@code IllegalArgumentException: Invalid value: null}. That is a pre-existing defect on a
         * different code path from Fix #161 (which only moves the character path) and is
         * deliberately left alone here — see the Fix #161 ledger entry, where it is surfaced rather
         * than swept in. The character half below is unaffected and is asserted.
         */
        CDT("cdt", false, false, BlankCellFormatIndependenceTest::loadCdt),

        /**
         * CDISC Dataset-JSON — one of the two formats that <b>can</b> express an explicit
         * {@code null} in a character column. Its fixture carries a {@code null} and a genuine
         * {@code ""} in the <em>same</em> character column; a fixture carrying only one of the two
         * would be vacuous, because it passes under either design.
         */
        DATASET_JSON("json", true, true, BlankCellFormatIndependenceTest::loadDatasetJson),

        /** Excel. */
        XLSX("xlsx", true, false, BlankCellFormatIndependenceTest::loadXlsx),

        /** Apache Parquet — the second null-capable format; same two-cell fixture shape. */
        PARQUET("parquet", true, true, BlankCellFormatIndependenceTest::loadParquet),

        /** SAS v5 transport — the checked-in ADSL fixture. */
        XPT("xpt", true, false, _ -> loadRepoFixture("testdata/xpt/01_plain/adsl.xpt")),

        /** SAS7BDAT — the checked-in ADSL fixture. */
        SAS7BDAT("sas7bdat", true, false, _ -> loadRepoFixture(
                "testdata/sas7bdat/01_plain/adsl.sas7bdat"));

        private final String extension;

        private final boolean carriesBlankNumeric;

        /**
         * Whether this format's loader can put a {@code MissingValue} into a <b>character</b>
         * column — measured per loader, see the class javadoc. Only the two null-capable formats
         * may do so; for every other format a character cell that reads as missing means a loader
         * has started manufacturing nulls the file never contained.
         */
        private final boolean canExpressCharacterNull;

        /**
         * Error Prone cannot see that a method-reference {@link Fixture} is stateless; every
         * constant here binds a static method of the enclosing test.
         */
        @SuppressWarnings("ImmutableEnumChecker")
        private final Fixture fixture;

        Format(String aExtension, boolean aCarriesBlankNumeric, boolean aCanExpressCharacterNull,
                Fixture aFixture)
        {
            extension = aExtension;
            carriesBlankNumeric = aCarriesBlankNumeric;
            canExpressCharacterNull = aCanExpressCharacterNull;
            fixture = aFixture;
        }
    }

    // ---------------------------------------------------------------- the contract

    @ParameterizedTest
    @EnumSource(Format.class)
    void aBlankCellResolvesByColumnTypeInEveryFormat(Format aFormat) throws Exception
    {
        IDataTable table = aFormat.fixture.load(tempDir);
        assertNotNull(table, "no table loaded for " + aFormat);

        DataTableMeta meta = table.getMetaData();
        int blankCharacterCells = 0;
        int missingNumericCells = 0;

        for (long r = 0; r < table.getRowCount(); r++)
        {
            for (int c = 0; c < meta.getColumnCount(); c++)
            {
                DataTableColumnMeta col = meta.getColumn(c);
                IDataValue dv = table.getDataValue(r, c);
                long row = r;
                int colIdx = c;

                if (col.getType() == DataValueType.STRING)
                {
                    if (!dv.isEmptyOrMissing())
                    {
                        continue;
                    }
                    blankCharacterCells++;

                    // The re-based Fix #161 contract: whatever the format, the BLANKNESS answer is
                    // the same, and it is the same through all three entry points (a rule reaches
                    // cells through each of them).
                    assertTrue(table.isEmptyOrMissing(row, colIdx),
                            () -> aFormat + ": IDataTable.isEmptyOrMissing disagreed with the cell "
                                    + "for character column " + col.getName() + " row " + row);
                    assertTrue(table.getColumn(colIdx).isEmptyOrMissing(row),
                            () -> aFormat + ": IDataTableColumn.isEmptyOrMissing disagreed with "
                                    + "the cell for character column " + col.getName() + " row "
                                    + row);

                    if (!aFormat.canExpressCharacterNull)
                    {
                        // This format has no way to say "null" in a character column, so a blank
                        // one must still be the empty string. A loader that started answering
                        // MISSING here would be manufacturing a distinction the file never made —
                        // exactly Fix #161's defect, in its original shape.
                        assertFalse(dv.isMissingOrInvalid(), () -> aFormat + ": character column "
                                + col.getName()
                                + " reported a MISSING cell, but this format cannot express "
                                + "a character null — the loader invented one");
                        assertEquals("", dv.getValueAsString(), () -> aFormat
                                + ": character column " + col.getName() + " row " + row);
                    }
                }
                else if (col.getType() == DataValueType.DOUBLE
                        || col.getType() == DataValueType.LONG)
                {
                    if (dv.isMissingOrInvalid())
                    {
                        missingNumericCells++;
                        assertTrue(table.isEmptyOrMissing(row, colIdx),
                                () -> aFormat + ": a missing numeric cell must also be blank ("
                                        + col.getName() + " row " + row + ")");
                    }
                }
            }
        }

        // A zero is not a measurement: without these two, the loop above would pass on a fixture
        // that carries no blank at all and would assert nothing whatsoever.
        assertTrue(blankCharacterCells > 0,
                aFormat + ": fixture carries no blank CHARACTER cell, so the probe proves nothing");
        if (aFormat.carriesBlankNumeric)
        {
            assertTrue(missingNumericCells > 0, aFormat
                    + ": fixture carries no blank NUMERIC cell, so the numeric half proves nothing");
        }
    }


    /**
     * The two halves of the settled design, on the <b>same character column</b> of the same file.
     * <p>
     * ⚠ A fixture carrying only a {@code null} <em>or</em> only a {@code ""} is vacuous — it passes
     * under either design. Both must be present, and the assertions must pull in opposite
     * directions:
     * </p>
     * <ol>
     * <li><b>the model distinguishes them</b> — the source {@code null} is a {@code MissingValue},
     * the empty string is an empty string. Reverting the provider change (mapping a null STRING
     * cell back to {@code ""}) reddens this half and only this half;</li>
     * <li><b>every blankness consumer is blind to the difference</b> — the value-, column- and
     * table-level {@code isEmptyOrMissing} all answer {@code true} for both, and
     * {@code ScalarSemantics.isMissing} (the predicate the operator families run on) agrees.
     * Deleting the {@code MissingValue} arm of {@code isEmptyOrMissing} reddens this half and only
     * this half.</li>
     * </ol>
     */
    @ParameterizedTest
    @EnumSource(value = Format.class, names =
    {
            "DATASET_JSON", "PARQUET"
    })
    void aSourceNullAndAGenuineEmptyStringAreDistinctInTheModelButBlankToEveryConsumer(
            Format aFormat)
        throws Exception
    {
        IDataTable table = aFormat.fixture.load(tempDir);
        int charCol = table.getMetaData().getColumnIndex(CHARCOL);
        assertTrue(charCol >= 0, aFormat + ": fixture has no " + CHARCOL);
        assertEquals(3L, table.getRowCount(),
                aFormat + ": fixture must carry a value row, a null row and an empty-string row");

        IDataValue sourceNull = table.getDataValue(ROW_SOURCE_NULL, charCol);
        IDataValue emptyString = table.getDataValue(ROW_EMPTY_STRING, charCol);

        // (1) the model keeps them apart
        assertTrue(sourceNull.isMissingOrInvalid(),
                aFormat + ": an explicit source null in a character column must load as a "
                        + "MissingValue — this is the half the provider change buys");
        assertFalse(emptyString.isMissingOrInvalid(),
                aFormat + ": an empty string the file genuinely contains must stay an empty "
                        + "string and must never be promoted to a MissingValue");
        assertEquals("", emptyString.getValueAsString(), aFormat + ": the empty-string cell");

        // (2) every blankness consumer is blind to it
        for (long row : new long[]
        {
                ROW_SOURCE_NULL, ROW_EMPTY_STRING
        })
        {
            assertTrue(table.getDataValue(row, charCol).isEmptyOrMissing(),
                    aFormat + ": IDataValue.isEmptyOrMissing, row " + row);
            assertTrue(table.getColumn(charCol).isEmptyOrMissing(row),
                    aFormat + ": IDataTableColumn.isEmptyOrMissing, row " + row);
            assertTrue(table.isEmptyOrMissing(row, charCol),
                    aFormat + ": IDataTable.isEmptyOrMissing, row " + row);
        }

        // The populated control row: without it every assertion above would hold on a column that
        // simply answers "blank" to everything.
        assertFalse(table.getDataValue(ROW_POPULATED, charCol).isEmptyOrMissing(),
                aFormat + ": the populated control row must not read as blank");
        assertFalse(table.getColumn(charCol).isEmptyOrMissing(ROW_POPULATED),
                aFormat + ": the populated control row must not read as blank (column level)");
    }


    /**
     * The construction guard. Every file format a registered provider claims must be exercised by a
     * {@link Format} case, or listed in {@link #ALTERNATE_ENCODINGS} as another encoding of a
     * provider that is. Adding a provider without adding a case turns this red.
     */
    @Test
    void everyRegisteredProviderFormatIsCovered()
    {
        List<FileInfo> registered = DataTableProviderFactory.getFactory().getFileInfos();

        Set<String> registeredExtensions = registered.stream()//
                .map(FileInfo::getFileExtension)//
                .map(e -> e.toLowerCase(Locale.ROOT))//
                .collect(Collectors.toCollection(TreeSet::new));

        // Positive control: prove provider discovery actually found providers on this classpath, so
        // an empty registration list cannot make the assertion below vacuously true.
        assertTrue(registeredExtensions.contains("parquet"),
                "provider discovery found no Parquet provider — this guard is not measuring "
                        + "anything: " + registeredExtensions);
        assertTrue(registeredExtensions.size() >= 8,
                "provider discovery looks incomplete: " + registeredExtensions);

        Set<String> covered = EnumSet.allOf(Format.class).stream()//
                .map(f -> f.extension)//
                .collect(Collectors.toCollection(TreeSet::new));
        covered.addAll(ALTERNATE_ENCODINGS.keySet());

        Set<String> uncovered = new TreeSet<>(registeredExtensions);
        uncovered.removeAll(covered);

        assertTrue(uncovered.isEmpty(), "a registered provider format is not covered by any "
                + "BlankCellFormatIndependenceTest.Format case, so nothing pins its blank-cell "
                + "behaviour: " + uncovered);
    }


    /** Guards against an accidentally emptied enum making the parameterised test vacuous. */
    @Test
    void everyFormatCaseIsExercised()
    {
        assertEquals(8, Format.values().length,
                "unexpected Format case count: " + List.of(Format.values()));
    }

    // ---------------------------------------------------------------- fixtures


    private static IDataTable loadViaFactory(Path aFile) throws IOException
    {
        // The 3-arg provide() is overloaded on the last parameter; the metadata-library overload is
        // the extension-dispatching one used by the study loader.
        IDataTable table = DataTableProviderFactory.getFactory().provide(aFile.toUri(), null,
                (IMetadataLibrary) null);
        assertNotNull(table, "no provider resolved for " + aFile);
        return table;
    }


    /**
     * Loads a checked-in study fixture from the repository root. ADSL carries blank character cells
     * (DISCONFL, DSRAEFL, DTHFL, DCSREAS) and missing numeric cells (BMIBL, WEIGHTBL) in both
     * encodings; the assertion loop counts them and fails if they ever disappear.
     */
    private static IDataTable loadRepoFixture(String aRelPath) throws IOException
    {
        File f = new File(System.getProperty("repoRoot"), aRelPath);
        assertTrue(f.isFile(), "fixture missing: " + f);
        return loadViaFactory(f.toPath());
    }


    private static IDataTable loadCsv(Path aDir) throws IOException
    {
        Path file = aDir.resolve("blankcell.csv");
        // The last line must be "," — a wholly empty line is dropped by the CSV parser.
        Files.writeString(file, CHARCOL + "," + NUMCOL + "\nA,1\n,\n", StandardCharsets.UTF_8);
        return loadViaFactory(file);
    }


    private static IDataTable loadRaggedCsv(Path aDir) throws IOException
    {
        Path file = aDir.resolve("blankcell-ragged.csv");
        // Rows 0 and 1 are full width and fix the column types; row 2 carries one field only.
        Files.writeString(file, "KEEPCOL," + CHARCOL + "," + NUMCOL + "\nx,A,1\ny,B,2\nz\n",
                StandardCharsets.UTF_8);

        // Type detection samples the first guessingRowCount rows. A ragged row inside that window
        // makes CsvRecord.getValue throw, which forces every affected column to STRING and would
        // leave the numeric half of this case untestable — so the window is narrowed to the two
        // full rows. That is the only reason this case does not go through the factory.
        CsvTableProvider provider = new CsvTableProvider();
        provider.setGuessingRowCount(2);
        return provider.provide(file.toUri(), CsvProviderSupplier.FI_CSV);
    }


    private static IDataTable loadCdt(Path aDir) throws IOException
    {
        Path file = aDir.resolve("blankcell.cdt");
        // NUMCOL keeps a real value on both rows: see Format.CDT — a blank Num cell cannot be
        // loaded through CdtTableProvider at all.
        String body = """
                dataset BLANKTEST
                col KEEPCOL type=Char
                col CHARCOL type=Char
                col NUMCOL type=Num
                ---
                x | A | 1
                y |  | 2
                ---
                """;
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return loadViaFactory(file);
    }


    private static IDataTable loadDatasetJson(Path aDir) throws IOException
    {
        Path file = aDir.resolve("blankcell.json");
        String body = """
                {
                  "datasetJSONCreationDateTime": "2026-08-05T10:00:00",
                  "datasetJSONVersion": "1.1.0",
                  "itemGroupOID": "IG.BLANKTEST",
                  "name": "BLANKTEST",
                  "label": "Blank cell test",
                  "records": 3,
                  "columns": [
                    {"itemOID": "IT.CHARCOL", "name": "CHARCOL", "label": "Char", \
                "dataType": "string"},
                    {"itemOID": "IT.NUMCOL", "name": "NUMCOL", "label": "Num", \
                "dataType": "double"}
                  ],
                  "rows": [
                    ["A", 1.0],
                    [null, null],
                    ["", 2.0]
                  ]
                }
                """;
        // Row 1 CHARCOL is a JSON null — an explicit "no value" the format CAN express — and row 2
        // CHARCOL is an empty string the file genuinely contains. Both in the same character
        // column, because a fixture carrying only one of the two passes under either design and so
        // pins nothing.
        Files.writeString(file, body, StandardCharsets.UTF_8);
        return loadViaFactory(file);
    }


    private static IDataTable loadXlsx(Path aDir) throws IOException
    {
        Path file = aDir.resolve("blankcell.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook())
        {
            Sheet sheet = wb.createSheet("BLANKTEST");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue(CHARCOL);
            head.createCell(1).setCellValue(NUMCOL);

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("A");
            first.createCell(1).setCellValue(1.0d);

            // Row 2's cells are created BLANK and never given a value. A blank cell is what the
            // reader turns into null; setting "" instead would make NUMCOL sniff as STRING and
            // silently remove the numeric half of this case.
            Row blank = sheet.createRow(2);
            blank.createCell(0);
            blank.createCell(1);

            try (OutputStream out = Files.newOutputStream(file))
            {
                wb.write(out);
            }
        }
        return loadViaFactory(file);
    }


    private static IDataTable loadParquet(Path aDir) throws IOException
    {
        Path file = aDir.resolve("blankcell.parquet");
        MessageType schema = Types.buildMessage()//
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                .named(CHARCOL)//
                .optional(PrimitiveTypeName.DOUBLE).named(NUMCOL)//
                .named("table");

        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withType(schema).withCompressionCodec(CompressionCodecName.SNAPPY)
                .withConf(new Configuration()).build())
        {
            writer.write(factory.newGroup().append(CHARCOL, "A").append(NUMCOL, 1.0d));
            // Row 1: both fields unset — the only way to reach the provider's null branch, and the
            // Parquet way of saying "no value".
            writer.write(factory.newGroup());
            // Row 2: CHARCOL is present and holds an empty string. Same column as row 1's null,
            // because a fixture carrying only one of the two passes under either design.
            writer.write(factory.newGroup().append(CHARCOL, "").append(NUMCOL, 2.0d));
        }
        return loadViaFactory(file);
    }
}
