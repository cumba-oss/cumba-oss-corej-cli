package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

/**
 * C2-03. The extension that keeps the module root clean must never destroy anything it did not
 * itself watch appear.
 *
 * <p>
 * {@code ./dictionaries} is the CLI's own {@code --install-dictionaries} default target, so a
 * developer who ran the CLI from the module root has a real store there — possibly holding MedDRA
 * or WHODrug, licensed distributions obtained by hand and <b>not re-downloadable</b>. An earlier
 * revision of the extension deleted that recursively and silently at the start of every test. Under
 * surefire it never bit ({@code <workingDirectory>} is {@code target/test-cwd}); under pitest,
 * whose minions inherit Maven's CWD, it would have. These cases pin both halves of the rule: a
 * pre-existing store survives untouched and is <em>reported</em>, and only entries that appeared
 * while the test ran are swept.
 * </p>
 */
class WorkingDirectoryStaysCleanExtensionTest
{

    private static Path licensedStore(Path aRoot) throws Exception
    {
        Path asc = aRoot.resolve("dictionaries/meddra/26-1/pt.asc");
        Files.createDirectories(asc.getParent());
        Files.writeString(asc, "a licensed distribution that cannot be downloaded again");
        return asc;
    }


    /** The core of C2-03: pre-existing means untouchable, in both callbacks. */
    @Test
    void aPreExistingDictionaryStoreIsReportedAndLeftUntouched(@TempDir Path root) throws Exception
    {
        Path licensed = licensedStore(root);
        WorkingDirectoryStaysCleanExtension ext = new WorkingDirectoryStaysCleanExtension(root,
                Set.of("dictionaries"));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class, ext::enter);

        assertTrue(Files.exists(licensed),
                "a pre-existing dictionary store must survive the extension: it may be licensed "
                        + "MedDRA / WHODrug data that cannot be obtained again");
        assertEquals("a licensed distribution that cannot be downloaded again",
                Files.readString(licensed));
        assertTrue(failure.getMessage().contains("will NOT delete"), failure.getMessage());
        assertTrue(failure.getMessage().contains("dictionaries"), failure.getMessage());

        // afterEach still runs after a failed beforeEach; it must not delete either.
        ext.exit();
        assertTrue(Files.exists(licensed), "afterEach must not sweep what beforeEach refused to");
    }


    /**
     * The other half: what the CLI writes <em>during</em> the test is the leak the extension exists
     * to catch, so it is both failed on and removed.
     */
    @Test
    void aLeakThatAppearsDuringTheTestIsFailedAndSwept(@TempDir Path root) throws Exception
    {
        WorkingDirectoryStaysCleanExtension ext = new WorkingDirectoryStaysCleanExtension(root,
                Set.of());
        ext.enter();
        Files.createDirectories(root.resolve("dictionaries/medrt"));
        Files.writeString(root.resolve("CORE-Report-20260908T101010.runtime.csv"), "x");

        AssertionFailedError failure = assertThrows(AssertionFailedError.class, ext::exit);

        assertTrue(failure.getMessage().contains("wrote into the process working directory"),
                failure.getMessage());
        assertFalse(Files.exists(root.resolve("dictionaries")),
                "a leak this test watched appear must be removed, or it poisons the next minion");
        assertFalse(Files.exists(root.resolve("CORE-Report-20260908T101010.runtime.csv")));
    }


    /** A clean run is silent, and files that are not CLI-shaped are never deleted. */
    @Test
    void aCleanRunPassesAndForeignFilesAreLeftForTheDeveloper(@TempDir Path root) throws Exception
    {
        WorkingDirectoryStaysCleanExtension ext = new WorkingDirectoryStaysCleanExtension(root,
                Set.of());
        ext.enter();
        ext.exit();

        ext.enter();
        Files.writeString(root.resolve("notes.txt"), "mine");
        AssertionFailedError failure = assertThrows(AssertionFailedError.class, ext::exit);
        assertTrue(Files.exists(root.resolve("notes.txt")),
                "only the CLI's own generated shapes may be removed");
        assertTrue(failure.getMessage().contains("left [notes.txt]"), failure.getMessage());
    }


    @Test
    void onlyTheCliOwnGeneratedShapesAreRecognised()
    {
        assertEquals(List.of("CORE-Report-x.runtime.csv", "dictionaries"),
                WorkingDirectoryStaysCleanExtension.generatedByTheCli(
                        Set.of("pom.xml", "src", "dictionaries", "CORE-Report-x.runtime.csv")));
    }
}
