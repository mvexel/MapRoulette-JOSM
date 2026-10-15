// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.auth;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.openstreetmap.josm.io.remotecontrol.RemoteControl;
import org.openstreetmap.josm.io.remotecontrol.handler.AuthorizationHandler;
import org.openstreetmap.josm.io.remotecontrol.handler.RequestHandler;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;
import org.openstreetmap.josm.tools.OpenBrowser;

/**
 * Get an authorization code in the user's browser. MapRoulette redirects the browser to JOSM's remote control,
 * which hands the code to us.
 */
final class LoopbackAuthorization implements OAuthAuthProvider.Authorizer {
    /**
     * Where MapRoulette sends the browser back to. The port is JOSM's fixed remote control port.
     */
    static final String REDIRECT_URI = "http://127.0.0.1:8111/" + AuthorizationHandler.command;
    /**
     * How long to wait for the user to log in
     */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    /**
     * The login the user is doing right now, so it can be cancelled. One for all servers: logins share remote
     * control, which the first one would otherwise stop under the second.
     */
    private static CompletableFuture<String> pending;

    @Override
    public String authorize(URI authorizeUri, String state) throws MapRouletteException, InterruptedException {
        final var code = new CompletableFuture<String>();
        synchronized (LoopbackAuthorization.class) {
            if (pending != null) {
                throw new MapRouletteException(tr("A MapRoulette login is already waiting for the browser."));
            }
            pending = code;
        }
        AuthorizationHandler.addAuthorizationConsumer(state, new CodeConsumer(state, code));
        // Like JOSM's own OSM login: run remote control just for the login if the user has it off
        final var startRemoteControl = !RemoteControl.PROP_REMOTECONTROL_ENABLED.get();
        if (startRemoteControl) {
            RemoteControl.start();
        }
        try {
            final var browserError = OpenBrowser.displayUrl(authorizeUri);
            if (browserError != null) {
                throw new MapRouletteException(
                        tr("Could not open the browser to log in to MapRoulette: {0}", browserError));
            }
            return code.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new MapRouletteException(tr("The MapRoulette login timed out. If another program uses port 8111, "
                    + "JOSM cannot receive the login."), e);
        } catch (CancellationException e) {
            throw new MapRouletteException(tr("The MapRoulette login was cancelled."), e);
        } catch (ExecutionException e) {
            throw new MapRouletteException(tr("The MapRoulette login failed."), e.getCause());
        } finally {
            synchronized (LoopbackAuthorization.class) {
                pending = null;
            }
            if (startRemoteControl) {
                RemoteControl.stop();
            }
        }
    }

    @Override
    public void cancel() {
        final CompletableFuture<String> code;
        synchronized (LoopbackAuthorization.class) {
            code = pending;
        }
        if (code != null) {
            code.cancel(false);
        }
    }

    /**
     * Receives the authorization code from remote control
     *
     * @param state The state we sent, which the answer must carry
     * @param code  Completed with the authorization code
     */
    record CodeConsumer(String state, CompletableFuture<String> code)
            implements AuthorizationHandler.AuthorizationConsumer {

    @Override
    public void validateRequest(String sender, String request, Map<String, String> args)
            throws RequestHandler.RequestHandlerBadRequestException {
        // JOSM keeps a consumer until it handles a request, so one from a login that timed out or was cancelled
        // must refuse late answers
        if (code.isDone() || !state.equals(args.get("state")) || args.getOrDefault("code", "").isEmpty()) {
            throw new RequestHandler.RequestHandlerBadRequestException("Not a MapRoulette login");
        }
    }

    @Override
    public AuthorizationHandler.ResponseRecord handleRequest(String sender, String request, Map<String, String> args) {
        final var accepted = code.complete(args.get("code"));
        return new AuthorizationHandler.ResponseRecord(
                accepted ? tr("JOSM received your MapRoulette login. You can close this page and return to JOSM.")
                        : tr("This MapRoulette login is no longer waited for. Log in again from JOSM."),
                "text/plain");
    }
}}
