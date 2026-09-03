package net.cumba.corej.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RemoteConfig} URL/auth resolution and CLI-over-system-property precedence.
 */
class RemoteConfigTest
{

    @BeforeEach
    @AfterEach
    void clearProps()
    {
        System.clearProperty(RemoteConfig.PROP_URL);
        System.clearProperty(RemoteConfig.PROP_USER);
        System.clearProperty(RemoteConfig.PROP_PASSWORD);
        System.clearProperty(RemoteConfig.PROP_TOKEN);
    }


    private static CdiscValidate.Args args()
    {
        return new CdiscValidate.Args();
    }


    @Test
    void notRemoteWhenNoUrlAnywhere()
    {
        assertFalse(RemoteConfig.resolve(args()).isRemote());
    }


    @Test
    void cliUrlOverridesSystemProperty()
    {
        System.setProperty(RemoteConfig.PROP_URL, "http://sys:8080");
        CdiscValidate.Args a = args();
        a.remote = "http://cli:9090";
        RemoteConfig c = RemoteConfig.resolve(a);
        assertTrue(c.isRemote());
        assertEquals("http://cli:9090", c.baseUrl());
    }


    @Test
    void systemPropertyUrlUsedWhenNoCliOption()
    {
        System.setProperty(RemoteConfig.PROP_URL, "http://sys:8080/");
        RemoteConfig c = RemoteConfig.resolve(args());
        assertTrue(c.isRemote());
        assertEquals("http://sys:8080", c.baseUrl()); // trailing slash stripped
    }


    @Test
    void basicAuthHeaderFromCliOptions()
    {
        CdiscValidate.Args a = args();
        a.remote = "http://h";
        a.remoteUser = "alice";
        a.remotePassword = "secret";
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, RemoteConfig.resolve(a).authHeader());
    }


    @Test
    void basicAuthHeaderFromSystemProperties()
    {
        System.setProperty(RemoteConfig.PROP_URL, "http://h");
        System.setProperty(RemoteConfig.PROP_USER, "bob");
        System.setProperty(RemoteConfig.PROP_PASSWORD, "pw");
        String expected = "Basic "
                + Base64.getEncoder().encodeToString("bob:pw".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, RemoteConfig.resolve(args()).authHeader());
    }


    @Test
    void bearerTokenHeader()
    {
        CdiscValidate.Args a = args();
        a.remote = "http://h";
        a.remoteToken = "tok123";
        assertEquals("Bearer tok123", RemoteConfig.resolve(a).authHeader());
    }


    @Test
    void tokenWinsOverBasic()
    {
        CdiscValidate.Args a = args();
        a.remote = "http://h";
        a.remoteUser = "alice";
        a.remotePassword = "secret";
        a.remoteToken = "tok";
        assertEquals("Bearer tok", RemoteConfig.resolve(a).authHeader());
    }


    @Test
    void noAuthHeaderWhenUnconfigured()
    {
        CdiscValidate.Args a = args();
        a.remote = "http://h";
        assertNull(RemoteConfig.resolve(a).authHeader());
    }
}
