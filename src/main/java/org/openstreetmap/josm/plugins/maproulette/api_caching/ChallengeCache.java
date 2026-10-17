// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api_caching;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.commons.jcs3.access.CacheAccess;
import org.openstreetmap.josm.data.cache.JCSCacheManager;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.ChallengeAPI;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.tools.Logging;

/**
 * A cache for challenge objects, which don't change often. Use this if you don't need the absolute freshest data.
 */
public final class ChallengeCache {
    /**
     * The cache for challenge objects
     */
    private static final CacheAccess<Long, Challenge> CACHE = JCSCacheManager.getCache("maproulette:challenge");
    /**
     * How long to wait before fetching a challenge again after a failed background fetch
     */
    private static final long RETRY_NANOS = TimeUnit.MINUTES.toNanos(1);
    /**
     * Challenges being fetched in the background, or whose fetch failed, with the earliest time to fetch them again
     */
    private static final Map<Long, Long> PENDING = new ConcurrentHashMap<>();
    /**
     * Challenges whose background fetch failed unexpectedly, so the warning is logged only once
     */
    private static final Set<Long> WARNED = ConcurrentHashMap.newKeySet();
    /**
     * Called on the EDT when a background fetch has added a challenge
     */
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Prevent instantiation
     */
    private ChallengeCache() {
        // Hide constructor
    }

    /**
     * Check if a challenge is hidden. This never blocks: an unknown challenge is fetched in the background and counts
     * as not hidden until it arrives (see {@link #addListener(Runnable)}).
     *
     * @param id The id of the challenge to check
     * @return {@code true} if the challenge is hidden and its tasks should not be shown
     */
    public static boolean isHidden(long id) {
        return cached(id).map(Challenge::deleted).orElse(false);
    }

    /**
     * Get a challenge without blocking. If it is not cached, it is fetched in the background and the listeners are
     * notified when it arrives.
     *
     * @param id The challenge id
     * @return The cached challenge, if there is one
     */
    public static Optional<Challenge> cached(long id) {
        final var challenge = CACHE.get(id);
        if (challenge == null) {
            prefetch(id);
        }
        return Optional.ofNullable(challenge);
    }

    /**
     * Fetch a challenge on the worker, unless it is already being fetched or recently failed
     *
     * @param id The challenge id
     */
    private static void prefetch(long id) {
        final var now = System.nanoTime();
        final var start = new boolean[1];
        PENDING.compute(id, (key, notBefore) -> {
            if (notBefore != null && now - notBefore < 0) {
                return notBefore;
            }
            start[0] = true;
            return now + RETRY_NANOS;
        });
        if (!start[0]) {
            return;
        }
        final boolean queued = Workers.tryExecute(() -> {
            try {
                challenge(id);
                PENDING.remove(id);
                GuiHelper.runInEDT(() -> LISTENERS.forEach(Runnable::run));
            } catch (IOException ioException) {
                // Leave the entry so the challenge is not fetched again until RETRY_NANOS has passed
                Logging.trace(ioException);
            } catch (RuntimeException runtimeException) {
                // This runs while painting and sorting, so don't let it reach the bug report dialog
                if (WARNED.add(id)) {
                    Logging.log(Logging.LEVEL_WARN, "Could not read MapRoulette challenge " + id, runtimeException);
                }
            }
        });
        if (!queued) {
            // JOSM is quitting
            PENDING.remove(id);
        }
    }

    /**
     * Add a listener for challenges fetched in the background
     *
     * @param listener Called on the EDT after a challenge was added
     */
    public static void addListener(Runnable listener) {
        LISTENERS.addIfAbsent(listener);
    }

    /**
     * Remove a listener added with {@link #addListener(Runnable)}
     *
     * @param listener The listener to remove
     */
    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Cache results from {@link ChallengeAPI#challenge(long)}
     *
     * @param id The challenge id
     * @return The cached challenge
     * @throws IOException if there was a problem communicating with the server
     */
    public static Challenge challenge(long id) throws IOException {
        if (CACHE.get(id) != null) {
            return CACHE.get(id);
        }
        synchronized (CACHE) {
            if (CACHE.get(id) == null) {
                final var challenge = ChallengeAPI.challenge(id);
                CACHE.put(id, challenge);
            }
        }
        return CACHE.get(id);
    }
}
