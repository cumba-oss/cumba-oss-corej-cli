package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.core.VersionInfo;
import net.cumba.corej.core.run.StudyValidationService;
import org.junit.jupiter.api.Test;

/**
 * Pins where the engine version the CLI stamps into every report's {@code CORE_Engine_Version}
 * comes from: the engine jar itself, never a name this CLI hard-codes.
 *
 * <p>
 * Until 2026-09-25 the internal twin of this CLI looked the version up under the pre-rename
 * artifact id {@code "corej-core"}. {@link VersionInfo#forArtifact} matches that key against each
 * jar's {@code /version.properties} and answers {@code "unknown"} for a key no jar declares, so
 * every report it wrote carried engine version {@code "unknown"} with nothing failing. The fix of
 * that day replaced the key; {@code PLAN-report-conformance-fields} (D4/D5) then removed the
 * constant from both twins: {@code new StudyValidationService()} resolves the version of the jar
 * containing the engine ({@link VersionInfo#forClass}), so there is no artifact id here to go stale
 * again, and this file reads the same in both twins. The control below keeps the old key in view:
 * it must still resolve to nothing, which is what proves the positive assertion can tell the two
 * apart. The end-to-end half — that a CLI run's report really carries this value — is
 * {@code CdiscValidateTest.run_endToEnd_reportStatesTheAppliedCapAndTheEngineVersion}.
 * </p>
 */
class CdiscValidateEngineVersionTest
{

    private static final String UNKNOWN = "unknown";

    @Test
    void theEngineVersionComesFromTheEngineJarItself()
    {
        VersionInfo engine = VersionInfo.forClass(StudyValidationService.class);
        assertNotEquals(UNKNOWN, engine.artifactId(),
                "CONTROL FAILED: the engine jar on the test classpath carries no filtered "
                        + "version.properties, so this test cannot tell a resolved version from "
                        + "a missing one");
        assertNotEquals(UNKNOWN, engine.version(),
                "the engine jar's version.properties is unfiltered");
        // The same jar answers the same version under its own artifact id: the self-resolving
        // lookup and the by-name lookup agree, so nothing about the stamp depends on the name.
        assertEquals(VersionInfo.forArtifact(engine.artifactId()).version(), engine.version());
    }


    /**
     * D5, as BEHAVIOUR rather than a field name (review L7): no main source of this CLI looks an
     * engine version up by artifact id any more. A {@code VersionInfo.forArtifact(} call or a
     * {@code "…corej-core"} literal coming back would be the hard-coded id returning, which is the
     * exact shape that stamped {@code "unknown"} for weeks. The value the CLI stamps is pinned end
     * to end by
     * {@code CdiscValidateTest.run_endToEnd_reportStatesTheAppliedCapAndTheEngineVersion}.
     */
    @Test
    void noMainSourceLooksTheEngineVersionUpByArtifactId() throws IOException
    {
        Path mainJava = Path.of(System.getProperty("projectBasedir", ".")).resolve("src/main/java");
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(mainJava))
        {
            sources = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertTrue(sources.stream().anyMatch(p -> p.endsWith("CdiscValidate.java")),
                "CONTROL: the main sources were found under " + mainJava + ": " + sources);
        for (Path source : sources)
        {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("forArtifact("),
                    source + " must not look an engine version up by artifact id");
            assertFalse(text.contains("corej-core\""),
                    source + " must not carry the engine's artifact id as a literal");
        }
    }


    @Test
    void thePreRenameArtifactIdResolvesToNothing()
    {
        // Negative control: the key the CLI used before the 2026-09-25 fix. If this ever starts
        // resolving, the assertion above no longer proves the lookup is what makes the version
        // known.
        assertEquals(UNKNOWN, VersionInfo.forArtifact("corej-core").version());
    }
}
