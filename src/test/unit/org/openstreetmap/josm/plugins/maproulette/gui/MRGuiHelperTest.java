// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Dialog;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.parsers.TaskParser;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;

import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import sun.misc.Unsafe;

@MapRouletteConfig
class MRGuiHelperTest {

    @Test
    void testGetInstructionTextMustache() {
        final var task = assertDoesNotThrow(() -> TaskAPI.get(147197178));
        // The instructions come from the challenge, which getInstructionText only reads from the cache
        assertDoesNotThrow(() -> ChallengeCache.challenge(task.parentId()));
        final var instructions = MRGuiHelper.getInstructionText(task);
        assertEquals(
                """
                        <html><ol>
                        <li>Node 6318952236 has been tagged as a building indicating that it represents a building, but it is better to represent buildings with polygons or multipolygons. Please see if there is enough satellite imagery information to replace this node with a new polygon. If there is enough detail to draw this building as a polygon then add the polygon that represents the building and remove the building tag from this node or transfer the tags from this node to the new polygon, and then delete this node. See https://wiki.openstreetmap.org/wiki/Mapping_addresses_as_separate_nodes_or_by_adding_to_building_polygons.</li>
                        </ol>
                        </html>""",
                instructions);
    }

    @Test
    void testFilterKeysRemovesMapRouletteIdentifiers() {
        assertEquals(List.of("building", "addr:street"),
                MRGuiHelper.filterKeys(Stream.of("building", "identifier", "addr:street", "@id", "osm_id")).toList());
    }

    /**
     * Tag values substituted into instructions must not be interpreted as regex group references
     */
    @Test
    void testGetInstructionTextMustacheWithSpecialCharacters() throws Exception {
        final var json = Files.readString(Path.of("src/test/resources/__files/api/v2/task/147197178/start"))
                .replace("\"instruction\": \"\"", "\"instruction\": \"Street: {{addr:street}}\"")
                .replace("Chatham Hill Road", "Costs $1 \\\\ Road");
        final var task = (Task) TaskParser.parseTask(new StringReader(json));
        final var instructions = assertDoesNotThrow(() -> MRGuiHelper.getInstructionText(task));
        assertEquals("<html><p>Street: Costs $1 \\ Road</p>\n</html>", instructions);
    }

    /**
     * With no focused window (as in a headless test), dialogs fall back to the main frame (#83)
     */
    @Test
    void testDialogParentFallsBackToMainFrame() {
        assertSame(MainApplication.getMainFrame(), MRGuiHelper.dialogParent());
    }

    /**
     * A modal window, such as the PleaseWaitDialog of a running job, is about to close and would take the dialog with
     * it, so dialogs go to the main frame. A detached panel window is used (#83).
     *
     * @throws ReflectiveOperationException if the dialogs could not be made
     */
    @Test
    void testDialogParentSkipsModalWindow() throws ReflectiveOperationException {
        // Dialogs can't be constructed headless, so make them without running a constructor
        final var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        final var unsafe = (Unsafe) unsafeField.get(null);
        final var modal = (Dialog) unsafe.allocateInstance(Dialog.class);
        final var detached = (Dialog) unsafe.allocateInstance(Dialog.class);
        new MockUp<Dialog>() {
            @Mock
            boolean isModal(Invocation invocation) {
                return invocation.getInvokedInstance() == modal;
            }

            @Mock
            boolean isShowing() {
                return true;
            }
        };
        assertSame(MainApplication.getMainFrame(), MRGuiHelper.dialogParent(modal));
        assertSame(detached, MRGuiHelper.dialogParent(detached));
    }
}
