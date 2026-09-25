package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.Field;
import net.cumba.corej.core.VersionInfo;
import org.junit.jupiter.api.Test;

/**
 * Pins the engine version the CLI stamps into every report's {@code CORE_Engine_Version}.
 *
 * <p>
 * {@link VersionInfo#forArtifact} matches its key against each jar's {@code /version.properties}
 * and answers {@code "unknown"} for a key no jar declares, with nothing failing. That is how the
 * internal twin of this CLI stamped engine version {@code "unknown"} into every report until
 * 2026-09-25: it still used the pre-rename key {@code "corej-core"}. This CLI was always right; the
 * test keeps it that way. The control below keeps that old key in view: it must resolve to nothing,
 * which is what proves the positive assertion can tell the two apart.
 * </p>
 */
class CdiscValidateEngineVersionTest
{

    private static final String UNKNOWN = "unknown";

    @Test
    void theEngineVersionResolvesToTheEngineJar() throws ReflectiveOperationException
    {
        VersionInfo engine = VersionInfo.forArtifact("cumba-oss-corej-core");
        assertEquals("cumba-oss-corej-core", engine.artifactId(),
                "CONTROL FAILED: no jar on the test classpath declares artifactId cumba-oss-corej-core,"
                        + " so this test cannot tell a resolved version from a missing one");
        assertNotEquals(UNKNOWN, engine.version(),
                "the engine jar's version.properties is unfiltered");

        assertEquals(engine.version(), coreEngineVersion(),
                "the CLI must stamp the engine jar's own version into CORE_Engine_Version");
    }


    @Test
    void thePreRenameArtifactIdResolvesToNothing()
    {
        // Negative control: the key the CLI used before the fix. If this ever starts resolving,
        // the assertion above no longer proves the fix is what makes the version known.
        assertEquals(UNKNOWN, VersionInfo.forArtifact("corej-core").version());
    }


    private static String coreEngineVersion() throws ReflectiveOperationException
    {
        Field f = CdiscValidate.class.getDeclaredField("CORE_ENGINE_VERSION");
        f.setAccessible(true);
        return (String) f.get(null);
    }
}
