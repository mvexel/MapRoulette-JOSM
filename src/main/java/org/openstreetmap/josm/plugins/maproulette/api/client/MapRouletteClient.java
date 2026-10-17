// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.auth.AuthProvider;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.JosmRuntimeException;
import org.openstreetmap.josm.tools.ListenerList;
import org.openstreetmap.josm.tools.Logging;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;

/**
 * The single entry point for MapRoulette requests. It owns the login ({@link AuthProvider}) and maps server errors
 * to {@link MapRouletteException} subclasses.
 * <p>
 * The request methods block, so they must not be called on the EDT.
 */
public final class MapRouletteClient {
    /**
     * The instance
     */
    private static final MapRouletteClient INSTANCE = new MapRouletteClient();
    /**
     * How much of an error response body to keep
     */
    private static final int BODY_EXCERPT_LENGTH = 200;
    /**
     * The JSON content type
     */
    private static final String JSON = "application/json";
    /**
     * Listeners for login availability changes
     */
    private final ListenerList<Runnable> authListeners = ListenerList.create();
    /**
     * The current login
     */
    private volatile AuthProvider auth = AuthProvider.NONE;

    private MapRouletteClient() {
        // Use getInstance
    }

    /**
     * Get the client
     *
     * @return The client
     */
    public static MapRouletteClient getInstance() {
        return INSTANCE;
    }

    /**
     * Get the current login
     *
     * @return The login provider
     */
    @Nonnull
    public AuthProvider auth() {
        return this.auth;
    }

    /**
     * Set the login. Listeners are told on the EDT.
     *
     * @param provider The login provider
     */
    public void setAuthProvider(@Nonnull AuthProvider provider) {
        this.auth = Objects.requireNonNull(provider, "provider");
        fireAuthChanged();
    }

    /**
     * Tell the listeners that the login changed, e.g. the user logged in or out. Listeners are told on the EDT.
     */
    public void fireAuthChanged() {
        GuiHelper.runInEDT(() -> this.authListeners.fireEvent(Runnable::run));
    }

    /**
     * Listen for login changes, so the UI can re-evaluate which actions are enabled
     *
     * @param listener Called on the EDT when the login changes
     */
    public void addAuthListener(@Nonnull Runnable listener) {
        this.authListeners.addListener(listener);
    }

    /**
     * Stop listening for login changes
     *
     * @param listener The listener to remove
     */
    public void removeAuthListener(@Nonnull Runnable listener) {
        this.authListeners.removeListener(listener);
    }

    /**
     * Fail fast for requests that need a MapRoulette login
     *
     * @throws MapRouletteAuthUnavailableException if no login is available
     */
    public void requireAuth() throws MapRouletteAuthUnavailableException {
        if (!this.auth.isAvailable()) {
            throw new MapRouletteAuthUnavailableException("MapRoulette login is not available");
        }
    }

    /**
     * GET a path
     *
     * @param path   The path below the API base URL, starting with {@code /}
     * @param query  The query parameters
     * @param parser The response parser
     * @param <T>    The result type
     * @return The parsed response
     * @throws MapRouletteException if the request failed
     */
    public <T> T get(String path, Map<String, String> query, ResponseParser<T> parser) throws MapRouletteException {
        return request("GET", path, query, null, null, parser, false);
    }

    /**
     * GET a path that changes something, like starting a task. This needs a login: without one it fails before
     * sending.
     *
     * @param path   The path below the API base URL, starting with {@code /}
     * @param query  The query parameters
     * @param parser The response parser
     * @param <T>    The result type
     * @return The parsed response
     * @throws MapRouletteException if the request failed
     */
    public <T> T getWithLogin(String path, Map<String, String> query, ResponseParser<T> parser)
            throws MapRouletteException {
        requireAuth();
        return request("GET", path, query, null, null, parser, true);
    }

    /**
     * PUT to a path. This needs a login: without one it fails before sending.
     *
     * @param path     The path below the API base URL, starting with {@code /}
     * @param query    The query parameters
     * @param jsonBody The JSON body, if any
     * @param parser   The response parser, or {@code null} to ignore the body
     * @param <T>      The result type
     * @return The parsed response, or {@code null} if there is no parser
     * @throws MapRouletteException if the request failed
     */
    public <T> T put(String path, Map<String, String> query, @Nullable byte[] jsonBody,
            @Nullable ResponseParser<T> parser) throws MapRouletteException {
        requireAuth();
        return request("PUT", path, query, jsonBody, JSON, parser, true);
    }

    /**
     * PUT a read-only request: the parameters go in the query string, the body is empty, and no login is needed. Only
     * {@link org.openstreetmap.josm.plugins.maproulette.api.TaskAPI#box} uses this; MapRoulette serves that search
     * as a PUT.
     *
     * @param path   The path below the API base URL, starting with {@code /}
     * @param query  The query parameters
     * @param parser The response parser
     * @param <T>    The result type
     * @return The parsed response
     * @throws MapRouletteException if the request failed
     */
    public <T> T putRead(String path, Map<String, String> query, ResponseParser<T> parser) throws MapRouletteException {
        return request("PUT", path, query, null, null, parser, false);
    }

    /**
     * POST to a path. This needs a login: without one it fails before sending.
     *
     * @param path     The path below the API base URL, starting with {@code /}
     * @param query    The query parameters
     * @param jsonBody The JSON body, if any
     * @param parser   The response parser, or {@code null} to ignore the body
     * @param <T>      The result type
     * @return The parsed response, or {@code null} if there is no parser
     * @throws MapRouletteException if the request failed
     */
    public <T> T post(String path, Map<String, String> query, @Nullable byte[] jsonBody,
            @Nullable ResponseParser<T> parser) throws MapRouletteException {
        requireAuth();
        return request("POST", path, query, jsonBody, JSON, parser, true);
    }

    /**
     * DELETE a path. This needs a login: without one it fails before sending.
     *
     * @param path The path below the API base URL, starting with {@code /}
     * @throws MapRouletteException if the request failed
     */
    public void delete(String path) throws MapRouletteException {
        requireAuth();
        request("DELETE", path, Collections.emptyMap(), null, null, null, true);
    }

    private <T> T request(String method, String path, Map<String, String> query, @Nullable byte[] body,
            @Nullable String contentType, @Nullable ResponseParser<T> parser, boolean loginRequired)
            throws MapRouletteException {
        final var url = url(path, query);
        checkThread(method, url);
        final var currentAuth = this.auth;
        for (var attempt = 0;; attempt++) {
            final var client = HttpClient.create(url, method);
            if (body != null) {
                client.setRequestBody(body);
                client.setHeader("Content-Type", contentType);
            } else if (!"GET".equals(method)) {
                // JOSM's HttpClient needs a body for PUT, POST and DELETE
                client.setRequestBody(new byte[0]);
            }
            try {
                currentAuth.authenticate(client, loginRequired);
                final var response = client.connect();
                final var status = response.getResponseCode();
                if (status == 401 && attempt == 0 && currentAuth.onUnauthorized(client)) {
                    continue;
                }
                if (status >= 400) {
                    throw error(status, response.fetchContent(), url);
                }
                if (parser == null) {
                    return null;
                }
                try (var reader = new InputStreamReader(response.getContent(), StandardCharsets.UTF_8)) {
                    return parser.parse(reader);
                } catch (JsonException | IllegalArgumentException | IllegalStateException | ClassCastException
                        | NullPointerException | DateTimeException | JosmRuntimeException e) {
                    // Keep the stack trace for bug reports; this can also be a parser bug
                    Logging.debug(e);
                    throw new MapRouletteParseException("Could not read the MapRoulette response from " + url, e);
                }
            } catch (MapRouletteException e) {
                throw e;
            } catch (IOException e) {
                throw new MapRouletteException("Could not reach MapRoulette at " + url, e);
            } finally {
                client.disconnect();
            }
        }
    }

    /**
     * Map an HTTP error to an exception
     *
     * @param status The HTTP status
     * @param body   The response body
     * @param url    The request URL
     * @return The exception to throw
     */
    static MapRouletteException error(int status, @Nullable String body, URL url) {
        final var safeBody = Objects.requireNonNullElse(body, "");
        final var message = message(safeBody);
        final MapRouletteException exception = switch (status) {
        case 401, 410 -> new MapRouletteAuthUnavailableException(
                "MapRoulette refused the login (HTTP " + status + ") for " + url + message);
        case 403 -> {
            final var reason = MapRouletteUnsupportedException.Reason.fromCode(errorCode(safeBody));
            yield reason != null
                    ? new MapRouletteUnsupportedException(
                            "MapRoulette refused this for the JOSM login (" + reason + "): " + url, reason,
                            url.getPath())
                    : new MapRouletteForbiddenException("MapRoulette refused the request for " + url + message);
        }
        case 404 -> new MapRouletteNotFoundException("Not found in MapRoulette: " + url + message);
        case 409 -> new MapRouletteLockHeldException("MapRoulette conflict for " + url + message,
                serverMessage(safeBody), lockedTaskId(safeBody), bundledTaskIds(safeBody));
        default -> new MapRouletteHttpException("MapRoulette answered HTTP " + status + " for " + url + message, status,
                safeBody.substring(0, Math.min(BODY_EXCERPT_LENGTH, safeBody.length())), serverMessage(safeBody));
        };
        return exception.withErrorCode(errorCode(safeBody));
    }

    /**
     * Read the {@code status} and {@code message} of a MapRoulette error body ({@code {"status": "KO", "message": ...}};
     * either may be missing or null)
     *
     * @param body The body
     * @return {@code ": status: message"} with the parts that are present, or an empty string
     */
    private static String message(String body) {
        final var object = jsonObject(body);
        final var message = new StringBuilder();
        if (object != null) {
            for (var key : new String[] { "status", "message" }) {
                if (object.get(key)instanceof JsonString value) {
                    message.append(": ").append(value.getString());
                }
            }
        }
        return message.toString();
    }

    /**
     * Read the {@code error} code of an app login refusal ({@code {"error": "insufficient_scope"}})
     *
     * @param body The body
     * @return The code, or {@code null} if there is none
     */
    @Nullable
    private static String errorCode(String body) {
        final var object = jsonObject(body);
        return object != null && object.get("error")instanceof JsonString value ? value.getString() : null;
    }

    /**
     * Read the {@code message} of a MapRoulette error body
     *
     * @param body The body
     * @return The message, or {@code null} if there is none
     */
    @Nullable
    private static String serverMessage(String body) {
        final var object = jsonObject(body);
        return object != null && object.get("message")instanceof JsonString value ? value.getString() : null;
    }

    /**
     * Read the task the user already holds from a 409 body ({@code {"lockedTaskId": 123, ...}})
     *
     * @param body The body
     * @return The task id, if present
     */
    private static OptionalLong lockedTaskId(String body) {
        final var object = jsonObject(body);
        if (object != null && object.get("lockedTaskId")instanceof JsonNumber id) {
            return OptionalLong.of(id.longValue());
        }
        return OptionalLong.empty();
    }

    /**
     * Read the other tasks of a held bundle from a 409 body ({@code {"bundledTasks": [1, 2], ...}})
     *
     * @param body The body
     * @return The task ids
     */
    private static long[] bundledTaskIds(String body) {
        final var object = jsonObject(body);
        if (object != null && object.get("bundledTasks")instanceof JsonArray tasks) {
            return tasks.stream().filter(JsonNumber.class::isInstance).mapToLong(id -> ((JsonNumber) id).longValue())
                    .toArray();
        }
        return new long[0];
    }

    @Nullable
    private static JsonObject jsonObject(String body) {
        try (var reader = Json.createReader(new StringReader(body))) {
            return reader.read()instanceof JsonObject object ? object : null;
        } catch (JsonException | IllegalStateException e) {
            Logging.trace(e);
            return null;
        }
    }

    /**
     * Refuse MapRoulette IO on the EDT, where it would freeze the UI
     *
     * @param method The request method
     * @param url    The request URL
     * @throws IllegalStateException if called on the EDT
     */
    private static void checkThread(String method, URL url) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("MapRoulette IO on EDT: " + method + ' ' + url);
        }
    }

    /**
     * Build a request URL
     *
     * @param path  The path below the API base URL
     * @param query The query parameters
     * @return The URL
     * @throws MapRouletteException if the configured server URL is not valid
     */
    static URL url(String path, Map<String, String> query) throws MapRouletteException {
        final var base = MapRouletteConfig.getBaseUrl() + path;
        try {
            return URI.create(query == null || query.isEmpty() ? base : base + query(query)).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            // The base URL comes from the user's preferences
            throw new MapRouletteException("The MapRoulette server URL is not valid: " + base, e);
        }
    }

    /**
     * Convert a map of parameters to a query string
     *
     * @param queryParameters The query parameters to send the server
     * @return The query string, starting with {@code ?}
     */
    static String query(Map<String, String> queryParameters) {
        return queryParameters.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&", "?", ""));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
