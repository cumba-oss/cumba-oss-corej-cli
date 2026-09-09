package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails — and cleans up after — any test that lets the CLI write into the <b>process working
 * directory</b> instead of the target the test named.
 *
 * <p>
 * Every end-to-end case in this module passes an explicit {@code -o} and, where relevant, an
 * explicit {@code --dictionaries-dir}, both under a {@code @TempDir}. The CLI's own defaults are
 * CWD-relative ({@code ./dictionaries} for the dictionary store, a timestamped
 * {@code CORE-Report-…} stem for the report), so a <em>single</em> flipped guard in
 * {@code installTargetDir} or {@code resolveOutputBase} silently redirects the whole write here.
 * That is not hypothetical: the 2026-09-07 pitest run left a partial {@code dictionaries/} store
 * and 40 {@code CORE-Report-*.runtime.csv} files in the module root.
 * </p>
 *
 * <p>
 * <b>Why this is an extension and not a {@code @BeforeAll} assertion.</b> The class-level guard it
 * replaces ({@code noAmbientDictionaryStoreMayBeConfigured}) was self-disarming: once a mutant had
 * created {@code ./dictionaries}, <em>every</em> later pitest minion for that class failed in
 * {@code @BeforeAll}, JUnit reported a container error, and pitest scored the mutant
 * {@code RUN_ERROR} with {@code detected='true'} and {@code numberOfTestsRun='0'} — 37 mutants
 * counted as killed with no test having observed them. {@link #afterEach} inverts that: it
 * <b>asserts</b> the redirect, so the mutant is KILLED by a test that ran and observed it, and it
 * removes what that test wrote, so the leak cannot poison the next test or the next minion.
 * </p>
 *
 * <h2>⚠ What this extension will NEVER delete</h2>
 *
 * <p>
 * <b>Only entries this JVM watched appear are ever removed.</b> The listing taken before the first
 * test of the JVM ran — {@link #ambient()} — is off limits to both callbacks, permanently. This is
 * not a nicety: {@code ./dictionaries} is the CLI's <em>own default install target</em>, so a
 * developer who ran {@code cdisc-validate --install-dictionaries} from the module root has a real
 * store there, possibly containing <b>MedDRA or WHODrug — licensed distributions obtained by hand
 * and not re-downloadable</b>. An earlier revision of this class swept CLI-shaped names out of the
 * module root in {@code beforeEach} unconditionally, recursively and silently; under surefire that
 * was harmless ({@code <workingDirectory>} is {@code target/test-cwd}), but a pitest minion
 * inherits Maven's CWD and it would have destroyed such a store with no message.
 * </p>
 *
 * <p>
 * A pre-existing CLI-shaped entry is therefore <b>reported, never removed</b> — {@link #beforeEach}
 * fails loudly, the way the {@code @BeforeAll} guard did, because the test outcomes here genuinely
 * depend on its absence (an ambient {@code ./dictionaries} is found by the resolver's CWD tier and
 * turns a degraded-run case into a healthy one). Silent deletion is not an acceptable alternative
 * in either direction: it destroys real data, and it hides the very leak this extension exists to
 * surface.
 * </p>
 *
 * <p>
 * ⚠ Residual, deliberately not papered over: a minion killed mid-test (a pitest {@code TIMED_OUT})
 * cannot run {@link #afterEach}, so its leak becomes the <em>next</em> minion's ambient state and
 * every test of that minion then fails here. That is loud and correct as a report, but it still
 * over-counts those mutants as detected. Closing it needs the product-side half of F-cli-01 — a
 * CWD-relative default that a single flipped guard can reach — which is an open owner decision, not
 * something the test layer can fix.
 * </p>
 */
final class WorkingDirectoryStaysCleanExtension implements BeforeEachCallback, AfterEachCallback
{

    /**
     * The process working directory. ⚠ It is <b>not the same directory under surefire and under
     * pitest</b>: surefire is configured with
     * {@code <workingDirectory>${project.build.directory}/test-cwd</workingDirectory>}, which is
     * inside the (git-ignored) build output, while a pitest minion inherits Maven's own CWD — the
     * module root. That difference is why the leak this extension catches was invisible in every
     * {@code mvn test} run and left 40 untracked files in the repository after the pitest gate.
     */
    private static final Path MODULE_DIR = Path.of("").toAbsolutePath();

    /**
     * The working-directory listing as it was before the first test of this JVM ran. Computed once
     * per JVM (a pitest minion is a fresh JVM, so each minion sees what it inherited) and never
     * mutated. Everything in it pre-dates the run and is off limits to both callbacks.
     */
    @Nullable
    private static Set<String> jvmAmbient;

    private final Path root;

    /**
     * Non-null only for the unit test of this class, which drives it against a {@code @TempDir}.
     */
    @Nullable
    private final Set<String> injectedAmbient;

    /**
     * The listing taken at the start of the <em>current</em> test.
     *
     * <p>
     * ⚠ Per-instance state: JUnit creates one extension instance per declaring class, so this is
     * shared by every test of that class and is correct only while they run one at a time. That
     * holds today — surefire's {@code forkCount} is unset (one fork) and no
     * {@code junit.jupiter.execution.parallel.*} configuration exists in this module — and the
     * whole approach (snapshot a shared directory, diff it) is meaningless under parallel execution
     * anyway. Enabling it would make attribution wrong, not destructive: {@link #ambient()} is
     * still never swept, so the worst case is a misattributed failure.
     * </p>
     */
    private Set<String> before = Set.of();

    /** False when {@link #beforeEach} bailed out, so {@link #afterEach} must not diff or delete. */
    private boolean armed;

    WorkingDirectoryStaysCleanExtension()
    {
        root = MODULE_DIR;
        injectedAmbient = null;
    }


    /** Test seam: drives the extension against a scratch root with a stated ambient listing. */
    WorkingDirectoryStaysCleanExtension(Path aRoot, Set<String> aAmbient)
    {
        root = aRoot;
        injectedAmbient = Set.copyOf(aAmbient);
    }


    @Override
    public void beforeEach(ExtensionContext aContext) throws IOException
    {
        enter();
    }


    /** {@link #beforeEach} without a JUnit context, so the unit test of this class can drive it. */
    void enter() throws IOException
    {
        armed = false;
        List<String> inherited = generatedByTheCli(ambient());
        if (!inherited.isEmpty())
        {
            fail(ambientLeakMessage(inherited, root));
        }
        before = listing(root);
        armed = true;
    }


    @Override
    public void afterEach(ExtensionContext aContext) throws IOException
    {
        exit();
    }


    /** {@link #afterEach} without a JUnit context, so the unit test of this class can drive it. */
    void exit() throws IOException
    {
        if (!armed)
        {
            return;
        }
        Set<String> appeared = listing(root);
        appeared.removeAll(before);
        // Belt and braces: whatever `before` says, nothing that pre-dates this JVM is touched.
        appeared.removeAll(ambient());
        if (appeared.isEmpty())
        {
            return;
        }
        List<String> swept = new ArrayList<>();
        List<String> left = new ArrayList<>();
        for (String name : appeared)
        {
            if (isGeneratedByTheCli(name))
            {
                deleteRecursively(root.resolve(name));
                swept.add(name);
            }
            else
            {
                left.add(name);
            }
        }
        fail("the CLI wrote into the process working directory " + root
                + " instead of the target this test named: removed " + swept + ", left " + left
                + ". The write is the defect — a CWD-relative default was reached although the "
                + "test passed an explicit target, which is exactly what a single flipped guard "
                + "in installTargetDir / resolveOutputBase does. The removal only stops the leak "
                + "poisoning the next test (and, under pitest, the next minion); it is safe "
                + "because these entries appeared while this very test ran.");
    }


    /** The listing that pre-dates this run. Never swept — see the class javadoc. */
    private Set<String> ambient() throws IOException
    {
        return injectedAmbient != null ? injectedAmbient : jvmAmbient();
    }


    private static synchronized Set<String> jvmAmbient() throws IOException
    {
        if (jvmAmbient == null)
        {
            jvmAmbient = Set.copyOf(listing(MODULE_DIR));
        }
        return jvmAmbient;
    }


    /**
     * The message for a CLI-shaped entry that was already there. It has to name the possibility of
     * a real, licensed store, because that is the case where deleting would be unrecoverable.
     */
    static String ambientLeakMessage(List<String> aInherited, Path aRoot)
    {
        return "the working directory " + aRoot + " already contained " + aInherited
                + " before this test ran, and the CLI's own defaults write exactly those names "
                + "there. This extension will NOT delete them: ./dictionaries is the "
                + "--install-dictionaries default target, so it may be a real store holding "
                + "licensed MedDRA / WHODrug data that cannot be downloaded again. Move or remove "
                + "them by hand, then re-run. If they appeared during a pitest run they are the "
                + "residue of a minion that was killed mid-test after a mutant redirected an "
                + "install to the CWD — that leak is the defect, and this message is the report "
                + "of it (F-cli-01, product side).";
    }


    /** The subset of {@code aNames} that matches a shape the CLI generates from a CWD default. */
    static List<String> generatedByTheCli(Set<String> aNames)
    {
        return aNames.stream().filter(WorkingDirectoryStaysCleanExtension::isGeneratedByTheCli)
                .sorted().toList();
    }


    /**
     * The shapes the CLI generates from its CWD-relative defaults: the dictionary store, and the
     * timestamped default report stem with every extension the report SPI and the runtime CSVs
     * append to it.
     */
    private static boolean isGeneratedByTheCli(String aName)
    {
        String lower = aName.toLowerCase(Locale.ROOT);
        return "dictionaries".equals(lower) || lower.startsWith("core-report-");
    }


    private static Set<String> listing(Path aRoot) throws IOException
    {
        try (Stream<Path> entries = Files.list(aRoot))
        {
            return entries.map(Path::getFileName).map(String::valueOf)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
    }


    private static void deleteRecursively(Path aRoot) throws IOException
    {
        if (!Files.exists(aRoot))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(aRoot))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }
}
