// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.enums;

/**
 * The task priority
 */
public enum Priority {
    /**
     * High priority, the default
     */
    HIGH(0),
    /**
     * Medium priority
     */
    MEDIUM(1),
    /**
     * Low priority
     */
    LOW(2);

    private final int code;

    Priority(int code) {
        this.code = code;
    }

    /**
     * Get the code MapRoulette uses for this priority
     *
     * @return The server code
     */
    public int code() {
        return this.code;
    }

    /**
     * Get the priority for a MapRoulette server code
     *
     * @param code The server code
     * @return The priority
     * @throws IllegalArgumentException if MapRoulette sent an unknown code
     */
    public static Priority fromCode(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown MapRoulette priority: " + code);
    }
}
