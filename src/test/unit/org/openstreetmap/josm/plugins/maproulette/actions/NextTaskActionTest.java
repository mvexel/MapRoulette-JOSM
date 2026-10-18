// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.testutils.annotations.Main;

import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;

/**
 * Next task in the menu starts the offered task, or says why it can't (#117)
 */
@Main
@MapRouletteConfig
class NextTaskActionTest {
    @Test
    void testNextTask() {
        final var offered = new boolean[] { false };
        final var starts = new int[1];
        new MockUp<CurrentTaskPanel>() {
            @Mock
            boolean startNextTask() {
                starts[0]++;
                return offered[0];
            }
        };
        final var notices = new ArrayList<String>();
        new MockUp<Notification>() {
            @Mock
            void show(Invocation invocation) {
                notices.add(invocation.<Notification> getInvokedInstance().getContent().toString());
            }
        };
        final var action = new NextTaskAction();
        GuiHelper.runInEDTAndWait(() -> action.actionPerformed(null));
        assertEquals(1, starts[0]);
        assertEquals(1, notices.size(), "Nothing offered: says why");
        offered[0] = true;
        GuiHelper.runInEDTAndWait(() -> action.actionPerformed(null));
        assertEquals(2, starts[0]);
        assertEquals(1, notices.size(), "Started: no notice");
    }
}
