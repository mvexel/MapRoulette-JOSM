// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openstreetmap.josm.data.oauth.OAuthAccessTokenHolder;
import org.openstreetmap.josm.data.oauth.OAuthVersion;
import org.openstreetmap.josm.gui.oauth.OAuthAuthorizationWizard;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.OsmApi;
import org.openstreetmap.josm.tools.Logging;

/**
 * JOSM's own OpenStreetMap login, offered before the first upload so it doesn't come as a surprise in the middle of
 * it (#118). The login itself stays JOSM's.
 */
public final class OsmLogin {
    /**
     * Whether JOSM's login is open, so a second click doesn't open a second one
     */
    private static final AtomicBoolean LOGGING_IN = new AtomicBoolean();

    private OsmLogin() {
        // Hide constructor
    }

    /**
     * Check whether an upload will ask for the OpenStreetMap login: JOSM uses OAuth 2 and has no token for the OSM
     * server. With basic authentication JOSM asks for the password as before; that is not counted.
     *
     * @return {@code true} if JOSM has no OpenStreetMap login yet
     */
    public static boolean needed() {
        if (!"oauth20".equals(OsmApi.getAuthMethod())) {
            return false;
        }
        return OAuthAccessTokenHolder.getInstance().getAccessToken(OsmApi.getOsmApi().getServerUrl(),
                OAuthVersion.OAuth20) == null;
    }

    /**
     * Open JOSM's OpenStreetMap login, unless it is open already. It waits for the browser on its own thread; once it
     * ends, {@code done} runs on the EDT.
     *
     * @param done What to do once the login ended, logged in or not
     * @return {@code false} if the login was open already
     */
    public static boolean logIn(Runnable done) {
        if (LOGGING_IN.getAndSet(true)) {
            return false;
        }
        final var thread = new Thread(() -> {
            try {
                OAuthAuthorizationWizard.obtainAccessToken(URI.create(OsmApi.getOsmApi().getServerUrl()).toURL());
            } catch (InvocationTargetException | MalformedURLException | IllegalArgumentException e) {
                Logging.warn(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                LOGGING_IN.set(false);
                GuiHelper.runInEDT(done);
            }
        }, "OpenStreetMap login");
        thread.setDaemon(true);
        thread.start();
        return true;
    }
}
