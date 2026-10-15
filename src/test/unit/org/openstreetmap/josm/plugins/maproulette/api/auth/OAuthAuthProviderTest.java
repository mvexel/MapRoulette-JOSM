// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Authenticator;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.io.auth.CredentialsManager;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.LoggingHandler;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;

/**
 * Test class for {@link OAuthAuthProvider}, against a mocked MapRoulette token endpoint
 */
@BasicPreferences
@org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig
class OAuthAuthProviderTest {
    private static final String TOKEN = "/oauth/mobile/token";
    private static final String REVOKE = "/oauth/mobile/revoke";
    private static final String ACCESS_1 = "a".repeat(43);
    private static final String REFRESH_1 = "r".repeat(43);
    private static final String ACCESS_2 = "b".repeat(43);
    private static final String REFRESH_2 = "s".repeat(43);

    private WireMock wireMock;
    private String root;
    private MutableClock clock;
    private FakeBrowser browser;

    @BeforeEach
    void setUp() {
        this.wireMock = WireMocks.current();
        this.root = OAuthAuthProvider.serverRoot(MapRouletteConfig.getBaseUrl());
        OAuthAuthProvider.LOGIN_SERVERS.put(Collections.singletonList(this.root));
        this.clock = new MutableClock(Instant.parse("2026-10-15T12:00:00Z"));
        this.browser = new FakeBrowser();
    }

    @AfterEach
    void tearDown() {
        MapRouletteClient.getInstance().setAuthProvider(AuthProvider.NONE);
        // Forget the login, which JOSM's credential store would otherwise keep for the next test
        provider().logOut();
    }

    private OAuthAuthProvider provider() {
        final var provider = new OAuthAuthProvider(this.root, this.browser, this.clock);
        provider.loadStored();
        return provider;
    }

    private static String tokenResponse(String access, String refresh) {
        return "{\"access_token\":\"" + access + "\",\"token_type\":\"Bearer\",\"expires_in\":900,"
                + "\"refresh_token\":\"" + refresh + "\",\"scope\":\"tasks:read tasks:write tasks:edit\"}";
    }

    private void stubToken(String grantType, String access, String refresh) {
        this.wireMock.register(post(urlPathEqualTo(TOKEN)).withRequestBody(containing("grant_type=" + grantType))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(tokenResponse(access, refresh))));
    }

    private OAuthAuthProvider loggedIn() throws Exception {
        stubToken("authorization_code", ACCESS_1, REFRESH_1);
        final var provider = provider();
        provider.logIn();
        this.wireMock.resetRequests();
        return provider;
    }

    private HttpClient request() throws Exception {
        return HttpClient.create(URI.create(MapRouletteConfig.getBaseUrl() + "/task/1/1").toURL(), "PUT");
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&", -1)).map(p -> p.split("=", 2))
                .collect(Collectors.toMap(p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                        p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
    }

    private static Map<String, String> query(URI uri) {
        return form(uri.getRawQuery());
    }

    /**
     * Login is only offered for servers that accept it, so maproulette.org shows no login until it supports it
     */
    @Test
    void testOnlyListedServersOfferLogin() {
        OAuthAuthProvider.LOGIN_SERVERS.remove();
        assertNull(OAuthAuthProvider.forServer("https://maproulette.org/api/v2"));
        assertNotNull(OAuthAuthProvider.forServer("https://mr-api.osm.lol/api/v2"));
        assertNotNull(OAuthAuthProvider.forServer("https://mr-api.osm.lol/api/v2/"));
        OAuthAuthProvider.LOGIN_SERVERS.put(Collections.singletonList("http://mr-api.example"));
        assertNull(OAuthAuthProvider.forServer("http://mr-api.example/api/v2"), "No tokens over plain http");
        assertNull(OAuthAuthProvider.serverRoot("https://mr-api.osm.lol/"), "Not an API URL");
    }

    @Test
    void testLogInUsesPkce() throws Exception {
        stubToken("authorization_code", ACCESS_1, REFRESH_1);
        final var provider = provider();
        assertFalse(provider.isAvailable());
        provider.logIn();

        final var authorize = query(this.browser.uri.get());
        assertAll(
                () -> assertEquals(this.root + "/oauth/mobile/authorize",
                        this.browser.uri.get().toString().substring(0, this.browser.uri.get().toString().indexOf('?'))),
                () -> assertEquals("code", authorize.get("response_type")),
                () -> assertEquals("maproulette-josm", authorize.get("client_id")),
                () -> assertEquals("http://127.0.0.1:8111/oauth_authorization", authorize.get("redirect_uri")),
                () -> assertEquals("tasks:read tasks:write tasks:edit", authorize.get("scope")),
                () -> assertEquals("S256", authorize.get("code_challenge_method")),
                () -> assertEquals(43, authorize.get("state").length()));

        final var exchange = this.wireMock.find(postRequestedFor(urlPathEqualTo(TOKEN)));
        assertEquals(1, exchange.size());
        assertNull(exchange.get(0).getHeader("Authorization"), "A public client sends no credentials");
        final var body = form(exchange.get(0).getBodyAsString());
        assertAll(() -> assertEquals("authorization_code", body.get("grant_type")),
                () -> assertEquals(FakeBrowser.CODE, body.get("code")),
                () -> assertEquals("http://127.0.0.1:8111/oauth_authorization", body.get("redirect_uri")),
                () -> assertEquals("maproulette-josm", body.get("client_id")),
                () -> assertTrue(body.get("code_verifier").matches("[A-Za-z0-9_-]{43}")),
                () -> assertEquals(authorize.get("code_challenge"),
                        OAuthAuthProvider.challenge(body.get("code_verifier"))));

        assertTrue(provider.isAvailable());
        assertEquals(
                EnumSet.of(AuthProvider.Capability.LOCK, AuthProvider.Capability.SET_STATUS,
                        AuthProvider.Capability.COMMENT, AuthProvider.Capability.REFRESH_LOCK),
                provider.capabilities());
        assertTrue(provider().isAvailable(), "The login is kept for the next JOSM session");
    }

    @Test
    void testRejectedLogInKeepsLoggedOut() {
        this.wireMock.register(post(urlPathEqualTo(TOKEN))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid_grant\"}")));
        final var provider = provider();
        assertThrows(Exception.class, provider::logIn);
        assertFalse(provider.isAvailable());
    }

    @Test
    void testBearerOnlyToTheServer() throws Exception {
        final var provider = loggedIn();
        final var toServer = request();
        provider.authenticate(toServer, true);
        assertEquals("Bearer " + ACCESS_1, toServer.getRequestHeader("Authorization"));

        final var elsewhere = HttpClient.create(URI.create("https://example.com/api/v2/task/1/1").toURL(), "PUT");
        provider.authenticate(elsewhere, true);
        assertNull(elsewhere.getRequestHeader("Authorization"));
    }

    /**
     * Reads go without the token, so it only travels with requests that need it
     */
    @Test
    void testReadsCarryNoToken() throws Exception {
        MapRouletteClient.getInstance().setAuthProvider(loggedIn());
        this.wireMock.register(put(urlPathEqualTo("/api/v2/task/1/1")).willReturn(aResponse().withStatus(204)));
        TaskAPI.updateStatus(1, TaskStatus.FIXED, null, null, null, null);
        this.wireMock.verifyThat(putRequestedFor(urlPathEqualTo("/api/v2/task/1/1")).withHeader("Authorization",
                equalTo("Bearer " + ACCESS_1)));
        final var read = new AtomicReference<String>();
        this.wireMock.register(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/api/v2/project/1"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        MapRouletteClient.getInstance().get("/project/1", Map.of(), reader -> {
            read.set("read");
            return null;
        });
        assertEquals("read", read.get());
        assertNull(
                this.wireMock
                        .find(com.github.tomakehurst.wiremock.client.WireMock
                                .getRequestedFor(urlPathEqualTo("/api/v2/project/1")))
                        .get(0).getHeader("Authorization"));
    }

    @Test
    void testRefreshBeforeExpiry() throws Exception {
        final var provider = loggedIn();
        stubToken("refresh_token", ACCESS_2, REFRESH_2);
        this.clock.now = this.clock.now.plusSeconds(900 - 30);
        final var client = request();
        provider.authenticate(client, true);
        assertEquals("Bearer " + ACCESS_2, client.getRequestHeader("Authorization"));
        final var body = form(this.wireMock.find(postRequestedFor(urlPathEqualTo(TOKEN))).get(0).getBodyAsString());
        assertEquals(Map.of("grant_type", "refresh_token", "refresh_token", REFRESH_1, "client_id", "maproulette-josm"),
                body);
        assertTrue(provider().isAvailable());
        final var stored = CredentialsManager.getInstance().lookup(Authenticator.RequestorType.SERVER,
                OAuthAuthProvider.credentialsHost(URI.create(this.root).getHost()));
        assertTrue(String.valueOf(stored.getPassword()).contains(REFRESH_2), "The new pair is stored");
    }

    /**
     * Several requests that get 401 at the same time refresh once: reusing the old refresh token would make
     * MapRoulette revoke the login
     */
    @Test
    void testConcurrentUnauthorizedRefreshesOnce() throws Exception {
        final var provider = loggedIn();
        stubToken("refresh_token", ACCESS_2, REFRESH_2);
        final var first = request();
        final var second = request();
        provider.authenticate(first, true);
        provider.authenticate(second, true);
        assertTrue(provider.onUnauthorized(first));
        assertTrue(provider.onUnauthorized(second));
        this.wireMock.verifyThat(exactly(1), postRequestedFor(urlPathEqualTo(TOKEN)));
        final var retry = request();
        provider.authenticate(retry, true);
        assertEquals("Bearer " + ACCESS_2, retry.getRequestHeader("Authorization"));
    }

    @Test
    void testFailedRefreshLogsOut() throws Exception {
        final var provider = loggedIn();
        this.wireMock.register(post(urlPathEqualTo(TOKEN))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid_grant\"}")));
        final var client = request();
        provider.authenticate(client, true);
        assertFalse(provider.onUnauthorized(client));
        assertFalse(provider.isAvailable());
        assertFalse(provider().isAvailable(), "The stored login is deleted");
        assertThrows(MapRouletteAuthUnavailableException.class, () -> provider.authenticate(request(), true));
    }

    @Test
    void testLogOutRevokes() throws Exception {
        final var provider = loggedIn();
        this.wireMock.register(post(urlPathEqualTo(REVOKE)).willReturn(aResponse().withStatus(200)));
        provider.logOut();
        assertFalse(provider.isAvailable());
        assertFalse(provider().isAvailable());
        final var revoke = this.wireMock.find(postRequestedFor(urlPathEqualTo(REVOKE)));
        assertEquals(1, revoke.size());
        assertEquals(Map.of("client_id", "maproulette-josm", "token", REFRESH_1),
                form(revoke.get(0).getBodyAsString()));
    }

    /**
     * No token gets into the log at debug level. (JOSM logs request bodies, with the refresh token, at trace level.)
     */
    @Test
    @LoggingHandler
    void testRefreshTokenNotLogged(LoggingHandler.TestHandler logs) throws Exception {
        final var level = Logging.getLogger().getLevel();
        Logging.getLogger().setLevel(Logging.LEVEL_DEBUG);
        try {
            final var provider = loggedIn();
            stubToken("refresh_token", ACCESS_2, REFRESH_2);
            this.clock.now = this.clock.now.plusSeconds(900);
            provider.authenticate(request(), true);
            assertFalse(logs.getRecords().isEmpty(), "Debug logging is on");
            assertAll(logs.getRecords().stream().map(r -> () -> {
                final var message = String.valueOf(r.getMessage()) + Arrays.toString(r.getParameters());
                assertFalse(Stream.of(ACCESS_1, ACCESS_2, REFRESH_1, REFRESH_2).anyMatch(message::contains), message);
            }));
        } finally {
            Logging.getLogger().setLevel(level);
        }
    }

    @Test
    void testTokensNotInToString() {
        final var tokens = OAuthTokens.fromTokenResponse(tokenResponse(ACCESS_1, REFRESH_1), Instant.EPOCH);
        assertFalse(tokens.toString().contains(ACCESS_1) || tokens.toString().contains(REFRESH_1));
        assertEquals(tokens, OAuthTokens.fromStorage(tokens.toStorage()));
    }

    /**
     * JOSM keeps the consumer of a login that timed out, so it must refuse a late answer
     */
    @Test
    void testCallbackAfterTimeoutRefused() {
        final var code = new java.util.concurrent.CompletableFuture<String>();
        code.cancel(false);
        final var consumer = new LoopbackAuthorization.CodeConsumer("our-state", code);
        assertThrows(Exception.class,
                () -> consumer.validateRequest("127.0.0.1", "", Map.of("state", "our-state", "code", "x")));
    }

    @Test
    @LoggingHandler
    void testLoginRefreshLogoutLoggedAtInfo(LoggingHandler.TestHandler logs) throws Exception {
        final var provider = loggedIn();
        stubToken("refresh_token", ACCESS_2, REFRESH_2);
        this.clock.now = this.clock.now.plusSeconds(900);
        provider.authenticate(request(), true);
        provider.logOut();
        final var info = logs.getRecords().stream().filter(r -> r.getLevel().equals(Logging.LEVEL_INFO))
                .map(r -> String.valueOf(r.getMessage())).toList();
        assertAll(
                () -> assertTrue(info.stream().anyMatch(m -> m.startsWith("Logged in to MapRoulette")), info::toString),
                () -> assertTrue(info.stream().anyMatch(m -> m.startsWith("Refreshed the MapRoulette login")),
                        info::toString),
                () -> assertTrue(info.stream().anyMatch(m -> m.startsWith("Logged out of MapRoulette")),
                        info::toString),
                () -> assertFalse(info.stream()
                        .anyMatch(m -> Stream.of(ACCESS_1, ACCESS_2, REFRESH_1, REFRESH_2).anyMatch(m::contains))));
    }

    @Test
    void testCallbackNeedsOurState() throws Exception {
        final var code = new java.util.concurrent.CompletableFuture<String>();
        final var consumer = new LoopbackAuthorization.CodeConsumer("our-state", code);
        assertThrows(Exception.class,
                () -> consumer.validateRequest("127.0.0.1", "", Map.of("state", "other", "code", "x")));
        assertThrows(Exception.class, () -> consumer.validateRequest("127.0.0.1", "", Map.of("state", "our-state")));
        consumer.validateRequest("127.0.0.1", "", Map.of("state", "our-state", "code", "x"));
        consumer.handleRequest("127.0.0.1", "", Map.of("state", "our-state", "code", "x"));
        assertEquals("x", code.getNow(null));
    }

    /**
     * Pretends the user logged in in the browser
     */
    private static final class FakeBrowser implements OAuthAuthProvider.Authorizer {
        static final String CODE = "the-code";
        final AtomicReference<URI> uri = new AtomicReference<>();

        @Override
        public String authorize(URI authorizeUri, String state) {
            this.uri.set(authorizeUri);
            assertEquals(state, query(authorizeUri).get("state"));
            return CODE;
        }

        @Override
        public void cancel() {
            // Nothing waits
        }
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }
    }
}
