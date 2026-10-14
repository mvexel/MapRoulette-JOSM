// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.api.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Check that enums map to the codes MapRoulette uses, independent of their declaration order
 */
class ServerCodeTest {
    @Test
    void testTaskStatusCodes() {
        assertEquals(TaskStatus.CREATED, TaskStatus.fromCode(0));
        assertEquals(TaskStatus.FIXED, TaskStatus.fromCode(1));
        assertEquals(TaskStatus.FALSE_POSITIVE, TaskStatus.fromCode(2));
        assertEquals(TaskStatus.SKIPPED, TaskStatus.fromCode(3));
        assertEquals(TaskStatus.DELETED, TaskStatus.fromCode(4));
        assertEquals(TaskStatus.ALREADY_FIXED, TaskStatus.fromCode(5));
        assertEquals(TaskStatus.TOO_HARD, TaskStatus.fromCode(6));
        assertEquals(TaskStatus.ANSWERED, TaskStatus.fromCode(7));
        assertEquals(TaskStatus.VALIDATED, TaskStatus.fromCode(8));
        assertEquals(TaskStatus.DISABLED, TaskStatus.fromCode(9));
        for (var status : TaskStatus.values()) {
            assertEquals(status, TaskStatus.fromCode(status.code()));
        }
        assertThrows(IllegalArgumentException.class, () -> TaskStatus.fromCode(10));
    }

    @Test
    void testPriorityCodes() {
        assertEquals(Priority.HIGH, Priority.fromCode(0));
        assertEquals(Priority.MEDIUM, Priority.fromCode(1));
        assertEquals(Priority.LOW, Priority.fromCode(2));
        assertThrows(IllegalArgumentException.class, () -> Priority.fromCode(3));
    }

    @Test
    void testDifficultyCodes() {
        assertEquals(Difficulty.EASY, Difficulty.fromCode(1));
        assertEquals(Difficulty.NORMAL, Difficulty.fromCode(2));
        assertEquals(Difficulty.HARD, Difficulty.fromCode(3));
        assertThrows(IllegalArgumentException.class, () -> Difficulty.fromCode(0));
    }
}
