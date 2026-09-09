package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * Drift guard between {@link CdiscValidate}'s two help-text sources.
 *
 * <p>
 * picocli is used for <em>parsing only</em>: {@code CommandLine.usage(...)} is never called and
 * there is no {@code mixinStandardHelpOptions}, so an {@code @Option(description = ...)} is never
 * rendered to a user. The hand-written {@code printUsage} banner is the only help anyone sees —
 * which is exactly why the two drifted apart unnoticed until 2026-09.
 * </p>
 *
 * <p>
 * This test walks picocli's own command model rather than the Java source, so it cannot be fooled
 * by formatting, and asserts that every <b>non-hidden</b> option is reachable from the banner. The
 * hidden ones ({@code Args#compatSink}, the accepted-but-ignored Python-CLI compatibility names)
 * are deliberately undocumented and are skipped.
 * </p>
 */
@org.junit.jupiter.api.extension.ExtendWith(WorkingDirectoryStaysCleanExtension.class)
class CdiscValidateOptionCoverageTest
{

    /**
     * Long option names that are a strict prefix of another option's name ({@code --rules} vs
     * {@code --rules-dir}, {@code --snomed} vs {@code --snomed-version-select}) must not be
     * credited to their longer sibling's banner entry, so a bare {@code contains} will not do: the
     * name has to be followed by something that cannot continue an option name.
     */
    private static boolean documented(String banner, String longName)
    {
        return Pattern.compile(Pattern.quote(longName) + "(?![A-Za-z0-9-])").matcher(banner).find();
    }


    /**
     * C2-01. The owner ruled 2026-09-08 that {@code 0} is unlimited and a <b>negative</b> cap fails
     * loud. {@code Args.parse} rejects a negative, so no printed contract may still promise
     * {@code "<= 0 = unlimited"} — the banner (asserted in {@code CdiscValidateUsageTest}) and this
     * {@code @Option} description, which is the second of the two help-text sources this class
     * exists to keep from drifting.
     */
    @Test
    void maxErrorsPerRule_printedContractMatchesTheRuledBehaviour()
    {
        CommandLine.Model.OptionSpec option = new CommandLine(new CdiscValidate.Args())
                .getCommandSpec().findOption("--max-errors-per-rule");
        String description = String.join(" ", option.description());

        assertTrue(description.contains("0 = unlimited"), description);
        assertTrue(description.contains("negative value is rejected"), description);
        assertTrue(!description.contains("<= 0"),
                "the description still promises that a negative cap means unlimited, which "
                        + "Args.parse rejects with exit 2: " + description);
    }


    @Test
    void everyVisibleOption_appearsInTheHelpBanner() throws Exception
    {
        String banner = helpOutput();
        List<String> undocumented = new ArrayList<>();
        int visible = 0;
        for (CommandLine.Model.OptionSpec option : new CommandLine(new CdiscValidate.Args())
                .getCommandSpec().options())
        {
            if (option.hidden())
            {
                continue;
            }
            visible++;
            boolean found = false;
            for (String name : option.names())
            {
                if (name.startsWith("--") && documented(banner, name))
                {
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                undocumented.add(String.join(", ", option.names()));
            }
        }
        // Guards the guard: if reflection ever stopped seeing the model, the loop above would
        // pass vacuously.
        assertTrue(visible > 40, "expected the parser model to expose the full option set, saw "
                + visible + " visible options");
        assertTrue(undocumented.isEmpty(),
                () -> "these non-hidden @Option(s) are missing from printUsage's banner — the "
                        + "banner is the only help a user ever sees, so an option absent from it "
                        + "is undiscoverable: " + undocumented);
    }


    /**
     * The two options the guard above first caught: both were parsed and honoured but appeared in
     * no help text a user could reach. Pinned by name so a later banner rewrite cannot drop them
     * back out silently.
     */
    @ParameterizedTest(name = "banner documents [{0}]")
    @ValueSource(strings =
    {
            "-sl, --severity-level <level>", "-me, --max-errors-per-rule <n>"
    })
    void banner_documentsThePreviouslyUndocumentedRunOptions(String entry) throws Exception
    {
        assertTrue(helpOutput().contains(entry),
                () -> "help banner must document: <" + entry + ">");
    }


    private static String helpOutput() throws Exception
    {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        OfflineCli.run(new String[]
        {
                "-h"
        }, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return outBuf.toString(StandardCharsets.UTF_8);
    }
}
