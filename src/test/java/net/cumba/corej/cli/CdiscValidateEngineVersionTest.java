package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
     * D5: the CLI no longer carries an engine artifact id. A constant named
     * {@code CORE_ENGINE_VERSION} coming back would be the hard-coded id returning, which is the
     * exact shape that stamped {@code "unknown"} for weeks.
     */
    @Test
    void theCliCarriesNoHardCodedEngineArtifactId()
    {
        assertThrows(NoSuchFieldException.class,
                () -> CdiscValidate.class.getDeclaredField("CORE_ENGINE_VERSION"),
                "CdiscValidate must not carry a CORE_ENGINE_VERSION constant: the engine version "
                        + "is the engine jar's own (StudyValidationService())");
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
