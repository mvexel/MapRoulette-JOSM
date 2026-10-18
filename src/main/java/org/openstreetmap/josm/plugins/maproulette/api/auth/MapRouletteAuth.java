// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * What the plugin tells the user about the MapRoulette login, and clean-up of the old API key
 */
public final class MapRouletteAuth {
    /**
     * The prefix of the preferences that cached the (now unused) MapRoulette API key
     */
    private static final String API_KEY_PREFERENCE_PREFIX = "maproulette.openstreetmap.";
    /**
     * Whether the user has been told that login is unavailable in this session
     */
    private static final AtomicBoolean UNAVAILABLE_NOTICE_SHOWN = new AtomicBoolean();

    private MapRouletteAuth() {
        // Hide constructor
    }

    /**
     * Check if the user can make requests that need a MapRoulette login
     *
     * @return {@code true} if start, stop and task status are available
     */
    public static boolean isAvailable() {
        return MapRouletteClient.getInstance().auth().isAvailable();
    }

    /**
     * Look up the MapRoulette id of the logged-in user, so tasks they locked are told apart from other mappers' locks.
     * Call it off the EDT. A failure is logged: the locks then look like other mappers' until the next try.
     */
    public static void lookUpUser() {
        try {
            MapRouletteClient.getInstance().auth().userId();
        } catch (MapRouletteException e) {
            Logging.warn("Could not look up the MapRoulette user: " + e.getMessage());
            Logging.debug(e);
        }
    }

    /**
     * The message shown when a MapRoulette login is needed but not available
     *
     * @return The message
     */
    public static String unavailableMessage() {
        if (MapRouletteClient.getInstance().auth() instanceof OAuthAuthProvider) {
            return tr("Log in to MapRoulette to start tasks and set their status. The login opens in your browser.");
        }
        return tr("MapRoulette does not accept logins from JOSM at the moment. "
                + "You can browse and download tasks, but set task status on the MapRoulette website.");
    }

    /**
     * The tooltip for actions that need a MapRoulette login
     *
     * @return The tooltip
     */
    public static String unavailableTooltip() {
        if (MapRouletteClient.getInstance().auth() instanceof OAuthAuthProvider) {
            return tr("Log in to MapRoulette first. Use Log in… in the MapRoulette panel.");
        }
        return tr("Not available while MapRoulette does not accept logins from JOSM. "
                + "Set task status on the MapRoulette website.");
    }

    /**
     * Use the login for the configured MapRoulette server: OAuth if the server accepts logins from JOSM, otherwise
     * none. Keeps the current login if the server did not change.
     */
    public static void configure() {
        final var client = MapRouletteClient.getInstance();
        final var apiUrl = MapRouletteConfig.getBaseUrl();
        if (client.auth()instanceof OAuthAuthProvider current
                && current.server().equals(OAuthAuthProvider.serverRoot(apiUrl))) {
            return;
        }
        if (client.auth()instanceof OAuthAuthProvider previous) {
            previous.cancelLogIn();
        }
        final var provider = OAuthAuthProvider.forServer(apiUrl);
        client.setAuthProvider(provider == null ? AuthProvider.NONE : provider);
        if (provider != null) {
            Workers.execute(provider::loadStored);
        }
    }

    /**
     * Check, once per session, whether to tell the user that the MapRoulette login is unavailable
     *
     * @return {@code true} if the user is not logged in and has not been told yet in this session
     */
    public static boolean tellUnavailableOnce() {
        return !isAvailable() && !UNAVAILABLE_NOTICE_SHOWN.getAndSet(true);
    }

    /**
     * Let the login notice show again, for tests
     */
    static void resetUnavailableNotice() {
        UNAVAILABLE_NOTICE_SHOWN.set(false);
    }

    /**
     * Delete the MapRoulette API keys that older versions cached in plain text in the JOSM preferences.
     * MapRoulette no longer accepts API keys.
     */
    public static void deleteCachedApiKeys() {
        final Collection<String> keys = Config.getPref().getKeySet().stream()
                .filter(key -> key.startsWith(API_KEY_PREFERENCE_PREFIX)).toList();
        // Removes the setting whatever its type
        keys.forEach(key -> Config.getPref().put(key, null));
    }
}
