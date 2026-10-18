// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.data;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;

@BasicPreferences
class IgnoreListTest {
    @Test
    void testIgnores() {
        // Set up the "basic" list
        for (int i = 0; i < 10; i++) {
            IgnoreList.ignoreTask(i);
            IgnoreList.ignoreChallenge(i);
        }
        for (int i = 20; i < 30; i++) {
            IgnoreList.ignoreTask(i);
            IgnoreList.ignoreChallenge(i);
        }
        IgnoreList.ignoreTask(15);
        IgnoreList.ignoreChallenge(15);
        assertAll(() -> assertTrue(IgnoreList.isTaskIgnored(0)), () -> assertTrue(IgnoreList.isChallengeIgnored(0)),
                () -> assertTrue(IgnoreList.isTaskIgnored(9)), () -> assertTrue(IgnoreList.isChallengeIgnored(9)),
                () -> assertTrue(IgnoreList.isTaskIgnored(15)), () -> assertTrue(IgnoreList.isChallengeIgnored(15)),
                () -> assertTrue(IgnoreList.isTaskIgnored(20)), () -> assertTrue(IgnoreList.isChallengeIgnored(20)),
                () -> assertTrue(IgnoreList.isTaskIgnored(29)), () -> assertTrue(IgnoreList.isChallengeIgnored(29)));
    }

    /**
     * Removing the first, a middle or the last entry removes exactly that one (#136)
     */
    @Test
    void testUnignore() {
        // Ids no other test uses: the lists last for the whole test run
        final long[] ids = { 900_001, 900_002, 900_003 };
        for (var id : ids) {
            IgnoreList.ignoreTask(id);
            IgnoreList.ignoreChallenge(id);
        }
        IgnoreList.unignoreTask(ids[1]);
        IgnoreList.unignoreChallenge(ids[1]);
        assertAll(() -> assertTrue(IgnoreList.isTaskIgnored(ids[0])),
                () -> assertFalse(IgnoreList.isTaskIgnored(ids[1])), () -> assertTrue(IgnoreList.isTaskIgnored(ids[2])),
                () -> assertTrue(IgnoreList.isChallengeIgnored(ids[0])),
                () -> assertFalse(IgnoreList.isChallengeIgnored(ids[1])),
                () -> assertTrue(IgnoreList.isChallengeIgnored(ids[2])));
        IgnoreList.unignoreTask(ids[2]);
        IgnoreList.unignoreChallenge(ids[2]);
        assertAll(() -> assertTrue(IgnoreList.isTaskIgnored(ids[0])),
                () -> assertFalse(IgnoreList.isTaskIgnored(ids[2])),
                () -> assertTrue(IgnoreList.isChallengeIgnored(ids[0])),
                () -> assertFalse(IgnoreList.isChallengeIgnored(ids[2])));
        IgnoreList.unignoreTask(ids[0]);
        IgnoreList.unignoreChallenge(ids[0]);
        assertAll(() -> assertFalse(IgnoreList.isTaskIgnored(ids[0])),
                () -> assertFalse(IgnoreList.isChallengeIgnored(ids[0])));
    }
}
