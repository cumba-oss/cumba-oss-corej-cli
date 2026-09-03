package net.cumba.dataviewer.examples;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the {@code --remote} target and its authentication for {@link CdiscValidate}.
 *
 * <p>
 * Each value is taken from the command-line option when present, otherwise from a system property
 * (precedence: <b>CLI option &gt; system property</b>). A later change will load those system
 * properties from a fixed properties file (user home, falling back to the install dir) so a user
 * can pin local/remote mode without passing flags — this resolver already reads {@link System
 * #getProperty}, so that feature only needs to populate the properties.
 * </p>
 *
 * <p>
 * Mode is <em>remote</em> iff a base URL resolves. Authentication is bearer-token when a token
 * resolves, else HTTP Basic when a user resolves (token wins if both are set), else none.
 * </p>
 */
final class RemoteConfig
{

    static final String PROP_URL = "corej.remote.url";

    static final String PROP_USER = "corej.remote.user";

    static final String PROP_PASSWORD = "corej.remote.password";

    static final String PROP_TOKEN = "corej.remote.token";

    private final @Nullable String baseUrl;

    private final @Nullable String authHeader;

    private RemoteConfig(@Nullable String baseUrl, @Nullable String authHeader)
    {
        this.baseUrl = baseUrl;
        this.authHeader = authHeader;
    }


    static RemoteConfig resolve(CdiscValidate.Args args)
    {
        String url = firstNonBlank(args.remote, System.getProperty(PROP_URL));
        String token = firstNonBlank(args.remoteToken, System.getProperty(PROP_TOKEN));
        String user = firstNonBlank(args.remoteUser, System.getProperty(PROP_USER));
        String password = firstNonBlank(args.remotePassword, System.getProperty(PROP_PASSWORD));

        String auth = null;
        if (token != null)
        {
            auth = "Bearer " + token;
        }
        else if (user != null)
        {
            String raw = user + ":" + (password != null ? password : "");
            auth = "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        return new RemoteConfig(normalise(url), auth);
    }


    boolean isRemote()
    {
        return baseUrl != null;
    }


    /** Base URL with any trailing slash removed; {@code null} when no URL resolved. */
    @Nullable
    String baseUrl()
    {
        return baseUrl;
    }


    /** The {@code Authorization} header value, or {@code null} for unauthenticated. */
    @Nullable
    String authHeader()
    {
        return authHeader;
    }


    private static @Nullable String normalise(@Nullable String url)
    {
        if (url == null)
        {
            return null;
        }
        String trimmed = url.strip();
        while (trimmed.endsWith("/"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }


    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b)
    {
        if (a != null && !a.isBlank())
        {
            return a;
        }
        if (b != null && !b.isBlank())
        {
            return b;
        }
        return null;
    }
}
