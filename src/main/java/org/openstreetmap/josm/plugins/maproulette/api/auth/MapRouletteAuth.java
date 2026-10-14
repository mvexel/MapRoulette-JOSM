// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GraphicsEnvironment;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

import jakarta.annotation.Nonnull;

/**
 * The MapRoulette login used by the plugin
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
    /**
     * The current provider
     */
    private static volatile AuthProvider provider = AuthProvider.NONE;

    private MapRouletteAuth() {
        // Hide constructor
    }

    /**
     * Get the current provider
     *
     * @return The provider
     */
    @Nonnull
    public static AuthProvider getProvider() {
        return provider;
    }

    /**
     * Set the provider
     *
     * @param newProvider The provider to use
     */
    public static void setProvider(@Nonnull AuthProvider newProvider) {
        provider = Objects.requireNonNull(newProvider, "newProvider");
    }

    /**
     * Check if the user can make requests that need a MapRoulette login
     *
     * @return {@code true} if start, stop and task status are available
     */
    public static boolean isAvailable() {
        return provider.isAvailable();
    }

    /**
     * The message shown when a MapRoulette login is needed but not available
     *
     * @return The message
     */
    public static String unavailableMessage() {
        return tr("MapRoulette does not accept logins from JOSM at the moment. "
                + "You can browse and download tasks, but set task status on the MapRoulette website.");
    }

    /**
     * The tooltip for actions that need a MapRoulette login
     *
     * @return The tooltip
     */
    public static String unavailableTooltip() {
        return tr("Not available while MapRoulette does not accept logins from JOSM. "
                + "Set task status on the MapRoulette website.");
    }

    /**
     * Tell the user, once per session, that the MapRoulette login is unavailable
     */
    public static void showUnavailableNoticeOnce() {
        if (isAvailable() || UNAVAILABLE_NOTICE_SHOWN.getAndSet(true)) {
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            Logging.info(unavailableMessage());
        } else {
            GuiHelper.runInEDT(() -> new Notification(unavailableMessage()).setIcon(JOptionPane.INFORMATION_MESSAGE)
                    .setDuration(Notification.TIME_VERY_LONG).show());
        }
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
