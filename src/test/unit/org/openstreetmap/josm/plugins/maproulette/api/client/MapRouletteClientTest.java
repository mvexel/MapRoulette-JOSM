// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.parsers.ProjectParser;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.tools.HttpClient;

/**
 * Test class for {@link MapRouletteClient}
 */
@BasicPreferences
@MapRouletteConfig
class MapRouletteClientTest {
    private static final String PATH = "/api/v2/project/1";

    @AfterEach
    void tearDown() {
        MapRouletteClient.getInstance().setAuthProvider(AuthProvider.NONE);
    }

    @Test
    void testQueryIsEncoded() {
        final var parameters = new TreeMap<String, String>();
        parameters.put("comment", "Fixed a & b #1 = ok?");
        parameters.put("tags", "über,road");
        assertEquals("?comment=Fixed+a+%26+b+%231+%3D+ok%3F&tags=%C3%BCber%2Croad",
                MapRouletteClient.query(parameters));
    }

    @Test
    void testEmptyQuery() {
        assertEquals("?", MapRouletteClient.query(Map.of()));
    }

    @Test
    @MapRouletteLogin
    void testUpdateStatusSendsCommentIntact() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(put(urlPathEqualTo("/api/v2/task/1/1")).willReturn(aResponse().withStatus(204)));
        assertTrue(TaskAPI.updateStatus(1, TaskStatus.FIXED, "Fixed a & b #1", "road", null, null));
        wireMock.verifyThat(putRequestedFor(urlPathEqualTo("/api/v2/task/1/1"))
                .withQueryParam("comment", equalTo("Fixed a & b #1")).withQueryParam("tags", equalTo("road")));
    }

    /**
     * MapRoulette no longer accepts API keys, so none may be sent
     */
    @Test
    @MapRouletteLogin
    void testNoApiKeyIsSent() throws Exception {
        final var wireMock = WireMocks.current();
        wireMock.register(put(urlPathEqualTo("/api/v2/task/1/1")).willReturn(aResponse().withStatus(204)));
        TaskAPI.updateStatus(1, TaskStatus.FIXED, null, null, null, null);
        wireMock.verifyThat(putRequestedFor(urlPathEqualTo("/api/v2/task/1/1")).withoutHeader("apiKey"));
    }

    /**
     * Without a login, write calls fail before anything is sent
     */
    @Test
    void testWriteWithoutLoginIsNotSent() {
        final var wireMock = WireMocks.current();
        final int requestsBefore = wireMock.find(anyRequestedFor(anyUrl())).size();
        assertThrows(MapRouletteAuthUnavailableException.class,
                () -> TaskAPI.updateStatus(1, TaskStatus.FIXED, null, null, null, null));
        assertThrows(MapRouletteAuthUnavailableException.class, () -> TaskAPI.start(1));
        wireMock.verifyThat(exactly(requestsBefore), anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest
    @ValueSource(ints = { 401, 410 })
    void testAuthUnavailable(int status) {
        WireMocks.current().register(get(urlPathEqualTo(PATH)).willReturn(
                aResponse().withStatus(status).withBody("{\"status\":\"NotAuthorized\",\"message\":\"no\"}")));
        assertThrows(MapRouletteAuthUnavailableException.class,
                () -> MapRouletteClient.getInstance().get("/project/1", Collections.emptyMap(), ProjectParser::parse));
    }

    @Test
    void testNotFound() {
        WireMocks.current().register(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));
        assertThrows(MapRouletteNotFoundException.class,
                () -> MapRouletteClient.getInstance().get("/project/1", Collections.emptyMap(), ProjectParser::parse));
    }

    @Test
    void testLockHeld() {
        WireMocks.current().register(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(409)
                .withBody("{\"status\":\"Conflict\",\"message\":\"locked\",\"lockedTaskId\":42,\"bundledTasks\":[]}")));
        final var exception = assertThrows(MapRouletteLockHeldException.class,
                () -> MapRouletteClient.getInstance().get("/project/1", Collections.emptyMap(), ProjectParser::parse));
        assertEquals(OptionalLong.of(42), exception.heldTaskId());
    }

    @Test
    void testLockHeldWithoutBody() {
        WireMocks.current().register(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(409)));
        final var exception = assertThrows(MapRouletteLockHeldException.class,
                () -> MapRouletteClient.getInstance().get("/project/1", Collections.emptyMap(), ProjectParser::parse));
        assertEquals(OptionalLong.empty(), exception.heldTaskId());
    }

    @Test
    void testServerError() {
        WireMocks.current().register(
                get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(502).withBody("<html>Bad gateway</html>")));
        final var exception = assertThrows(MapRouletteHttpException.class,
                () -> MapRouletteClient.getInstance().get("/project/1", Collections.emptyMap(), ProjectParser::parse));
        assertEquals(502, exception.status());
        assertEquals("<html>Bad gateway</html>", exception.bodyExcerpt());
    }

    @Test
    void testMalformedBody() {
        WireMocks.current()
                .register(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("{\"id\":")));
        assertThrows(MapRouletteParseException.class,
                () -> MapRouletteClient.getInstance().get("/project/1", Collections.emptyMap(), ProjectParser::parse));
    }

    /**
     * After a 401, a provider that refreshed its credentials gets exactly one retry
     */
    @Test
    void testUnauthorizedIsRetriedOnceAfterRefresh() {
        final var refreshes = new AtomicInteger();
        MapRouletteClient.getInstance().setAuthProvider(new AuthProvider() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Set<Capability> capabilities() {
                return EnumSet.allOf(Capability.class);
            }

            @Override
            public void authenticate(HttpClient client) {
                client.setHeader("Authorization", "Bearer " + refreshes.get());
            }

            @Override
            public boolean onUnauthorized() {
                refreshes.incrementAndGet();
                return true;
            }
        });
        final var wireMock = WireMocks.current();
        wireMock.register(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));
        final var exception = assertThrows(MapRouletteException.class,
                () -> MapRouletteClient.getInstance().get("/project/1", Collections.emptyMap(), body -> body));
        assertInstanceOf(MapRouletteAuthUnavailableException.class, exception);
        assertEquals(1, refreshes.get());
        wireMock.verifyThat(exactly(1),
                getRequestedFor(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer 0")));
        wireMock.verifyThat(exactly(1),
                getRequestedFor(urlPathEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer 1")));
    }
}
