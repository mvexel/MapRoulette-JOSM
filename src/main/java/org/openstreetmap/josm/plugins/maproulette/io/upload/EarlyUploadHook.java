// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.io.upload;

import static org.openstreetmap.josm.plugins.maproulette.config.MapRouletteConfig.getBaseUrl;
import static org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel.getSelections;
import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.text.html.HTMLDocument;

import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.osm.IPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.SimplePrimitiveId;
import org.openstreetmap.josm.data.osm.Tagged;
import org.openstreetmap.josm.gui.ConditionalOptionPaneUtil;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.plugins.maproulette.api.TaskAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.api.model.ClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.data.TaskPrimitives;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.TagChangeTable;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskEdits;
import org.openstreetmap.josm.plugins.maproulette.gui.layer.MapRouletteClusteredPointLayer;
import org.openstreetmap.josm.plugins.maproulette.gui.task.current.CurrentTaskPanel;
import org.openstreetmap.josm.plugins.maproulette.io.TaskStatusSender;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;
import org.openstreetmap.josm.tools.bugreport.BugReport;

/**
 * An early upload hook for setting the changeset tags (for review by user)
 */
public final class EarlyUploadHook implements UploadHook {
    /**
     * The base pref key for asking the user if a task is finished
     */
    static final String PREF_CHECK_IF_FINISHED = "maproulette.check_if_finished";
    /**
     * The statuses of tasks that are not done yet, so that the user may have just finished them
     */
    private static final Set<TaskStatus> UNFINISHED = EnumSet.of(TaskStatus.CREATED, TaskStatus.SKIPPED,
            TaskStatus.TOO_HARD);
    private static final int MAX_LONG_STRING_LENGTH = Long.toString(Long.MAX_VALUE).length();
    /**
     * The base number predicate
     */
    private static final Predicate<String> NUMBER_PREDICATE = Pattern.compile("^\\d+$").asMatchPredicate();
    /**
     * The predicate for simple primitive ids
     */
    private static final Predicate<String> ID_PREDICATE = SimplePrimitiveId.ID_PATTERN.asMatchPredicate();
    /**
     * The pattern for ids with a version
     */
    private static final Pattern VERSION_PATTERN = Pattern
            .compile("(n|node|w|way|r|rel|relation)[ /]?(\\d+)[ v@]?(\\d+)");
    /**
     * The pattern for id_type_version
     */
    private static final Pattern ID_TYPE_VERSION_PATTERN = Pattern.compile("(\\d+)_(n|node|w|way|r|rel|relation)_\\d+");

    /**
     * The primitives of the upload in progress, captured in {@link #checkUpload(APIDataSet)}
     */
    private volatile List<OsmPrimitive> uploading = List.of();

    /**
     * JOSM calls this on the worker, and {@link #modifyChangesetTags(Map)} afterwards on the EDT
     */
    @Override
    public boolean checkUpload(APIDataSet apiDataSet) {
        this.uploading = List.copyOf(apiDataSet.getPrimitives());
        try {
            return realCheckUpload(apiDataSet);
        } catch (RuntimeException exception) {
            BugReport.intercept(exception).warn();
        } finally {
            cacheChallenges();
        }
        return true;
    }

    /**
     * Cache the challenges of the tasks marked Fixed, for the changeset tags
     */
    private static void cacheChallenges() {
        final var challenges = Stream
                .concat(ModifiedObjects.getUnlinkedFixed().stream(),
                        ModifiedObjects.getFinishing().stream().map(ModifiedTask::task))
                .mapToLong(Task::parentId).distinct().toArray();
        for (var challenge : challenges) {
            try {
                ChallengeCache.challenge(challenge);
            } catch (IOException ioException) {
                ExceptionDialogUtil.explainException(ioException);
            }
        }
    }

    private boolean realCheckUpload(APIDataSet apiDataSet) {
        // A finish recorded for an upload that did not happen (the user cancelled the upload dialog) is dropped
        ModifiedObjects.setFinishing(List.of());
        if (!MapRouletteAuth.isAvailable()) {
            // The answer could not be sent to MapRoulette
            return UploadHook.super.checkUpload(apiDataSet);
        }
        final var started = finishable();
        if (!started.isEmpty()) {
            // A started task is finished by the upload of its edits (#115); the old question is for uploads without one
            if (TaskEdits.touches(apiDataSet.getPrimitives())) {
                final List<ModifiedTask> finishing = GuiHelper.runInEDTAndWaitAndReturn(() -> askFinishes(started));
                ModifiedObjects.setFinishing(Objects.requireNonNull(finishing));
            }
            return UploadHook.super.checkUpload(apiDataSet);
        }
        final var ids = apiDataSet.getPrimitives().stream().map(IPrimitive::getPrimitiveId).collect(Collectors.toSet());
        //noinspection DataFlowIssue -- getPrimitiveId should stably return either a null value or a non-null value
        final var tasks = MainApplication.getLayerManager().getLayersOfType(MapRouletteClusteredPointLayer.class)
                .stream().map(MapRouletteClusteredPointLayer::getTasks).flatMap(Collection::stream)
                .filter(p -> getPrimitiveId(p) != null).collect(Collectors.groupingBy(EarlyUploadHook::getPrimitiveId));
        final var possiblyDone = ids.stream().filter(id -> tasks.containsKey(id.getUniqueId()))
                .collect(Collectors.toMap(PrimitiveId::getUniqueId, p -> p));
        final var exceptionList = new ArrayList<Exception>();
        final var possibleTasks = tasks.entrySet().stream().filter(t -> possiblyDone.containsKey(t.getKey()))
                .map(Map.Entry::getValue).flatMap(Collection::stream).mapToLong(TaskClusteredPoint::id)
                .filter(id -> ModifiedObjects.getModifiedTask(id) == null).mapToObj(id -> {
                    try {
                        return TaskAPI.get(id);
                    } catch (IOException e) {
                        Logging.trace(e);
                        exceptionList.add(e);
                    }
                    return null;
                }).filter(Objects::nonNull).filter(task -> UNFINISHED.contains(task.status()))
                .collect(Collectors.toSet());
        final var fixed = new ArrayList<ModifiedTask>();
        ConditionalOptionPaneUtil.startBulkOperation(PREF_CHECK_IF_FINISHED);
        for (var task : possibleTasks) {
            if (ids.containsAll(TaskPrimitives.getPrimitiveIds(task))) {
                // Look up the primitives here; the panel is built on the EDT, which must not wait for the server
                final var primitiveIdMap = TaskPrimitives.getPrimitiveIdMap(task);
                final var descriptivePanel = GuiHelper
                        .runInEDTAndWaitAndReturn(() -> createDescriptivePanel(task, apiDataSet, primitiveIdMap));
                Objects.requireNonNull(descriptivePanel);
                final var didFix = GuiHelper.runInEDTAndWaitAndReturn(
                        () -> ConditionalOptionPaneUtil.showConfirmationDialog(PREF_CHECK_IF_FINISHED,
                                MRGuiHelper.dialogParent(), descriptivePanel,
                                tr("Did you finish the following MapRoulette Task: {0}?", task.id()),
                                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                                JOptionPane.YES_OPTION));
                if (Boolean.TRUE.equals(didFix)) {
                    final var doc = (HTMLDocument) ((JMultilineLabel) descriptivePanel.getComponent(1)).getDocument();
                    final var modifiedTask = new ModifiedTask(task, TaskStatus.FIXED, null, null, null,
                            getSelections(doc));
                    ModifiedObjects.addModifiedTask(modifiedTask);
                    ModifiedObjects.addUnlinkedFixed(task);
                    fixed.add(modifiedTask);
                }
            }
        }
        // Statuses are sent at once (D15); this upload's changeset is linked to the task afterwards
        if (!fixed.isEmpty()) {
            TaskStatusSender.sendStatuses(fixed);
        }
        GuiHelper.runInEDT(() -> exceptionList.forEach(ExceptionDialogUtil::explainException));
        ConditionalOptionPaneUtil.endBulkOperation(PREF_CHECK_IF_FINISHED);
        return UploadHook.super.checkUpload(apiDataSet);
    }

    /**
     * Get the started tasks an upload can finish. A choice task can't be finished: MapRoulette only takes an answer for
     * it. A task whose status is being sent is done already.
     *
     * @return The tasks
     */
    static List<Task> finishable() {
        return ModifiedObjects.getLockedTasks().stream().filter(task -> !task.isChoiceTask())
                .filter(task -> ModifiedObjects.getModifiedTask(task.id()) == null
                        || ModifiedObjects.getModifiedTask(task.id()).status() == TaskStatus.CREATED)
                .toList();
    }

    /**
     * Ask if the upload finishes the started tasks. Call it on the EDT.
     *
     * @param started The started tasks
     * @return The statuses to send after the upload, empty if the tasks are not finished yet
     */
    private static List<ModifiedTask> askFinishes(List<Task> started) {
        final var panel = CurrentTaskPanel.get();
        final var statuses = started.stream()
                .map(task -> panel == null ? new ModifiedTask(task, TaskStatus.FIXED, null, null, null, Map.of())
                        : panel.feedback(task, TaskStatus.FIXED))
                .toList();
        return finishDialog(statuses).showDialog().getValue() == 1 ? statuses : List.of();
    }

    /**
     * Build the question whether the upload finishes the started tasks. Button 1 (the default) finishes them.
     *
     * @param statuses The statuses to send
     * @return The dialog, not shown yet
     */
    static ExtendedDialog finishDialog(List<ModifiedTask> statuses) {
        return new ExtendedDialog(MRGuiHelper.dialogParent(), tr("Finish MapRoulette task"),
                tr("Finished: I fixed it!"), tr("Not finished yet")).setContent(finishMessage(statuses))
                        .setButtonIcons("dialogs/validator", "cancel").setIcon(JOptionPane.QUESTION_MESSAGE)
                        .setDefaultButton(1).setCancelButton(2);
    }

    /**
     * Describe what finishing the tasks sends
     *
     * @param statuses The statuses to send
     * @return The text, as html
     */
    static String finishMessage(List<ModifiedTask> statuses) {
        final var ids = statuses.stream().map(status -> Long.toString(status.task().id()))
                .collect(Collectors.joining(", "));
        final var text = new StringBuilder("<html>")
                .append(trn("This upload finishes task {0}.", "This upload finishes tasks {0}.", statuses.size(), ids));
        final var comment = statuses.isEmpty() ? null : statuses.get(0).comment();
        text.append("<br>").append(Utils.isStripEmpty(comment) ? tr("No comment for MapRoulette.")
                : tr("Comment for MapRoulette: {0}", Utils.escapeReservedCharactersHTML(comment.strip())));
        if (!statuses.isEmpty() && Boolean.TRUE.equals(statuses.get(0).reviewRequested())) {
            text.append("<br>").append(tr("A review is requested."));
        }
        text.append("<br>").append(tr("Choose Not finished yet if the task needs more edits."));
        return text.append("</html>").toString();
    }

    private static Long getPrimitiveId(TaskClusteredPoint point) {
        final String toParse;
        if (point instanceof ClusteredPoint clusteredPoint) {
            toParse = clusteredPoint.title();
        } else if (point instanceof Task task) {
            toParse = task.name();
        } else {
            throw new IllegalArgumentException("Unknown class type: " + point.getClass());
        }
        if (toParse.length() <= MAX_LONG_STRING_LENGTH + 6 && NUMBER_PREDICATE.test(toParse)
                && toParse.endsWith("000000")) {
            try {
                final var challenge = ChallengeCache.challenge(point.parentId());
                if (challenge.general().checkinComment() != null
                        && challenge.general().checkinComment().contains("#AtlasCheck")) {
                    return Long.parseLong(toParse.substring(0, toParse.length() - 6));
                }
            } catch (IOException ioException) {
                ExceptionDialogUtil.explainException(ioException);
            }
        }
        if (toParse.length() <= MAX_LONG_STRING_LENGTH && NUMBER_PREDICATE.test(toParse)) {
            return Long.parseLong(toParse);
        } else if (ID_PREDICATE.test(toParse)) {
            return SimplePrimitiveId.fromString(toParse).getUniqueId();
        }
        final var versionMatcher = VERSION_PATTERN.matcher(toParse);
        if (versionMatcher.matches()) {
            return Long.parseLong(versionMatcher.group(2));
        }
        final var idTypeMatcher = ID_TYPE_VERSION_PATTERN.matcher(toParse);
        if (idTypeMatcher.matches()) {
            return Long.parseLong(idTypeMatcher.group(1));
        } else if (point instanceof Task task) {
            final var ids = TaskPrimitives.getPrimitiveIds(task);
            if (ids.size() == 1) {
                return ids.iterator().next().getUniqueId();
            }
        }
        return null;
    }

    private static JComponent createDescriptivePanel(Task task, APIDataSet dataSet, Map<PrimitiveId, IPrimitive> map) {
        final var panel = new JPanel(new GridBagLayout());
        final var instructionPane = new JMultilineLabel(MRGuiHelper.getInstructionText(task));
        panel.add(new JLabel(tr("Instructions:")), GBC.eol().fill(GridBagConstraints.HORIZONTAL));
        panel.add(instructionPane, GBC.eol().fill(GridBagConstraints.HORIZONTAL));
        for (var taskPrimitive : task.geometries().allPrimitives()) {
            if (taskPrimitive.isTagged()) {
                final var id = map.entrySet().stream().filter(entry -> entry.getValue() == taskPrimitive)
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                final var osmPrimitive = id == null ? null
                        : dataSet.getPrimitives().stream()
                                .filter(p -> id.getType() == p.getType() && id.getUniqueId() == p.getUniqueId())
                                .findFirst().orElse(null);
                if (id != null && osmPrimitive != null) {
                    final var table = new TagChangeTable(false);
                    final List<String> keys = Stream
                            .concat(osmPrimitive.keys(), MRGuiHelper.filterKeys(taskPrimitive.keys())).sorted()
                            .distinct().toList();
                    for (var row = 0; row < keys.size(); row++) {
                        final var key = keys.get(row);
                        table.setValueAt(key, row, 0);
                        table.setValueAt(taskPrimitive.get(key), row, 1);
                        table.setValueAt(osmPrimitive.get(key), row, 2);
                    }
                    panel.add(new JSeparator(), GBC.eol().fill(GridBagConstraints.HORIZONTAL));
                    panel.add(new JLabel(tr("Changes for {0}", id)), GBC.eol().fill(GridBagConstraints.HORIZONTAL));
                    panel.add(new JScrollPane(table), GBC.eol().fill(GridBagConstraints.HORIZONTAL));
                }
            }
        }
        return panel;
    }

    @Override
    public void modifyChangesetTags(Map<String, String> tags) {
        UploadHook.super.modifyChangesetTags(tags);
        final var tagBuilders = new ArrayList<StringBuilder>();
        final var changesetComments = new TreeSet<String>();
        final var sourceComments = new TreeSet<String>();
        var tagBuilder = new StringBuilder();
        tagBuilders.add(tagBuilder);
        // Only the tasks this upload is for; the challenges were cached in checkUpload
        final var uploaded = this.uploading;
        // The tasks this upload finishes, and the tasks marked Fixed whose objects it contains
        final var forUpload = Stream
                .concat(ModifiedObjects.getFinishing().stream().map(ModifiedTask::task),
                        ModifiedObjects.getUnlinkedFixed().stream()
                                .filter(task -> TaskPrimitives.cachedPrimitiveIds(task)
                                        .map(ids -> LateUploadHook.inUpload(ids, uploaded)).orElse(Boolean.TRUE)))
                .collect(Collectors.toMap(Task::id, task -> task, (a, b) -> a, TreeMap::new)).values();
        for (Task task : forUpload) {
            final var id = Long.toString(task.id());
            if (tagBuilder.length() + id.length() + 1 >= Tagged.MAX_TAG_LENGTH) {
                tagBuilder = new StringBuilder();
                tagBuilders.add(tagBuilder);
            }
            if (!tagBuilder.isEmpty()) {
                tagBuilder.append(';');
            }
            tagBuilder.append(task.id());
            // Called on the EDT; checkUpload cached the challenges (and explained any failure)
            ChallengeCache.cached(task.parentId()).map(Challenge::general).ifPresent(challengeGeneral -> {
                if (!Utils.isStripEmpty(challengeGeneral.checkinComment())) {
                    changesetComments.add(challengeGeneral.checkinComment());
                }
                if (!Utils.isStripEmpty(challengeGeneral.checkinSource())) {
                    sourceComments.add(challengeGeneral.checkinSource());
                }
            });
        }

        if (!changesetComments.isEmpty()) {
            tags.put("comment", changesetComments.stream().sorted().collect(Collectors.joining("; ")));
        }
        if (!sourceComments.isEmpty()) {
            tags.put("source", sourceComments.stream().sorted().collect(Collectors.joining("; ")));
        }
        final var taskStrings = tagBuilders.stream().map(StringBuilder::toString).filter(str -> !str.isBlank())
                .toList();
        if (!taskStrings.isEmpty()) {
            tags.put("maproulette:tasks", taskStrings.get(0));
            tags.put("maproulette:server", getBaseUrl());
            for (var i = 1; i < taskStrings.size(); i++) {
                tags.put("maproulette:tasks:" + (i + 1), taskStrings.get(i));
            }
        }
    }

}
