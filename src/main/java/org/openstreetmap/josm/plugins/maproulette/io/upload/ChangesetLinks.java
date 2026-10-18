// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteAuthUnavailableException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteException;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

/**
 * Links tasks marked Fixed to the changeset of their edits. MapRoulette only links a closed changeset, so a link to a
 * changeset that is still open waits and is tried again, also after JOSM restarts. Everything here makes HTTP calls:
 * call it off the EDT.
 */
public final class ChangesetLinks {
    /**
     * How long MapRoulette accepts a link after the task was marked Fixed
     */
    static final Duration WINDOW = Duration.ofHours(24);

    /**
     * The preference that keeps the waiting links when JOSM quits
     */
    static final String PREF = "maproulette.changeset.links.pending";

    /**
     * A link that waits for its changeset to be closed
     *
     * @param taskId      The task marked Fixed
     * @param changesetId The changeset
     * @param since       When the link was first tried
     * @param server      The MapRoulette server the task was marked Fixed on
     */
    private record Pending(long taskId, long changesetId, Instant since, String server) {
        Map<String, String> toMap() {
            // The preferences look up null keys, which an immutable map refuses
            return new HashMap<>(Map.of("task", Long.toString(this.taskId), "changeset",
                    Long.toString(this.changesetId), "since", this.since.toString(), "server", this.server));
        }

        static Pending fromMap(Map<String, String> map) {
            return new Pending(Long.parseLong(map.get("task")), Long.parseLong(map.get("changeset")),
                    Instant.parse(map.get("since")), Objects.requireNonNull(map.get("server")));
        }
    }

    /**
     * The links waiting for their changeset to be closed, by task id
     */
    private static final Map<Long, Pending> PENDING = new ConcurrentHashMap<>();

    private ChangesetLinks() {
        // Hide constructor
    }

    /**
     * Link tasks to a changeset; links to a changeset that is still open wait
     *
     * @param tasks       The tasks marked Fixed
     * @param changesetId The changeset, or {@code 0} to let MapRoulette find it
     * @param server      The MapRoulette server the tasks were marked Fixed on
     */
    static void link(List<Task> tasks, long changesetId, String server) {
        final var now = Instant.now();
        for (var task : tasks) {
            if (!tryLink(task.id(), changesetId, server) && changesetId > 0) {
                PENDING.put(task.id(), new Pending(task.id(), changesetId, now, server));
                TaskResults.waiting(task.id(), changesetId);
            }
        }
        save();
    }

    /**
     * Wait for a changeset to be closed before linking tasks to it
     *
     * @param tasks       The tasks marked Fixed
     * @param changesetId The changeset
     * @param server      The MapRoulette server the tasks were marked Fixed on
     */
    static void await(List<Task> tasks, long changesetId, String server) {
        final var now = Instant.now();
        tasks.forEach(task -> {
            PENDING.put(task.id(), new Pending(task.id(), changesetId, now, server));
            TaskResults.waiting(task.id(), changesetId);
        });
        save();
    }

    /**
     * Check if a link waits for a changeset
     *
     * @param changesetId The changeset
     * @return {@code true} if a task waits for it
     */
    static boolean awaits(long changesetId) {
        return PENDING.values().stream().anyMatch(pending -> pending.changesetId() == changesetId);
    }

    /**
     * Try the waiting links to one changeset again, for example once JOSM closed it
     *
     * @param changesetId The changeset
     */
    static void retry(long changesetId) {
        retry(changesetId, Instant.now());
    }

    /**
     * Try all waiting links again. MapRoulette can close a changeset JOSM does not know about (the idle auto-close, or
     * another editor), so this runs from a timer.
     */
    public static void retryAll() {
        retry(-1, Instant.now());
    }

    /**
     * Try waiting links again
     *
     * @param changesetId The changeset, or {@code -1} for all
     * @param now         The current time
     */
    static void retry(long changesetId, Instant now) {
        if (PENDING.isEmpty() || !MapRouletteAuth.isAvailable()) {
            return;
        }
        for (var pending : List.copyOf(PENDING.values())) {
            if (changesetId >= 0 && pending.changesetId() != changesetId) {
                continue;
            }
            if (pending.since().plus(WINDOW).isBefore(now)) {
                // MapRoulette no longer links it
                PENDING.remove(pending.taskId(), pending);
                Logging.info("Gave up linking MapRoulette task " + pending.taskId() + " to changeset "
                        + pending.changesetId());
                TaskResults.linked(pending.taskId(), pending.changesetId(), false);
            } else if (tryLink(pending.taskId(), pending.changesetId(), pending.server())) {
                PENDING.remove(pending.taskId(), pending);
            }
        }
        save();
    }

    /**
     * Pick up the links that were still waiting when JOSM quit. The timer that tries all waiting links sends them.
     */
    public static void load() {
        for (var map : Config.getPref().getListOfMaps(PREF, Collections.emptyList())) {
            try {
                final var pending = Pending.fromMap(map);
                PENDING.putIfAbsent(pending.taskId(), pending);
            } catch (RuntimeException e) {
                Logging.warn("Ignoring a stored MapRoulette changeset link: " + map);
                Logging.debug(e);
            }
        }
    }

    /**
     * Keep the waiting links, so that they are still sent if JOSM quits first
     */
    private static synchronized void save() {
        Config.getPref().putListOfMaps(PREF, PENDING.values().stream().map(Pending::toMap).toList());
    }

    /**
     * Forget all waiting links, for example when the user switches to another MapRoulette server
     *
     * @return The ids of the tasks that were waiting
     */
    public static Set<Long> forgetAll() {
        final var ids = new TreeSet<>(PENDING.keySet());
        ids.forEach(PENDING::remove);
        save();
        return ids;
    }

    /**
     * Link a task to a changeset. The status is already sent, so a refusal here only means the task stays unlinked.
     *
     * @param taskId      The task marked Fixed
     * @param changesetId The changeset, or {@code 0} to let MapRoulette find it
     * @param server      The MapRoulette server the task was marked Fixed on
     * @return {@code true} if done (linked, or refused for good); {@code false} to try again later
     */
    private static boolean tryLink(long taskId, long changesetId, String server) {
        if (!server.equals(MapRouletteConfig.getBaseUrl())) {
            // The task id may belong to an unrelated task on the server now configured
            Logging.info("Not linking MapRoulette task " + taskId + ": it is on " + server);
            return true;
        }
        try {
            TaskAPI.changeset(taskId, changesetId);
            TaskResults.linked(taskId, changesetId, true);
            return true;
        } catch (IOException e) {
            if (changesetId > 0 && isTemporary(e)) {
                Logging.debug(e);
                return false;
            }
            Logging.warn(e);
            // Tells the user, in a notification and in the panel
            TaskResults.linked(taskId, changesetId, false);
            return true;
        }
    }

    /**
     * Check if a link may work later: the changeset is still open, the connection failed, or the login is gone for now
     *
     * @param e The failure
     * @return {@code true} to try again later
     */
    private static boolean isTemporary(IOException e) {
        if (e instanceof MapRouletteException mapRouletteException) {
            return "changeset_open".equals(mapRouletteException.errorCode().orElse(null))
                    || e instanceof MapRouletteAuthUnavailableException || e.getClass() == MapRouletteException.class;
        }
        return true;
    }
}
