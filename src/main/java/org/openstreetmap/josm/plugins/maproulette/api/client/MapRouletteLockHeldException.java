// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.Serial;
import java.util.Optional;
import java.util.OptionalLong;

import jakarta.annotation.Nullable;

/**
 * HTTP 409: usually the user already holds a task lock, and MapRoulette allows only one. MapRoulette also uses 409
 * for OSM change conflicts, which carry no held task.
 */
public class MapRouletteLockHeldException extends MapRouletteException {
    @Serial
    private static final long serialVersionUID = 1590383569207587432L;
    /**
     * The id of the task the user holds, or -1 if the server didn't say
     */
    private final long heldTaskId;
    /**
     * The other tasks in the held bundle
     */
    private final long[] bundledTaskIds;
    /**
     * The {@code message} from the server's error body, if any
     */
    private final String serverMessage;

    /**
     * Create a new exception
     *
     * @param message        The message
     * @param serverMessage  The {@code message} from the server's error body, if any
     * @param heldTaskId     The task the user holds, if the server said
     * @param bundledTaskIds The other tasks in the held bundle
     */
    public MapRouletteLockHeldException(String message, @Nullable String serverMessage, OptionalLong heldTaskId,
            long... bundledTaskIds) {
        super(message);
        this.serverMessage = serverMessage;
        this.heldTaskId = heldTaskId.orElse(-1);
        this.bundledTaskIds = bundledTaskIds.clone();
    }

    /**
     * Get the server's explanation. A 409 without a held task is not about locks (e.g. an OSM change conflict).
     *
     * @return The {@code message} from the server's error body, if any
     */
    public Optional<String> serverMessage() {
        return Optional.ofNullable(this.serverMessage);
    }

    /**
     * Get the task the user holds
     *
     * @return The task id, if the server said which task it is
     */
    public OptionalLong heldTaskId() {
        return this.heldTaskId < 0 ? OptionalLong.empty() : OptionalLong.of(this.heldTaskId);
    }

    /**
     * Get the other tasks in the held bundle
     *
     * @return The task ids; empty if the lock is not a bundle or the server didn't say
     */
    public long[] bundledTaskIds() {
        return this.bundledTaskIds.clone();
    }
}
