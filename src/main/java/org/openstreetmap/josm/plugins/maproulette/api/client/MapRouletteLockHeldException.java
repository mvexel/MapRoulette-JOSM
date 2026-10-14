// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.client;

import java.io.Serial;
import java.util.OptionalLong;

/**
 * The user already holds a task lock, and MapRoulette allows only one (HTTP 409)
 */
public class MapRouletteLockHeldException extends MapRouletteException {
    @Serial
    private static final long serialVersionUID = 1590383569207587432L;
    /**
     * The id of the task the user holds, or -1 if the server didn't say
     */
    private final long heldTaskId;

    /**
     * Create a new exception
     *
     * @param message    The message
     * @param heldTaskId The task the user holds, if the server said
     */
    public MapRouletteLockHeldException(String message, OptionalLong heldTaskId) {
        super(message);
        this.heldTaskId = heldTaskId.orElse(-1);
    }

    /**
     * Get the task the user holds
     *
     * @return The task id, if the server said which task it is
     */
    public OptionalLong heldTaskId() {
        return this.heldTaskId < 0 ? OptionalLong.empty() : OptionalLong.of(this.heldTaskId);
    }
}
