// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Tagged;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.enums.Difficulty;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Point;
import org.openstreetmap.josm.plugins.maproulette.api.model.PointReview;
import org.openstreetmap.josm.plugins.maproulette.api.model.PublicUser;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskEdits;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteConfig;
import org.openstreetmap.josm.plugins.maproulette.util.MapRouletteLogin;
import org.openstreetmap.josm.plugins.maproulette.util.WireMocks;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.testutils.annotations.BasicPreferences;
import org.openstreetmap.josm.testutils.annotations.Main;
import org.openstreetmap.josm.testutils.annotations.Projection;
import org.openstreetmap.josm.testutils.mockers.ExtendedDialogMocker;

/**
 * Test class for {@link EarlyUploadHook}
 */
@BasicPreferences
@Main
@Projection
@MapRouletteConfig
class EarlyUploadHookTest {
    private EarlyUploadHook hook;

    @BeforeEach
    void setUp() {
        this.hook = new EarlyUploadHook();
    }

    @AfterEach
    void tearDown() {
        ModifiedObjects.getUnlinkedFixed().forEach(ModifiedObjects::removeUnlinkedFixed);
        ModifiedObjects.getModifiedTasks().forEach(ModifiedObjects::removeModifiedTask);
        ModifiedObjects.getLockedTasks().forEach(ModifiedObjects::removeLockedTask);
        ModifiedObjects.setFinishing(List.of());
        LateUploadHook.forgetUnfinished();
        GuiHelper.runInEDTAndWait(TaskEdits::clear);
    }

    @ParameterizedTest
    @MapRouletteLogin
    @ValueSource(strings = { "9494185766_node_4", "9494185766", "node/9494185766", "node 9494185766@4" })
    void testRegexParsing(String title) {
        final var apiDataSet = setUpUpload(title);
        final var wireMock = WireMocks.current();
        wireMock.register(
                put(urlPathEqualTo("/api/v2/task/132279499/" + TaskStatus.FIXED.code())).willReturn(aResponse()));
        wireMock.register(get(urlPathEqualTo("/api/v2/task/132279499/release"))
                .willReturn(aResponse().withBodyFile("api/v2/task/132279499/start")));
        assertTrue(hook.checkUpload(apiDataSet));
        // The answer is sent at once (D15)
        wireMock.verifyThat(1, putRequestedFor(urlPathEqualTo("/api/v2/task/132279499/" + TaskStatus.FIXED.code())));
        final var map = new HashMap<String, String>();
        hook.modifyChangesetTags(map);
        assertEquals("132279499", map.get("maproulette:tasks"));
    }

    /**
     * An upload with edits made while a task is started asks once if it finishes the task. Nothing is sent before the
     * upload; the task is recorded for the late hook, and the changeset is tagged with it (#115).
     */
    @Test
    @MapRouletteLogin
    void testUploadOfTaskEditsAsksToFinish() {
        TestUtils.assumeWorkingJMockit();
        final var task = startedTask(false);
        final var node = editedNode(true);
        final var statuses = List.of(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, Map.of()));
        new ExtendedDialogMocker(Map.of(EarlyUploadHook.finishMessage(statuses), "Finished: I fixed it!"));
        assertTrue(hook.checkUpload(new APIDataSet(node.getDataSet())));
        WireMocks.current().verifyThat(exactly(0), anyRequestedFor(urlPathMatching("/api/v2/task/27/.*")));
        assertEquals(List.of(TaskStatus.FIXED),
                ModifiedObjects.getFinishing().stream().map(ModifiedTask::status).toList());
        final var tags = new HashMap<String, String>();
        hook.modifyChangesetTags(tags);
        assertEquals("27", tags.get("maproulette:tasks"));
    }

    /**
     * "Not finished yet" keeps the task started: nothing is recorded, and the next upload asks again (#115)
     */
    @Test
    @MapRouletteLogin
    void testNotFinishedYet() {
        TestUtils.assumeWorkingJMockit();
        final var task = startedTask(false);
        final var node = editedNode(true);
        final var statuses = List.of(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, Map.of()));
        new ExtendedDialogMocker(Map.of(EarlyUploadHook.finishMessage(statuses), "Not finished yet"));
        assertTrue(hook.checkUpload(new APIDataSet(node.getDataSet())));
        assertTrue(ModifiedObjects.getFinishing().isEmpty());
        assertEquals(List.of(task), ModifiedObjects.getLockedTasks());
    }

    /**
     * After an upload that did not go through, "Not finished yet" on the next one drops the Fixed kept from the first
     * (#130)
     */
    @Test
    @MapRouletteLogin
    void testNotFinishedYetDropsCarriedFinish() {
        TestUtils.assumeWorkingJMockit();
        final var task = startedTask(false);
        final var node = editedNode(true);
        final var statuses = List.of(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, Map.of()));
        // As LateUploadHook.afterUpload does when the first upload stopped partway
        LateUploadHook.carry(statuses);
        new ExtendedDialogMocker(Map.of(EarlyUploadHook.finishMessage(statuses), "Not finished yet"));
        assertTrue(hook.checkUpload(new APIDataSet(node.getDataSet())));
        assertEquals(List.of(), LateUploadHook.withCarried(ModifiedObjects.getFinishing()));
    }

    /**
     * Edits made before the task was started don't finish it, and a choice task is never finished by an upload: no
     * question is asked (#115)
     */
    @Test
    @MapRouletteLogin
    void testNoQuestionWithoutTaskEdits() {
        TestUtils.assumeWorkingJMockit();
        // Any dialog fails the test
        new ExtendedDialogMocker(Map.of());
        final var before = editedNode(false);
        startedTask(false);
        assertTrue(hook.checkUpload(new APIDataSet(before.getDataSet())));
        assertTrue(ModifiedObjects.getFinishing().isEmpty(), "Edited before the task was started");

        ModifiedObjects.getLockedTasks().forEach(ModifiedObjects::removeLockedTask);
        startedTask(true);
        final var choice = editedNode(true);
        assertTrue(hook.checkUpload(new APIDataSet(choice.getDataSet())));
        assertTrue(ModifiedObjects.getFinishing().isEmpty(), "A choice task");
    }

    /**
     * An upload whose task edits are far from the task doesn't ask, and the task stays started. "Upload edits and
     * finish" asks anyway (#149).
     */
    @Test
    @MapRouletteLogin
    void testFarEditsAskOnlyWhenFinishRequested() {
        TestUtils.assumeWorkingJMockit();
        final var task = startedTask(false);
        final var far = editedNode(true);
        // About 80 m north of the task
        far.setCoor(new LatLon(0.00072, 0));
        final var statuses = List.of(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, Map.of()));
        final var dialogs = new ExtendedDialogMocker(
                Map.of(EarlyUploadHook.finishMessage(statuses), "Finished: I fixed it!"));
        assertTrue(hook.checkUpload(new APIDataSet(far.getDataSet())));
        assertEquals(0, dialogs.getInvocationLog().size(), "No question for edits far from the task");
        assertTrue(ModifiedObjects.getFinishing().isEmpty());
        assertEquals(List.of(task), ModifiedObjects.getLockedTasks(), "The task stays started");

        EarlyUploadHook.requestFinish();
        assertTrue(hook.checkUpload(new APIDataSet(far.getDataSet())));
        assertEquals(1, dialogs.getInvocationLog().size(), "Upload edits and finish asks");
        assertEquals(List.of(TaskStatus.FIXED),
                ModifiedObjects.getFinishing().stream().map(ModifiedTask::status).toList());

        assertTrue(hook.checkUpload(new APIDataSet(far.getDataSet())));
        assertEquals(1, dialogs.getInvocationLog().size(), "The request is used up");

        // An upload that stopped before its check leaves no request behind
        EarlyUploadHook.requestFinish();
        EarlyUploadHook.clearFinishRequest();
        assertTrue(hook.checkUpload(new APIDataSet(far.getDataSet())));
        assertEquals(1, dialogs.getInvocationLog().size(), "The request was dropped");
    }

    /**
     * A started choice task is never one an upload finishes (#130)
     */
    @Test
    void testChoiceTaskNotFinishable() {
        final var choice = startedTask(true);
        assertTrue(EarlyUploadHook.finishable().isEmpty(), "A choice task");
        ModifiedObjects.removeLockedTask(choice);
        final var task = startedTask(false);
        assertEquals(List.of(task), EarlyUploadHook.finishable());
    }

    /**
     * The question repeats the comment and the review request, so the user sees what is sent
     */
    @Test
    void testFinishMessage() {
        final var task = task(27, false);
        assertEquals(
                "<html>This upload finishes task 27.<br>No comment for MapRoulette.<br>"
                        + "Choose Not finished yet if the task needs more edits. Your edits upload either way.</html>",
                EarlyUploadHook
                        .finishMessage(List.of(new ModifiedTask(task, TaskStatus.FIXED, null, null, null, null))));
        assertEquals("<html>This upload finishes task 27.<br>Comment for MapRoulette: Bench &lt;added&gt;<br>"
                + "A review is requested.<br>Choose Not finished yet if the task needs more edits. Your edits upload either way.</html>",
                EarlyUploadHook.finishMessage(List
                        .of(new ModifiedTask(task, TaskStatus.FIXED, " Bench <added> ", null, Boolean.TRUE, null))));
    }

    private static Task task(long id, boolean choice) {
        // At the edited bench, so its edits are near the task (#149)
        return new Task(id, "Bench " + id, null, null, 15318, null, new Point(0, 0), new DataSet(),
                choice ? Task.ChoiceWork.INSTANCE : null, null, null, null, null, null, 0, null, null, null, false,
                null, null);
    }

    private static Task startedTask(boolean choice) {
        TaskEdits.start();
        final var task = task(27, choice);
        ModifiedObjects.addLockedTask(task);
        // The edits are followed from the EDT once the task is started
        GuiHelper.runInEDTAndWait(() -> {
            /* Sync EDT */ });
        return task;
    }

    /**
     * Add a bench to a new data layer
     *
     * @param afterStart {@code true} to tag it after the task was started, {@code false} to tag it now
     * @return The bench
     */
    private static Node editedNode(boolean afterStart) {
        final var node = new Node(LatLon.ZERO);
        final var dataSet = new DataSet();
        dataSet.addPrimitive(node);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "bench", null));
        if (afterStart) {
            node.put("amenity", "bench");
        }
        return node;
    }

    /**
     * A task another mapper has locked is not offered as finished: MapRoulette would reject its status (#112)
     */
    @Test
    @MapRouletteLogin
    void testNoQuestionForTaskLockedByOther() {
        final var apiDataSet = setUpUpload("9494185766", 99L);
        assertTrue(hook.checkUpload(apiDataSet));
        WireMocks.current().verifyThat(exactly(0), anyRequestedFor(urlPathMatching("/api/v2/task/132279499.*")));
        assertNull(ModifiedObjects.getModifiedTask(132279499L));
        final var map = new HashMap<String, String>();
        hook.modifyChangesetTags(map);
        assertNull(map.get("maproulette:tasks"));
    }

    /**
     * Without a MapRoulette login the answer can't be sent, so the user is not asked if the task is done
     */
    @Test
    void testNoPromptWithoutLogin() {
        final var apiDataSet = setUpUpload("9494185766");
        assertTrue(hook.checkUpload(apiDataSet));
        assertNull(ModifiedObjects.getModifiedTask(132279499L));
        final var map = new HashMap<String, String>();
        hook.modifyChangesetTags(map);
        assertNull(map.get("maproulette:tasks"));
    }

    /**
     * The changeset is tagged only with the tasks marked Fixed whose OSM objects are in the upload (#97)
     *
     * @throws Exception if the test fails
     */
    @Test
    void testTagsOnlyTasksInUpload() throws Exception {
        final var inUpload = TaskAPI.get(147197178);
        ModifiedObjects.addUnlinkedFixed(inUpload);
        ModifiedObjects.addUnlinkedFixed(TaskAPI.get(132279499));
        final var node = new Node(LatLon.ZERO);
        node.setOsmId(6318952236L, 1);
        node.setModified(true);
        new DataSet().addPrimitive(node);
        assertTrue(hook.checkUpload(new APIDataSet(node.getDataSet())));
        final var map = new HashMap<String, String>();
        hook.modifyChangesetTags(map);
        assertEquals("147197178", map.get("maproulette:tasks"));
    }

    /**
     * The changeset comment and source come only from the tasks in the upload. When none is in it, the mapper's comment
     * is left alone (#95).
     *
     * @throws Exception if the test fails
     */
    @Test
    void testCommentOnlyFromTasksInUpload() throws Exception {
        ModifiedObjects.addUnlinkedFixed(TaskAPI.get(147197178));
        ModifiedObjects.addUnlinkedFixed(TaskAPI.get(132279499));
        final var map = new HashMap<String, String>();
        assertTrue(hook.checkUpload(new APIDataSet(modifiedNode(6318952236L).getDataSet())));
        hook.modifyChangesetTags(map);
        assertEquals("#AtlasChecks #LoneBuildingNodeCheck #maproulette", map.get("comment"),
                "Only the comment of challenge 28410, not that of 27905");

        final var unrelated = new HashMap<String, String>();
        unrelated.put("comment", "Fix a typo");
        assertTrue(hook.checkUpload(new APIDataSet(modifiedNode(1).getDataSet())));
        hook.modifyChangesetTags(unrelated);
        assertEquals(Map.of("comment", "Fix a typo"), unrelated);
    }

    /**
     * Create a modified node to upload
     *
     * @param id The OSM id
     * @return The node
     */
    private static Node modifiedNode(long id) {
        final var node = new Node(LatLon.ZERO);
        node.setOsmId(id, 1);
        node.setModified(true);
        new DataSet().addPrimitive(node);
        return node;
    }

    /**
     * Set up an upload of a node that a MapRoulette task is about, and answer "yes" to "did you finish the task?"
     *
     * @param title The task title
     * @return The data to upload
     */
    private static APIDataSet setUpUpload(String title) {
        return setUpUpload(title, null);
    }

    /**
     * Set up an upload of a node that a MapRoulette task is about, and answer "yes" to "did you finish the task?"
     *
     * @param title    The task title
     * @param lockedBy The MapRoulette user holding a lock on the task, if any
     * @return The data to upload
     */
    private static APIDataSet setUpUpload(String title, Long lockedBy) {
        final var ds = new DataSet();
        final var node = TestUtils.newNode("");
        node.setCoor(LatLon.ZERO);
        node.setOsmId(9494185766L, 1);
        node.setModified(true);
        ds.addPrimitive(node);
        final var apiDataSet = new APIDataSet(ds);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "testRegexParsing", null));
        final var mrLayer = new MapRouletteClusteredPointLayer(new Bounds(0, 0, 0, 0),
                Collections.singleton(new ClusteredPoint(132279499L, 0L, "null", title, 0L, "parentName",
                        new Point(0, 0), "bounding", "blurb", Instant.EPOCH, Difficulty.NORMAL, 2, TaskStatus.CREATED,
                        null, Instant.EPOCH, 0L, new PublicUser(0L, null, "someone", Instant.EPOCH, null),
                        new PointReview(null, null, null, null, null, null, null, null, null), 0, null, false, lockedBy,
                        Map.of())));
        MainApplication.getLayerManager().addLayer(mrLayer);
        Config.getPref().putInt("message." + EarlyUploadHook.PREF_CHECK_IF_FINISHED + ".value", 0);
        Config.getPref().putBoolean("message." + EarlyUploadHook.PREF_CHECK_IF_FINISHED, false);
        return apiDataSet;
    }

    /**
     * This checks to make certain that a lot of tasks do not go over the 255 character limit
     */
    @Test
    void testManyClosedTasks() {
        for (int i = 100_000_000; i < 100_000_050; i++) {
            ModifiedObjects.addUnlinkedFixed(new Task(i, "", null, null, 15318, null, null, null, null, null, null,
                    null, null, null, 0, null, null, null, false, null, null));
        }
        // JOSM calls checkUpload first, which caches the challenges that modifyChangesetTags reads
        assertTrue(hook.checkUpload(new APIDataSet(new DataSet())));
        final var map = new HashMap<String, String>();
        hook.modifyChangesetTags(map);
        assertEquals(4, map.size());
        assertAll("All values must be less than the max tag length",
                map.values().stream().map(s -> () -> assertTrue(s.length() < Tagged.MAX_TAG_LENGTH)));
        final var tasksString = map.get("maproulette:tasks") + ";" + map.get("maproulette:tasks:2");
        assertFalse(tasksString.startsWith(";"));
        assertFalse(tasksString.endsWith(";"));
        final var tasks = Stream.of(tasksString.split(";", -1)).mapToLong(Long::parseLong).toArray();
        final var sortedTasks = tasks.clone();
        Arrays.sort(sortedTasks);
        assertArrayEquals(sortedTasks, tasks);
        assertEquals(50, tasks.length);
    }

    /**
     * This checks to make certain we don't add maproulette tags when the user hasn't done anything yet
     */
    @Test
    void testNoChanges() {
        final var emptyMap = new HashMap<String, String>();
        hook.modifyChangesetTags(emptyMap);
        assertTrue(emptyMap.isEmpty());
    }
}