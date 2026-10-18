// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;

/**
 * Test class for {@link UserAPI}
 */
@MapRouletteConfig
class UserAPITest {
    /**
     * The public profile of a challenge owner is read without a login (#120). The fixture is the dev test account.
     *
     * @throws Exception if the test fails
     */
    @Test
    void testPublicUser() throws Exception {
        final var user = UserAPI.publicUser(11353);
        assertEquals(2, user.id());
        assertEquals("mvexelmrtest", user.name());
        assertEquals(11353, user.osmProfile().id());
        assertEquals("mvexelmrtest", user.osmProfile().displayName());
        WireMocks.current().verifyThat(1, getRequestedFor(urlPathEqualTo("/api/v2/user/11353/public"))
                .withHeader("Authorization", absent()));
    }
}
