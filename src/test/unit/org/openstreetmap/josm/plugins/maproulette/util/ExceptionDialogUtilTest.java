// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteUnsupportedException;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteUnsupportedException.Reason;

/**
 * Test class for {@link ExceptionDialogUtil}
 */
class ExceptionDialogUtilTest {
    /**
     * A scope refusal names the refused action and does not suggest logging in again, which never helps (#107)
     */
    @Test
    void testScopeRefusalNamesAction() {
        assertEquals("This MapRoulette server does not allow JOSM to mark a task as Skipped.",
                ExceptionDialogUtil.explainRefusal(new MapRouletteUnsupportedException("refused",
                        Reason.INSUFFICIENT_SCOPE, "/api/v2/task/18/3")));
        assertEquals("This MapRoulette server does not allow JOSM to work on task bundles.",
                ExceptionDialogUtil.explainRefusal(new MapRouletteUnsupportedException("refused",
                        Reason.INSUFFICIENT_SCOPE, "/api/v2/taskBundle")));
        final var unknown = ExceptionDialogUtil
                .explainRefusal(new MapRouletteUnsupportedException("refused", Reason.INSUFFICIENT_SCOPE, "/api/v2/x"));
        assertEquals("This MapRoulette server does not allow this from JOSM.", unknown);
        assertFalse(unknown.contains("again"));
    }

    /**
     * Only a scope refusal names the action: another refusal on a known request keeps its own reason
     */
    @Test
    void testOtherRefusalKeepsItsReason() {
        assertEquals(ExceptionDialogUtil.explainRefusal(Reason.CHALLENGE_DRAFT), ExceptionDialogUtil.explainRefusal(
                new MapRouletteUnsupportedException("refused", Reason.CHALLENGE_DRAFT, "/api/v2/task/18/start")));
        assertEquals(ExceptionDialogUtil.explainRefusal(Reason.MOBILE_WRITES_DISABLED),
                ExceptionDialogUtil.explainRefusal(new MapRouletteUnsupportedException("refused",
                        Reason.MOBILE_WRITES_DISABLED, "/api/v2/task/18/3")));
    }

    /**
     * Each task request has a description for the user
     */
    @Test
    void testRefusedAction() {
        assertEquals("start a task", ExceptionDialogUtil.refusedAction("/api/v2/task/1/start"));
        assertEquals("stop a task", ExceptionDialogUtil.refusedAction("/api/v2/task/1/release"));
        assertEquals("keep a task started", ExceptionDialogUtil.refusedAction("/api/v2/task/1/refreshLock"));
        assertEquals("skip a task", ExceptionDialogUtil.refusedAction("/api/v2/task/1/skip"));
        assertEquals("comment on a task", ExceptionDialogUtil.refusedAction("/api/v2/task/1/comment"));
        assertEquals("link a changeset to a task", ExceptionDialogUtil.refusedAction("/api/v2/task/1/changeset"));
        assertEquals("mark a task as Fixed", ExceptionDialogUtil.refusedAction("/api/v2/task/1/1"));
        assertNull(ExceptionDialogUtil.refusedAction("/api/v2/task/1/99"), "Unknown status");
        assertNull(ExceptionDialogUtil.refusedAction("/api/v2/task/1"), "Not an action");
    }
}
