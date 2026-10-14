// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.enums;

/**
 * The challenge/task difficulty
 */
public enum Difficulty {
    /**
     * The challenge/task is easy
     */
    EASY(1),
    /**
     * The challenge/task is normal
     */
    NORMAL(2),
    /**
     * The challenge/task is hard
     */
    HARD(3);

    private final int code;

    Difficulty(int code) {
        this.code = code;
    }

    /**
     * Get the code MapRoulette uses for this difficulty
     *
     * @return The server code
     */
    public int code() {
        return this.code;
    }

    /**
     * Get the difficulty for a MapRoulette server code
     *
     * @param code The server code
     * @return The difficulty
     * @throws IllegalArgumentException if MapRoulette sent an unknown code
     */
    public static Difficulty fromCode(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown MapRoulette difficulty: " + code);
    }
}
