// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.tools.HttpClient;

import com.github.tomakehurst.wiremock.http.Fault;

import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Error mapping of {@link MapRouletteClient} (epic #3, issue #35), against the contract in
 * architecture/client-contract.md. Bodies come from {@code src/test/resources/__files/errors}, see its README.
 */
@BasicPreferences
@MapRouletteConfig
class MapRouletteClientErrorsTest {
    // Not /task/<id>: the shared MapRoulette WireMock extension answers GET /task/<id> from recorded files
    private static final String TASK = "/challenge/4242";
    private static final String TASK_URL = "/api/v2" + TASK;
    private static final String START = "/task/12345/start";
    private static final ResponseParser<JsonObject> JSON = reader -> Json.createReader(reader).readObject();

    private FakeAuthProvider auth;
    private AuthProvider originalAuth;

    @BeforeEach
    void setUp() {
        this.originalAuth = MapRouletteClient.getInstance().auth();
        this.auth = new FakeAuthProvider();
        MapRouletteClient.getInstance().setAuthProvider(this.auth);
    }

    @AfterEach
    void tearDown() {
        MapRouletteClient.getInstance().setAuthProvider(this.originalAuth);
    }

    private static MapRouletteClient client() {
        return MapRouletteClient.getInstance();
    }

    private static void stubGet(int status, String bodyFile) {
        WireMocks.current().register(get(urlPathEqualTo(TASK_URL)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBodyFile("errors/" + bodyFile)));
    }

    private static void stubPut(int status, String bodyFile) {
        WireMocks.current().register(put(urlPathEqualTo("/api/v2" + START)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBodyFile("errors/" + bodyFile)));
    }

    @Test
    void testAllErrorsAreCheckedIOExceptions() {
        assertAll(() -> assertTrue(IOException.class.isAssignableFrom(MapRouletteException.class)),
                () -> assertTrue(
                        MapRouletteException.class.isAssignableFrom(MapRouletteAuthUnavailableException.class)),
                () -> assertTrue(MapRouletteException.class.isAssignableFrom(MapRouletteLockHeldException.class)),
                () -> assertTrue(MapRouletteException.class.isAssignableFrom(MapRouletteNotFoundException.class)),
                () -> assertTrue(MapRouletteException.class.isAssignableFrom(MapRouletteForbiddenException.class)),
                () -> assertTrue(MapRouletteException.class.isAssignableFrom(MapRouletteHttpException.class)),
                () -> assertTrue(MapRouletteException.class.isAssignableFrom(MapRouletteParseException.class)));
    }

    @Test
    void testUnauthorized() {
        stubGet(401, "401-not-authorized.json");
        assertThrows(MapRouletteAuthUnavailableException.class, () -> client().get(TASK, Collections.emptyMap(), JSON));
    }

    @Test
    void testUnauthorizedRetriesOnceAfterRefresh() throws MapRouletteException {
        this.auth.refreshSucceeds = true;
        WireMocks.current()
                .register(get(urlPathEqualTo(TASK_URL)).inScenario("refresh")
                        .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                        .willReturn(aResponse().withStatus(401).withBodyFile("errors/401-not-authorized.json"))
                        .willSetStateTo("refreshed"));
        WireMocks.current().register(get(urlPathEqualTo(TASK_URL)).inScenario("refresh")
                .whenScenarioStateIs("refreshed").willReturn(aResponse().withStatus(200).withBody("{\"id\":12345}")));

        final var task = client().get(TASK, Collections.emptyMap(), JSON);

        assertEquals(12345, task.getInt("id"));
        assertEquals(1, this.auth.unauthorizedCalls.get());
        WireMocks.current().verifyThat(2, getRequestedFor(urlPathEqualTo(TASK_URL)));
    }

    @Test
    void testUnauthorizedAfterRefreshDoesNotLoop() {
        this.auth.refreshSucceeds = true;
        stubGet(401, "401-not-authorized.json");
        assertThrows(MapRouletteAuthUnavailableException.class, () -> client().get(TASK, Collections.emptyMap(), JSON));
        // One original request and one retry, never more
        WireMocks.current().verifyThat(2, getRequestedFor(urlPathEqualTo(TASK_URL)));
    }

    @Test
    void testGone() {
        stubGet(410, "410-api-keys-disabled.json");
        assertThrows(MapRouletteAuthUnavailableException.class, () -> client().get(TASK, Collections.emptyMap(), JSON));
    }

    /**
     * Write operations call {@code requireAuth()} before sending (contract: a write can't be told apart by method)
     */
    @Test
    void testRequireAuthWithoutAuth() {
        this.auth.available = false;
        assertThrows(MapRouletteAuthUnavailableException.class, () -> client().requireAuth());
        WireMocks.current().verifyThat(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void testRequireAuthWithAuth() {
        assertDoesNotThrow(() -> client().requireAuth());
    }

    /**
     * Requests without a body, including DELETE, must not hit the JOSM HttpClient null body NPE
     */
    @Test
    void testDelete() {
        WireMocks.current().register(delete(urlPathEqualTo("/api/v2/task/12345")).willReturn(aResponse()));
        assertDoesNotThrow(() -> client().delete("/task/12345"));
        WireMocks.current().verifyThat(1, deleteRequestedFor(urlPathEqualTo("/api/v2/task/12345")));
    }

    @Test
    void testReadWithoutAuthIsSent() throws MapRouletteException {
        this.auth.available = false;
        WireMocks.current().register(get(urlPathEqualTo(TASK_URL)).willReturn(aResponse().withBody("{\"id\":1}")));
        assertEquals(1, client().get(TASK, Collections.emptyMap(), JSON).getInt("id"));
    }

    @Test
    void testLockHeld() {
        stubPut(409, "409-lock-held.json");
        final var exception = assertThrows(MapRouletteLockHeldException.class,
                () -> client().put(START, Collections.emptyMap(), null, JSON));
        assertTrue(exception.heldTaskId().isPresent());
        assertEquals(777, exception.heldTaskId().getAsLong());
        assertArrayEquals(new long[] { 778, 779 }, exception.bundledTaskIds());
    }

    @Test
    void testConflictWithoutLockedTaskId() {
        stubPut(409, "409-change-conflict.json");
        final var exception = assertThrows(MapRouletteLockHeldException.class,
                () -> client().put(START, Collections.emptyMap(), null, JSON));
        assertFalse(exception.heldTaskId().isPresent());
        assertEquals(0, exception.bundledTaskIds().length);
    }

    @Test
    void testNotFound() {
        stubGet(404, "404-not-found.json");
        assertThrows(MapRouletteNotFoundException.class, () -> client().get(TASK, Collections.emptyMap(), JSON));
    }

    /**
     * 403 is what Start gets when another user holds the task lock
     */
    @Test
    void testForbidden() {
        stubPut(403, "403-locked-by-other.json");
        final var exception = assertThrows(MapRouletteForbiddenException.class,
                () -> client().put(START, Collections.emptyMap(), null, JSON));
        assertTrue(exception.getMessage().contains("locked by another user"), exception.getMessage());
    }

    @Test
    void testServerError() {
        stubGet(500, "500-internal.json");
        final var exception = assertThrows(MapRouletteHttpException.class,
                () -> client().get(TASK, Collections.emptyMap(), JSON));
        assertEquals(500, exception.status());
        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("internal error"), exception.getMessage());
    }

    @Test
    void testServerErrorWithHtmlBody() {
        WireMocks.current().register(get(urlPathEqualTo(TASK_URL))
                .willReturn(aResponse().withStatus(502).withBody("<html><body>Bad gateway</body></html>")));
        final var exception = assertThrows(MapRouletteHttpException.class,
                () -> client().get(TASK, Collections.emptyMap(), JSON));
        assertEquals(502, exception.status());
    }

    /**
     * A 200 whose body the parser can't read. Jakarta JSON throws unchecked JsonException, which must still
     * surface as a parse error rather than escape as a RuntimeException.
     *
     * @param body The response body
     */
    @ParameterizedTest
    @ValueSource(strings = { "<html>maintenance</html>", "{\"id\": 1", "", "[1, 2]" })
    void testMalformedBody(String body) {
        WireMocks.current().register(get(urlPathEqualTo(TASK_URL)).willReturn(aResponse().withBody(body)));
        final var exception = assertThrows(MapRouletteParseException.class,
                () -> client().get(TASK, Collections.emptyMap(), JSON));
        assertNotNull(exception.getCause());
    }

    /**
     * An IOException from the parser is a read failure, not a parse failure
     */
    @Test
    void testParserIOException() {
        WireMocks.current().register(get(urlPathEqualTo(TASK_URL)).willReturn(aResponse().withBody("{}")));
        final ResponseParser<Object> failing = reader -> {
            throw new IOException("unreadable");
        };
        final var exception = assertThrows(MapRouletteException.class,
                () -> client().get(TASK, Collections.emptyMap(), failing));
        assertFalse(exception instanceof MapRouletteParseException, "A read failure is not a parse failure");
        assertInstanceOf(IOException.class, exception.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = { "CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE" })
    void testConnectionFailure(String fault) {
        WireMocks.current()
                .register(get(urlPathEqualTo(TASK_URL)).willReturn(aResponse().withFault(Fault.valueOf(fault))));
        final var exception = assertThrows(MapRouletteException.class,
                () -> client().get(TASK, Collections.emptyMap(), JSON));
        assertInstanceOf(IOException.class, exception.getCause() != null ? exception.getCause() : exception);
    }

    @Test
    void testRequestsAreAuthenticated() throws MapRouletteException {
        WireMocks.current().register(get(urlPathEqualTo(TASK_URL)).willReturn(aResponse().withBody("{}")));
        client().get(TASK, Collections.emptyMap(), JSON);
        assertEquals(1, this.auth.authenticateCalls.get());
    }

    /**
     * An {@link AuthProvider} the tests control
     */
    static final class FakeAuthProvider implements AuthProvider {
        boolean available = true;
        boolean refreshSucceeds;
        final AtomicInteger authenticateCalls = new AtomicInteger();
        final AtomicInteger unauthorizedCalls = new AtomicInteger();

        @Override
        public boolean isAvailable() {
            return this.available;
        }

        @Override
        public Set<Capability> capabilities() {
            return this.available ? EnumSet.allOf(Capability.class) : EnumSet.noneOf(Capability.class);
        }

        @Override
        public void authenticate(HttpClient request) {
            this.authenticateCalls.incrementAndGet();
        }

        @Override
        public boolean onUnauthorized() {
            this.unauthorizedCalls.incrementAndGet();
            return this.refreshSucceeds;
        }
    }
}
