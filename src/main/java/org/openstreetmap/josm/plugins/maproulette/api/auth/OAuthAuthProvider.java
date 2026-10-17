// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.io.StringReader;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import jakarta.annotation.Nullable;
import jakarta.json.Json;
import jakarta.json.JsonException;

import org.openstreetmap.josm.data.preferences.ListProperty;
import org.openstreetmap.josm.io.auth.CredentialsAgentException;
import org.openstreetmap.josm.io.auth.CredentialsManager;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.JosmRuntimeException;
import org.openstreetmap.josm.tools.Logging;

/**
 * Log in to MapRoulette with OAuth 2 (authorization code with PKCE), as a public client whose browser redirect
 * comes back through JOSM's remote control. The tokens are kept per server host in JOSM's credential store.
 */
public final class OAuthAuthProvider implements AuthProvider {
    /**
     * The servers that accept logins from JOSM, as their root URL (the API URL without {@code /api/v2}).
     * Login is offered only for these, so a server that doesn't support it never shows a login that fails.
     */
    public static final ListProperty LOGIN_SERVERS = new ListProperty("maproulette.oauth.servers",
            // JOSM's ListSetting checks for null entries, which an immutable List.of refuses
            Collections.singletonList("https://mr-api.osm.lol"));
    /**
     * The client id MapRoulette registered for this plugin
     */
    static final String CLIENT_ID = "maproulette-josm";
    /**
     * The scopes to ask for: read and change tasks, and the editor actions (comments, lock refresh, changesets)
     */
    static final String SCOPE = "tasks:read tasks:write tasks:edit";
    private static final String API_PATH = "/api/v2";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Gets an authorization code from the user
     */
    interface Authorizer {
        /**
         * Let the user log in and approve the plugin
         *
         * @param authorizeUri The MapRoulette authorization page
         * @param state        The state that the answer must carry
         * @return The authorization code
         * @throws MapRouletteException if the user did not log in
         * @throws InterruptedException if the thread was interrupted while waiting
         */
        String authorize(URI authorizeUri, String state) throws MapRouletteException, InterruptedException;

        /**
         * Stop waiting for a login in progress
         */
        void cancel();
    }

    private final String root;
    private final String host;
    private final Authorizer authorizer;
    private final Clock clock;
    /**
     * Held while the tokens change, so only one refresh runs: MapRoulette revokes the login if a refresh token is
     * used twice
     */
    private final Object tokenLock = new Object();
    @Nullable
    private volatile OAuthTokens tokens;
    /**
     * Set while a login waits for the browser, so every login control shows it
     */
    private final AtomicBoolean loggingIn = new AtomicBoolean();
    /**
     * The MapRoulette id of the logged-in user, once looked up
     */
    @Nullable
    private volatile Long userId;
    /**
     * Counts logins and logouts, so a user id looked up for an earlier login is not kept
     */
    private final AtomicLong loginGeneration = new AtomicLong();

    OAuthAuthProvider(String root, Authorizer authorizer, Clock clock) {
        this.root = root;
        this.host = host(root);
        this.authorizer = authorizer;
        this.clock = clock;
    }

    /**
     * Read the stored login. Credential stores like a system keyring can block, so call it off the EDT.
     */
    public void loadStored() {
        final var stored = load(this.host);
        synchronized (this.tokenLock) {
            if (this.tokens == null) {
                this.tokens = stored;
            }
        }
        if (stored != null) {
            MapRouletteClient.getInstance().fireAuthChanged();
        }
    }

    /**
     * Get the login for a MapRoulette server
     *
     * @param apiUrl The server's API URL, e.g. {@code https://mr-api.osm.lol/api/v2}
     * @return The login, or {@code null} if the server does not accept logins from JOSM
     */
    @Nullable
    public static OAuthAuthProvider forServer(String apiUrl) {
        final var root = serverRoot(apiUrl);
        if (root == null || !LOGIN_SERVERS.get().contains(root)) {
            return null;
        }
        return new OAuthAuthProvider(root, new LoopbackAuthorization(), Clock.systemUTC());
    }

    /**
     * Get the root URL of a MapRoulette server
     *
     * @param apiUrl The server's API URL
     * @return The URL without the API path, or {@code null} if it isn't a MapRoulette API URL we can send tokens to
     */
    @Nullable
    static String serverRoot(@Nullable String apiUrl) {
        if (apiUrl == null) {
            return null;
        }
        var root = apiUrl.strip();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (!root.endsWith(API_PATH)) {
            return null;
        }
        root = root.substring(0, root.length() - API_PATH.length());
        try {
            return mayCarryToken(URI.create(root + '/').toURL(), host(root)) ? root : null;
        } catch (IllegalArgumentException | MalformedURLException | JosmRuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        return this.tokens != null;
    }

    @Override
    public Set<Capability> capabilities() {
        final var current = this.tokens;
        if (current == null) {
            return Collections.emptySet();
        }
        final var capabilities = EnumSet.noneOf(Capability.class);
        if (current.scope().contains("tasks:write")) {
            capabilities.add(Capability.LOCK);
            capabilities.add(Capability.SET_STATUS);
            if (current.scope().contains("tasks:edit")) {
                capabilities.add(Capability.COMMENT);
                capabilities.add(Capability.REFRESH_LOCK);
            }
        }
        return capabilities;
    }

    @Override
    public void authenticate(HttpClient client, boolean loginRequired) throws MapRouletteAuthUnavailableException {
        // Reads go without the token: it only travels with requests that need it
        if (!loginRequired) {
            return;
        }
        // Never let a redirect carry the token somewhere else, e.g. from https to http on the same host
        client.setMaxRedirects(-1);
        if (!mayCarryToken(client.getURL(), this.host)) {
            return;
        }
        var current = this.tokens;
        if (current != null && current.expiresSoon(this.clock.instant())) {
            current = refresh(current);
        }
        if (current == null) {
            throw new MapRouletteAuthUnavailableException("Not logged in to MapRoulette");
        }
        client.setHeader("Authorization", "Bearer " + current.accessToken());
    }

    @Override
    public boolean onUnauthorized(HttpClient failed) {
        final var current = this.tokens;
        final var sent = failed.getRequestHeader("Authorization");
        if (current == null || sent == null) {
            // A read, or a request sent while logged out: a refresh would not change the answer
            return false;
        }
        if (!("Bearer " + current.accessToken()).equals(sent)) {
            // Another request refreshed the token since this one was sent: try again with the new token
            return true;
        }
        return refresh(current) != null;
    }

    @Override
    public OptionalLong userId() throws MapRouletteException {
        final var known = this.userId;
        if (known != null || this.tokens == null) {
            return knownUserId();
        }
        final var generation = this.loginGeneration.get();
        final var id = fetchUserId();
        synchronized (this.tokenLock) {
            if (generation == this.loginGeneration.get() && this.tokens != null) {
                this.userId = id;
            }
        }
        return OptionalLong.of(id);
    }

    @Override
    public OptionalLong knownUserId() {
        final var known = this.userId;
        return known == null || this.tokens == null ? OptionalLong.empty() : OptionalLong.of(known);
    }

    /**
     * Ask MapRoulette who is logged in
     *
     * @return The user's MapRoulette id
     * @throws MapRouletteException if MapRoulette could not be asked
     */
    private long fetchUserId() throws MapRouletteException {
        final HttpClient client;
        try {
            client = HttpClient.create(URI.create(this.root + "/oauth/mobile/me").toURL())
                    .setAccept("application/json");
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new MapRouletteException("The MapRoulette server URL is not valid: " + this.root, e);
        }
        try {
            authenticate(client, true);
            final var response = client.connect();
            final var content = response.fetchContent();
            if (response.getResponseCode() != 200) {
                throw new MapRouletteException(
                        "MapRoulette did not say who is logged in: HTTP " + response.getResponseCode());
            }
            try (var reader = Json.createReader(new StringReader(Objects.requireNonNullElse(content, "")))) {
                return reader.readObject().getJsonNumber("id").longValueExact();
            }
        } catch (IOException | JsonException | NullPointerException | ClassCastException | ArithmeticException e) {
            throw new MapRouletteException("Could not read who is logged in to MapRoulette", e);
        } finally {
            client.disconnect();
        }
    }

    /**
     * Get the server this login is for
     *
     * @return The server's root URL
     */
    public String server() {
        return this.root;
    }

    /**
     * Log in. This waits for the user to finish in the browser, so call it on its own thread, not the EDT and not
     * JOSM's worker.
     *
     * @throws MapRouletteException if the login failed
     * @throws InterruptedException if the thread was interrupted while waiting for the user
     */
    public void logIn() throws MapRouletteException, InterruptedException {
        if (!this.loggingIn.compareAndSet(false, true)) {
            throw new MapRouletteException(tr("A login to MapRoulette is already waiting for the browser."));
        }
        MapRouletteClient.getInstance().fireAuthChanged();
        try {
            waitForLogIn();
        } finally {
            this.loggingIn.set(false);
            MapRouletteClient.getInstance().fireAuthChanged();
        }
    }

    /**
     * Check if a login is waiting for the browser
     *
     * @return {@code true} while {@link #logIn()} runs
     */
    public boolean isLoggingIn() {
        return this.loggingIn.get();
    }

    private void waitForLogIn() throws MapRouletteException, InterruptedException {
        final var verifier = randomToken();
        final var state = randomToken();
        final var query = new LinkedHashMap<String, String>();
        query.put("response_type", "code");
        query.put("client_id", CLIENT_ID);
        query.put("redirect_uri", LoopbackAuthorization.REDIRECT_URI);
        query.put("scope", SCOPE);
        query.put("state", state);
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");
        final var code = this.authorizer.authorize(URI.create(this.root + "/oauth/mobile/authorize?" + form(query)),
                state);
        final var body = new LinkedHashMap<String, String>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", LoopbackAuthorization.REDIRECT_URI);
        body.put("client_id", CLIENT_ID);
        body.put("code_verifier", verifier);
        final OAuthTokens received;
        try {
            received = postToken(body);
        } catch (IOException | JsonException | NullPointerException | ClassCastException | ArithmeticException e) {
            throw new MapRouletteException(tr("MapRoulette did not accept the login."), e);
        }
        synchronized (this.tokenLock) {
            this.loginGeneration.incrementAndGet();
            this.userId = null;
            store(received);
        }
        Logging.info("Logged in to MapRoulette at " + this.root);
    }

    /**
     * Stop waiting for a login in progress
     */
    public void cancelLogIn() {
        this.authorizer.cancel();
    }

    /**
     * Log out: tell MapRoulette to revoke the login, then forget the tokens. Call it off the EDT.
     */
    public void logOut() {
        final OAuthTokens current;
        synchronized (this.tokenLock) {
            current = this.tokens;
            this.loginGeneration.incrementAndGet();
            this.userId = null;
            store(null);
        }
        Logging.info("Logged out of MapRoulette at " + this.root);
        MapRouletteClient.getInstance().fireAuthChanged();
        if (current != null) {
            final var body = new LinkedHashMap<String, String>();
            body.put("client_id", CLIENT_ID);
            // Revoking the refresh token ends the whole login, access tokens included
            body.put("token", current.refreshToken());
            HttpClient client = null;
            try {
                client = tokenEndpointClient("/oauth/mobile/revoke", body);
                final var status = client.connect().getResponseCode();
                if (status != 200) {
                    Logging.warn("MapRoulette did not revoke the login: HTTP " + status);
                }
            } catch (IOException e) {
                Logging.warn("Could not reach MapRoulette to revoke the login");
                Logging.debug(e);
            } finally {
                if (client != null) {
                    client.disconnect();
                }
            }
        }
    }

    /**
     * Get a new token pair. Only one refresh runs at a time; a request that waited for another refresh uses its
     * result.
     *
     * @param used The tokens the caller has
     * @return The new tokens, or {@code null} if the user has to log in again
     */
    @Nullable
    private OAuthTokens refresh(OAuthTokens used) {
        synchronized (this.tokenLock) {
            final var current = this.tokens;
            if (current != used) {
                return current;
            }
            final var body = new LinkedHashMap<String, String>();
            body.put("grant_type", "refresh_token");
            body.put("refresh_token", used.refreshToken());
            body.put("client_id", CLIENT_ID);
            OAuthTokens received = null;
            try {
                received = postToken(body);
            } catch (UnknownHostException | ConnectException e) {
                // The request never reached MapRoulette (offline), so the refresh token is still good
                Logging.warn("Could not reach MapRoulette to refresh the login");
                Logging.debug(e);
                return used;
            } catch (IOException | JsonException | NullPointerException | ClassCastException | ArithmeticException e) {
                // The refresh token may have been used up even if the answer got lost, and using it again would
                // revoke the login, so the user has to log in again
                Logging.warn("Could not refresh the MapRoulette login; log in again");
                Logging.debug(e);
            }
            store(received);
            if (received == null) {
                MapRouletteClient.getInstance().fireAuthChanged();
            } else {
                Logging.info("Refreshed the MapRoulette login at " + this.root);
            }
            return received;
        }
    }

    private OAuthTokens postToken(Map<String, String> body) throws IOException {
        final var client = tokenEndpointClient("/oauth/mobile/token", body);
        try {
            final var response = client.connect();
            final var content = response.fetchContent();
            if (response.getResponseCode() != 200) {
                // The error body is an OAuth error code, never a token
                throw new IOException("MapRoulette token endpoint: HTTP " + response.getResponseCode() + ' '
                        + Objects.requireNonNullElse(content, ""));
            }
            return OAuthTokens.fromTokenResponse(content, this.clock.instant());
        } finally {
            client.disconnect();
        }
    }

    private HttpClient tokenEndpointClient(String path, Map<String, String> body) throws IOException {
        final URL url = URI.create(this.root + path).toURL();
        // Tokens go in the form body, never in the URL or a header that JOSM might log
        return HttpClient.create(url, "POST").setMaxRedirects(-1).setAccept("application/json")
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .setRequestBody(form(body).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Keep the new tokens, in memory and in the credential store. Call with {@link #tokenLock} held.
     *
     * @param newTokens The tokens, or {@code null} to forget the login
     */
    private void store(@Nullable OAuthTokens newTokens) {
        this.tokens = newTokens;
        try {
            CredentialsManager.getInstance().store(Authenticator.RequestorType.SERVER, credentialsHost(this.host),
                    new PasswordAuthentication(CLIENT_ID,
                            newTokens == null ? new char[0] : newTokens.toStorage().toCharArray()));
        } catch (CredentialsAgentException e) {
            // The login still works until JOSM closes
            Logging.warn("Could not store the MapRoulette login");
            Logging.debug(e);
        }
    }

    @Nullable
    private static OAuthTokens load(String host) {
        try {
            final var stored = CredentialsManager.getInstance().lookup(Authenticator.RequestorType.SERVER,
                    credentialsHost(host));
            return stored == null || stored.getPassword() == null ? null
                    : OAuthTokens.fromStorage(String.valueOf(stored.getPassword()));
        } catch (CredentialsAgentException e) {
            Logging.warn("Could not read the MapRoulette login");
            Logging.debug(e);
            return null;
        }
    }

    /**
     * The key for the credential store. It must not be a plain host name, so it never mixes with other credentials
     * JOSM keeps for that host.
     *
     * @param host The server host
     * @return The key
     */
    static String credentialsHost(String host) {
        return "maproulette:" + host;
    }

    /**
     * Check if a request may carry the token: https to the server host, or http to the local machine (for tests and
     * local servers)
     *
     * @param url  The request URL
     * @param host The server host
     * @return {@code true} if the token may be sent
     */
    static boolean mayCarryToken(URL url, String host) {
        if (!url.getHost().equalsIgnoreCase(host)) {
            return false;
        }
        return "https".equalsIgnoreCase(url.getProtocol())
                || "http".equalsIgnoreCase(url.getProtocol()) && isLoopback(url.getHost());
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    private static String host(String root) {
        final var host = URI.create(root).getHost();
        if (host == null) {
            throw new JosmRuntimeException("No host in " + root);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * Get a random value for the PKCE verifier and the state
     *
     * @return 43 characters of base64url
     */
    static String randomToken() {
        final var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Get the PKCE S256 challenge for a verifier
     *
     * @param verifier The verifier
     * @return The challenge
     */
    static String challenge(String verifier) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime has SHA-256
            throw new JosmRuntimeException(e);
        }
    }

    private static String form(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + '=' + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
}
