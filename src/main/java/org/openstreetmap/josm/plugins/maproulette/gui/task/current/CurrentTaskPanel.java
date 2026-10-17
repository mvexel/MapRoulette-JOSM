// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.Option;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.OsmDataManager;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DefaultNameFormatter;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.PrimitiveId;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.event.SelectionEventManager;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.tagging.ac.AutoCompComboBox;
import org.openstreetmap.josm.gui.widgets.HtmlPanel;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.gui.widgets.JosmTextField;
import org.openstreetmap.josm.gui.widgets.VerticallyScrollablePanel;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteUnsupportedException.Reason;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.data.TaskPrimitives;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteDialog;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.TagChangeTable;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskData;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskEdits;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.plugins.maproulette.io.TaskStatusSender;
import org.openstreetmap.josm.plugins.maproulette.io.upload.LateUploadHook;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.tools.Utils;

import jakarta.annotation.Nullable;

/**
 * The current task section of the MapRoulette panel, for the task the user started
 */
public final class CurrentTaskPanel extends JPanel {
    /**
     * The serial UID for this panel
     */
    @Serial
    private static final long serialVersionUID = 6628674700523633284L;
    private static final String FINISH_CARD = "finish";
    /**
     * The review status code of a rejected task
     */
    private static final int REJECTED = 2;
    private static final String COOPERATIVE_CARD = "cooperative";
    /**
     * The model for the task
     */
    private final VerticallyScrollablePanel panel = new VerticallyScrollablePanel(new GridBagLayout());
    /**
     * The name of the challenge of the task
     */
    private final JLabel challengeLabel = new JLabel();
    /**
     * The name of the task
     */
    private final JLabel nameLabel = new JLabel();
    /**
     * The label for the task id
     */
    private final JLabel idLabel = new JLabel();
    /**
     * Opens the task on the MapRoulette website
     */
    private final OpenOnMapRouletteAction openAction = new OpenOnMapRouletteAction(() -> this.task);
    private final JButton openButton = new JButton(this.openAction);
    /**
     * The panel for instructions
     */
    private final HtmlPanel instructionPane = new HtmlPanel();
    /**
     * The panel for cooperative work
     */
    private final JPanel cooperativeWork = new JPanel();
    /**
     * The actions for the task
     */
    private final InnerAction[] actions;
    /**
     * Shows the finish button, or the Fixed button of a cooperative task, which applies its suggested change
     */
    private final JPanel finishCards = new JPanel(new CardLayout());
    /**
     * The number of edits for the started task
     */
    private final JLabel editsLabel = new JLabel();
    /**
     * The comment sent with the status (#115)
     */
    private final JosmTextField commentField = new JosmTextField();
    /**
     * The task tags sent with the status, shown when the challenge suggests some
     */
    private final AutoCompComboBox<String> tagBox = new AutoCompComboBox<>();
    /**
     * Asks for a review of the task
     */
    private final JCheckBox reviewBox = new JCheckBox(tr("Ask for a review"));
    /**
     * Shown when the upload succeeded but Fixed could not be sent
     */
    private final JPanel unfinishedPanel = new JPanel(new GridBagLayout());
    private final JLabel unfinishedLabel = new JLabel();
    /**
     * Shown when the task's objects are not in the edit layer, with a button to download them (#114)
     */
    private final JPanel dataPanel = new JPanel(new GridBagLayout());
    /**
     * Updates the header and the missing data notice when the selection changes
     */
    private final transient DataSelectionListener selectionListener = e -> this.refreshDataState();
    /**
     * Updates the edit count, the finish button and the header. Edits include downloads into the edit layer.
     */
    private final Runnable editsListener = () -> {
        this.refreshFinish();
        this.refreshDataState();
    };
    /**
     * Shows a task whose Fixed could not be sent, and updates the actions once a status is sent
     */
    private final transient Consumer<Map<Long, Task>> sentListener = tasks -> {
        this.refreshActions();
        this.refreshFinish();
    };
    /**
     * The task the comment and the review request were filled in for
     */
    private long feedbackTask = -1;
    /**
     * Re-evaluates the actions when the MapRoulette login changes
     */
    private final Runnable authListener = this::refreshActions;
    /**
     * Shows the instructions, and updates the actions, once the challenge of the task arrives in the background
     */
    private final Runnable challengeListener = () -> {
        if (this.instructionPending || this.task != null) {
            this.refreshPanel();
        }
        this.refreshActions();
    };
    /**
     * The current task
     */
    private Task task;
    /**
     * {@code true} if the instructions are waiting for the challenge to arrive
     */
    private boolean instructionPending;

    /**
     * Create a new task panel
     */
    public CurrentTaskPanel() {
        super(new BorderLayout());

        final Supplier<Task> supplier = () -> this.task;
        final Supplier<HTMLDocument> docSupplier = () -> (HTMLDocument) this.instructionPane.getEditorPane()
                .getDocument();
        // The edit path has no status button: the upload finishes the task (#115). These are the outcomes with no edit.
        final var newActions = new InnerAction[] {
                new TaskStatusAction(TaskStatus.ALREADY_FIXED, supplier, docSupplier),
                new TaskStatusAction(TaskStatus.FALSE_POSITIVE, supplier, docSupplier),
                new TaskStatusAction(TaskStatus.TOO_HARD, supplier, docSupplier),
                new TaskStatusAction(TaskStatus.SKIPPED, supplier, docSupplier),
                new TaskStatusAction(TaskStatus.FIXED, supplier, docSupplier), new SelectOsmPrimitives(supplier),
                new FinishAction(this) };
        this.actions = newActions;
        this.instructionPane.enableClickableHyperlinks();
        // The header stays in place while the instructions scroll
        final var header = new JPanel(new GridBagLayout());
        // The task name is what people look for; the challenge above it is context
        final var font = this.nameLabel.getFont();
        this.challengeLabel.setFont(font.deriveFont(font.getSize2D() * 0.9f));
        this.challengeLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        this.nameLabel.setFont(font.deriveFont(Font.BOLD, font.getSize2D() * 1.2f));
        header.add(this.challengeLabel, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 2, 4, 0));
        header.add(this.nameLabel, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 0));
        header.add(this.idLabel, GBC.std().fill(GBC.HORIZONTAL).insets(4, 0, 4, 2));
        header.add(this.openButton, GBC.eol().anchor(GBC.LINE_END).insets(0, 0, 4, 2));
        this.add(header, BorderLayout.NORTH);
        final var gbc = GBC.eol();
        this.panel.add(new JLabel(tr("Instructions: ")), gbc);
        gbc.fill(GridBagConstraints.BOTH);
        this.panel.add(this.instructionPane, gbc);
        this.panel.add(this.cooperativeWork, gbc);
        this.add(this.panel.getVerticalScrollPane(), BorderLayout.CENTER);
        this.commentField.setHint(tr("Comment for MapRoulette (optional)"));
        this.commentField.setToolTipText(tr("Sent with the task''s status"));
        this.tagBox.setToolTipText(tr("Task tags for MapRoulette (optional)"));
        this.unfinishedLabel
                .setForeground(UIManager.getColor("Component.warningForeground") == null ? Color.RED.darker()
                        : UIManager.getColor("Component.warningForeground"));
        this.unfinishedPanel.add(this.unfinishedLabel, GBC.std().fill(GBC.HORIZONTAL));
        final var retry = new JButton(tr("Send again"));
        retry.addActionListener(
                e -> Optional.ofNullable(this.task).map(Task::id).ifPresent(LateUploadHook::retryFinish));
        this.unfinishedPanel.add(retry, GBC.eol().anchor(GBC.LINE_END));
        this.dataPanel.add(new JLabel(tr("The task''s objects are not in the data.")), GBC.std().fill(GBC.HORIZONTAL));
        final var download = new JButton(tr("Download"));
        download.setToolTipText(tr("Download the task''s objects from OpenStreetMap and select them"));
        download.addActionListener(e -> Optional.ofNullable(this.task).ifPresent(TaskData::loadAndSelect));
        this.dataPanel.add(download, GBC.eol().anchor(GBC.LINE_END));
        final var south = new JPanel(new GridBagLayout());
        south.add(this.dataPanel, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        south.add(this.unfinishedPanel, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        south.add(this.editsLabel, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        south.add(this.commentField, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 0));
        south.add(this.tagBox, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 0));
        south.add(this.reviewBox, GBC.eol().anchor(GBC.LINE_START).insets(2, 0, 4, 0));
        // Uploading is the main way to finish; the outcomes with no edit come after it
        final var finishButton = new SideButton(newActions[6]);
        finishButton.setFont(finishButton.getFont().deriveFont(Font.BOLD));
        this.finishCards.add(finishButton, FINISH_CARD);
        this.finishCards.add(new SideButton(newActions[4]), COOPERATIVE_CARD);
        south.add(this.finishCards, GBC.eol().fill(GBC.HORIZONTAL));
        // Wraps in a narrow panel
        final var uploadHint = new JMultilineLabel("<html>"
                + Utils.escapeReservedCharactersHTML(
                        tr("Edited the map? Upload here or with File > Upload data, and the task is finished."))
                + "</html>");
        uploadHint.setFont(font.deriveFont(font.getSize2D() * 0.9f));
        uploadHint.setForeground(UIManager.getColor("Label.disabledForeground"));
        south.add(uploadHint, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 4));
        final var noEdit = new JLabel(tr("Nothing to edit?"));
        noEdit.setFont(font.deriveFont(Font.BOLD));
        south.add(noEdit, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 0));
        south.add(
                MRGuiHelper.buttonRows(List.of(
                        List.of(new SideButton(newActions[0]), new SideButton(newActions[1]),
                                new SideButton(newActions[2])),
                        List.of(new SideButton(newActions[3]), new SideButton(newActions[5])))),
                GBC.eol().fill(GBC.HORIZONTAL));
        this.add(south, BorderLayout.SOUTH);
        MapRouletteClient.getInstance().addAuthListener(this.authListener);
        TaskEdits.addListener(this.editsListener);
        ModifiedObjects.addLockListener(this.editsListener);
        TaskStatusSender.addListener(this.sentListener);
        ChallengeCache.addListener(this.challengeListener);
        SelectionEventManager.getInstance().addSelectionListenerForEdt(this.selectionListener);
        this.refreshPanel();
    }

    /**
     * Get the current task section of the MapRoulette panel
     *
     * @return The section, or {@code null} if there is no map
     */
    @Nullable
    public static CurrentTaskPanel get() {
        final var dialog = MapRouletteDialog.get();
        return dialog == null ? null : dialog.currentTask();
    }

    /**
     * Remove the listeners and actions of the section. The MapRoulette panel calls this when it is destroyed.
     */
    public void destroy() {
        // The side buttons destroy their own actions, once
        GuiHelper.destroyComponents(this, false);
        this.openAction.destroy();
        MapRouletteClient.getInstance().removeAuthListener(this.authListener);
        TaskEdits.removeListener(this.editsListener);
        ModifiedObjects.removeLockListener(this.editsListener);
        TaskStatusSender.removeListener(this.sentListener);
        ChallengeCache.removeListener(this.challengeListener);
        SelectionEventManager.getInstance().removeSelectionListener(this.selectionListener);
    }

    /**
     * Update the header and the missing data notice of the panel, for example after the task's data was loaded. Call
     * on the EDT.
     */
    public static void refreshData() {
        final var panel = get();
        if (panel != null) {
            panel.refreshDataState();
        }
    }

    /**
     * Show whether the task's objects are in the edit layer, and which are selected
     */
    private void refreshDataState() {
        final var currentTask = this.task;
        if (currentTask == null) {
            this.dataPanel.setVisible(false);
            return;
        }
        final List<PrimitiveId> ids = TaskPrimitives.cachedPrimitiveIds(currentTask).map(List::copyOf)
                .orElse(List.of());
        final var dataSet = MainApplication.getLayerManager().getEditDataSet();
        this.dataPanel.setVisible(!TaskData.missing(ids, dataSet).isEmpty());
        final var started = showsLockedTask();
        final var selection = started ? selection(ids, dataSet) : null;
        // The selection goes on a line of its own, so the narrow panel doesn't cut it off
        this.idLabel.setText(selection == null ? header(currentTask, started)
                : "<html>" + Utils.escapeReservedCharactersHTML(header(currentTask, true)) + "<br>"
                        + Utils.escapeReservedCharactersHTML(selection) + "</html>");
    }

    /**
     * Get the header line of a task
     *
     * @param task    The task
     * @param started {@code true} if the user started the task
     * @return "Working on task 27" once the task is started, "Task 27" otherwise
     */
    static String header(Task task, boolean started) {
        return started ? tr("Working on task {0}", task.id()) : tr("Task {0}", task.id());
    }

    /**
     * Describe the selected objects of a task
     *
     * @param ids     The task's objects
     * @param dataSet The edit data set, or {@code null}
     * @return Such as "Bench selected", or {@code null} if none of them is selected
     */
    @Nullable
    static String selection(List<PrimitiveId> ids, @Nullable DataSet dataSet) {
        final List<OsmPrimitive> selected = dataSet == null ? List.of()
                : ids.stream().map(dataSet::getPrimitiveById).filter(p -> p != null && p.isSelected()).toList();
        if (selected.isEmpty()) {
            return null;
        } else if (selected.size() == 1) {
            return tr("{0} selected", DefaultNameFormatter.getInstance().format(selected.get(0)));
        }
        return trn("{0} object selected", "{0} objects selected", selected.size(), selected.size());
    }

    private void refreshActions() {
        for (var action : this.actions) {
            action.updateEnabledState();
        }
    }

    /**
     * Update the internal model
     *
     * @param task The task to show
     */
    public void refreshModel(final Task task) {
        if (this.task != null) {
            final var selectedResponses = getSelections(
                    (HTMLDocument) this.instructionPane.getEditorPane().getDocument());
            if (!selectedResponses.isEmpty()) {
                final var modifiedTask = ModifiedObjects.getModifiedTask(this.task.id());
                final ModifiedTask newModifiedTask;
                if (modifiedTask == null) {
                    newModifiedTask = new ModifiedTask(this.task, this.task.status(), null, null, null,
                            selectedResponses);
                } else {
                    newModifiedTask = new ModifiedTask(modifiedTask.task(), modifiedTask.status(),
                            modifiedTask.comment(), modifiedTask.tags(), modifiedTask.reviewRequested(),
                            selectedResponses);
                    ModifiedObjects.removeModifiedTask(modifiedTask);
                }
                ModifiedObjects.addModifiedTask(newModifiedTask);
            }
        }
        this.task = task;
        if (task != null) {
            final var dialog = MapRouletteDialog.get();
            if (dialog != null) {
                dialog.showTask();
            }
        }
        this.refreshPanel();
    }

    /**
     * Show a task in the MapRoulette panel, adding the panel if the map has none yet
     *
     * @param task  The task to show
     * @param focus {@code true} to also expand the panel, for example after the user started the task
     */
    public static void show(Task task, boolean focus) {
        final var map = MainApplication.getMap();
        if (map == null) {
            return;
        }
        var dialog = MapRouletteDialog.get();
        if (dialog == null) {
            dialog = new MapRouletteDialog();
            map.addToggleDialog(dialog);
        }
        dialog.currentTask().refreshModel(task);
        if (focus) {
            dialog.unfurlDialog();
        }
    }

    /**
     * Check if the panel shows a task the user still has locked
     *
     * @return {@code true} if the shown task is still locked
     */
    public boolean showsLockedTask() {
        return this.task != null && ModifiedObjects.getLockedTask(this.task.id()) != null;
    }

    /**
     * Get what to send with a status: the comment, the task tags and the review request from the panel, and the
     * answers picked in the instructions
     *
     * @param task   The task
     * @param status The status to send
     * @return The status with its feedback
     */
    public ModifiedTask feedback(Task task, TaskStatus status) {
        final var comment = Utils.strip(this.commentField.getText());
        // A skip sets no status, so there is nothing to tag or review
        final var skip = status == TaskStatus.SKIPPED;
        final var tags = skip || !this.tagBox.isVisible() ? "" : Utils.strip(this.tagBox.getText());
        return new ModifiedTask(task, status, comment.isEmpty() ? null : comment, tags.isEmpty() ? null : tags,
                reviewRequest(task, status, this.reviewBox.isSelected()),
                getSelections((HTMLDocument) this.instructionPane.getEditorPane().getDocument()));
    }

    /**
     * Get the review request to send with a status. A task whose review was rejected always goes back for review, as on
     * the website: without the request, MapRoulette uses the user's review setting, and the reviewer may never hear of
     * the revision.
     *
     * @param task   The task
     * @param status The status to send
     * @param asked  {@code true} if the user asked for a review
     * @return {@code true} to request a review, {@code null} to leave it to MapRoulette
     */
    @Nullable
    public static Boolean reviewRequest(Task task, TaskStatus status, boolean asked) {
        // A skip sets no status, so there is nothing to review
        return status != TaskStatus.SKIPPED && (asked || isInRevision(task)) ? Boolean.TRUE : null;
    }

    /**
     * Check if a task's review was rejected, so the user is revising it
     *
     * @param task The task
     * @return {@code true} if the task is in revision
     */
    public static boolean isInRevision(@Nullable Task task) {
        return task != null && task.review() != null && task.review().reviewStatus() != null
                && task.review().reviewStatus() == REJECTED;
    }

    /**
     * Show the edit count, a Fixed that could not be sent, and whether the upload can finish the task
     */
    private void refreshFinish() {
        final var currentTask = this.task;
        final var started = currentTask != null && showsLockedTask() && !currentTask.isChoiceTask();
        final var edits = TaskEdits.count();
        this.editsLabel.setVisible(started);
        if (started) {
            this.editsLabel.setText(edits == 0 ? tr("No edits yet for task {0}", currentTask.id())
                    : trn("{0} edit for task {1}", "{0} edits for task {1}", edits, edits, currentTask.id()));
        }
        final var unfinished = currentTask != null && LateUploadHook.isUnfinished(currentTask.id());
        this.unfinishedPanel.setVisible(unfinished);
        if (unfinished) {
            this.unfinishedLabel
                    .setText(tr("Your edits are uploaded, but Fixed was not sent for task {0}.", currentTask.id()));
        }
        ((CardLayout) this.finishCards.getLayout()).show(this.finishCards,
                currentTask != null && currentTask.cooperativeWork() != null && !currentTask.isChoiceTask()
                        ? COOPERATIVE_CARD
                        : FINISH_CARD);
        // A pending finish disables the status buttons
        this.refreshActions();
    }

    /**
     * Empty the comment, the tags and the review request when another task is shown
     *
     * @param currentTask The task shown
     */
    private void resetFeedback(@Nullable Task currentTask) {
        final var id = currentTask == null ? -1 : currentTask.id();
        if (id == this.feedbackTask) {
            return;
        }
        this.feedbackTask = id;
        this.commentField.setText("");
        this.reviewBox.setSelected(false);
        this.tagBox.getModel().removeAllElements();
        this.tagBox.setText("");
        final var challenge = currentTask == null ? null : ChallengeCache.cached(currentTask.parentId()).orElse(null);
        updateTags(challenge);
    }

    private void updateTags(@Nullable Challenge challenge) {
        final var preferred = challenge == null ? null : challenge.extra().preferredTags();
        this.tagBox.setVisible(!Utils.isStripEmpty(preferred));
        if (this.tagBox.isVisible() && this.tagBox.getModel().getSize() == 0) {
            this.tagBox.getModel().addAllElements(
                    Arrays.stream(preferred.split(",")).map(String::strip).filter(tag -> !tag.isEmpty()).toList());
            this.tagBox.setEditable(!challenge.extra().limitTags());
        }
    }

    /**
     * Get the actions for the panel
     * @return The actions
     */
    InnerAction[] actions() {
        return actions;
    }

    private void refreshPanel() {
        final var currentTask = this.task;
        this.panel.setBackground(UIManager.getColor("Panel.background"));
        for (var action : actions) {
            action.updateEnabledState();
        }
        this.openAction.updateEnabledState();
        resetFeedback(currentTask);
        if (currentTask != null) {
            updateTags(ChallengeCache.cached(currentTask.parentId()).orElse(null));
        }
        final var hasTask = currentTask != null;
        this.commentField.setVisible(hasTask && !currentTask.isChoiceTask());
        this.reviewBox.setVisible(hasTask && !currentTask.isChoiceTask());
        // A rejected task always goes back for review
        final var revision = isInRevision(currentTask);
        this.reviewBox.setEnabled(!revision);
        if (revision) {
            this.reviewBox.setSelected(true);
        }
        this.reviewBox.setToolTipText(revision ? tr("The reviewer rejected this task, so your fix goes back for review")
                : tr("Ask a reviewer to check your work"));
        this.tagBox.setVisible(this.tagBox.isVisible() && hasTask);
        refreshFinish();
        if (currentTask == null) {
            this.dataPanel.setVisible(false);
            this.instructionPending = false;
            this.challengeLabel.setText(null);
            this.nameLabel.setText(tr("No current task"));
            this.idLabel.setText(null);
            this.openButton.setVisible(false);
            this.instructionPane.setText(tr("Start a task from the list to work on it here."));
            this.cooperativeWork.setVisible(false);
            return;
        }
        this.challengeLabel.setText(ChallengeCache.cached(currentTask.parentId()).map(Challenge::name)
                .orElseGet(() -> tr("Challenge {0}", currentTask.parentId())));
        this.nameLabel.setText(currentTask.name());
        this.openButton.setVisible(true);
        refreshDataState();

        // Checked before building the text, so a challenge arriving in between still triggers a refresh
        this.instructionPending = Utils.isStripEmpty(currentTask.instruction())
                && ChallengeCache.cached(currentTask.parentId()).isEmpty();
        this.instructionPane.setText(MRGuiHelper.getInstructionText(currentTask));
        updateSelections((HTMLDocument) this.instructionPane.getEditorPane().getDocument(), this.task);
        if (currentTask.isCooperativeWorkOsmChange()) {
            final var cooperativePanel = this.cooperativeWork;
            cooperativePanel.removeAll();
            cooperativePanel.setVisible(true);
            cooperativePanel.setLayout(new GridBagLayout());
            final var taskCooperativeWork = Objects.requireNonNull(currentTask.cooperativeWorkAsOsmChange());
            if (taskCooperativeWork.creates() != null && taskCooperativeWork.creates().length > 0) {
                cooperativePanel.add(new JLabel(tr("Creates")), GBC.eol());
                throw new IllegalArgumentException("Haven't figured out what to do with creates");
            }
            if (taskCooperativeWork.updates() != null && taskCooperativeWork.updates().length > 0
                    && OsmDataManager.getInstance().getEditDataSet() != null) {
                cooperativePanel.add(new JLabel(tr("Tag Updates")), GBC.eol());
                final var table = new TagChangeTable();
                cooperativePanel.add(table.getTableHeader(), GBC.eol().fill(GridBagConstraints.HORIZONTAL));
                cooperativePanel.add(table, GBC.eol().fill(GridBagConstraints.HORIZONTAL));
                for (var updates : taskCooperativeWork.updates()) {
                    var row = 0;
                    final var current = OsmDataManager.getInstance().getEditDataSet().getPrimitiveById(updates.osmId(),
                            updates.osmType());
                    for (var change : updates.tags().updates().entrySet()) {
                        final var old = current == null ? null : current.get(change.getKey());
                        table.setValueAt(change.getKey(), row, 0);
                        table.setValueAt(old, row, 1);
                        table.setValueAt(change.getValue(), row++, 2);
                    }
                    for (var change : updates.tags().deletes()) {
                        table.setValueAt(change, row, 0);
                        table.setValueAt(current == null ? null : current.get(change), row++, 1);
                    }
                }
            } else {
                this.cooperativeWork.setVisible(false);
            }
        }
    }

    /**
     * Update the selections in a doc from the stored selections
     * @param doc The doc to update
     * @param task The task to get the selections for
     */
    private static void updateSelections(HTMLDocument doc, Task task) {
        final var modifiedTask = ModifiedObjects.getModifiedTask(task.id());
        if (modifiedTask != null && modifiedTask.completionResponses() != null) {
            final var selectIterator = doc.getIterator(HTML.Tag.SELECT);
            final var selectListener = new SelectComboBoxListener(doc, task);
            while (selectIterator.isValid()) {
                final var attribs = selectIterator.getAttributes();
                if (attribs.getAttribute(HTML.Attribute.NAME) != null) {
                    final var name = (String) attribs.getAttribute(HTML.Attribute.NAME);
                    if (modifiedTask.completionResponses().get(name) != null && attribs
                            .getAttribute(StyleConstants.ModelAttribute)instanceof DefaultComboBoxModel<?> listModel) {
                        listModel.addListDataListener(selectListener);
                        final var expectedOption = modifiedTask.completionResponses().get(name);
                        for (var i = 0; i < listModel.getSize(); i++) {
                            final var currentOption = (Option) listModel.getElementAt(i);
                            if (Objects.equals(currentOption.getValue(), expectedOption.getValue())) {
                                listModel.setSelectedItem(currentOption);
                                break;
                            }
                        }
                    }
                }
                selectIterator.next();
            }
        }
    }

    /**
     * Get the selections from the comboboxes of a document
     * @param doc The document to parse
     * @return The selected options
     */
    public static Map<String, Option> getSelections(HTMLDocument doc) {
        final var selectionMap = new TreeMap<String, Option>();
        final var selectIterator = doc.getIterator(HTML.Tag.SELECT);
        while (selectIterator.isValid()) {
            final var attribs = selectIterator.getAttributes();
            if (attribs.getAttribute(HTML.Attribute.NAME) != null) {
                final var name = (String) attribs.getAttribute(HTML.Attribute.NAME);
                if (attribs.getAttribute(StyleConstants.ModelAttribute)instanceof DefaultComboBoxModel<?> listModel) {
                    final var option = (Option) listModel.getSelectedItem();
                    if (!Utils.isStripEmpty(option.getValue())) {
                        selectionMap.put(name, option);
                    }
                }
            }
            selectIterator.next();
        }
        return Collections.unmodifiableMap(selectionMap);
    }

    /**
     * A listener for select combo boxes from a {@link HTMLDocument}
     * @param doc The document to use to update a task from
     * @param task The originating task
     */
    private record SelectComboBoxListener(HTMLDocument doc, Task task) implements ListDataListener {

    @Override
    public void intervalAdded(ListDataEvent e) {
        updateModifiedTask();
    }

    @Override
    public void intervalRemoved(ListDataEvent e) {
        updateModifiedTask();
    }

    @Override
    public void contentsChanged(ListDataEvent e) {
        updateModifiedTask();
    }

    private void updateModifiedTask() {
        final var originalModifiedTask = ModifiedObjects.getModifiedTask(this.task.id());
        if (originalModifiedTask != null) {
            final var modifiedTask = new ModifiedTask(originalModifiedTask.task(), originalModifiedTask.status(),
                    originalModifiedTask.comment(), originalModifiedTask.tags(), originalModifiedTask.reviewRequested(),
                    getSelections(doc));
            ModifiedObjects.removeModifiedTask(originalModifiedTask);
            ModifiedObjects.addModifiedTask(modifiedTask);
        }
    }
}

/**
 * Open the task on the MapRoulette website
 */
private static final class OpenOnMapRouletteAction extends JosmAction {
    @Serial
    private static final long serialVersionUID = 3071722190416473338L;
    private final transient Supplier<Task> taskSupplier;

    OpenOnMapRouletteAction(Supplier<Task> taskSupplier) {
        super(tr("Open on MapRoulette"), "presets/misc/contact", tr("Open this task on the MapRoulette website"),
                Shortcut.registerShortcut("maproulette:open_current_task_in_browser",
                        tr("MapRoulette: Open the current task on MapRoulette"), KeyEvent.CHAR_UNDEFINED,
                        Shortcut.NONE),
                false, false);
        this.taskSupplier = taskSupplier;
        updateEnabledState();
    }

    private Optional<String> url() {
        final var task = this.taskSupplier == null ? null : this.taskSupplier.get();
        return task == null ? Optional.empty() : MapRouletteServerPreference.getTaskUrl(task.parentId(), task.id());
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        url().ifPresent(OpenBrowser::displayUrl);
    }

    @Override
    protected void updateEnabledState() {
        final var hasWebsite = MapRouletteServerPreference.getWebsiteUrl().isPresent();
        this.setEnabled(url().isPresent());
        this.setTooltip(hasWebsite ? tr("Open this task on the MapRoulette website")
                : tr("Set the MapRoulette website URL in the MapRoulette server preferences to open tasks there"));
    }
}

/**
 * An action purely for making it easier to call {@link JosmAction#updateEnabledState()}.
 */
abstract static class InnerAction extends JosmAction {
    /**
     * Serial UID for this action
     */
    @Serial
    private static final long serialVersionUID = -6428448152026023173L;

    /**
     * Constructs a new {@code JosmAction} and installs layer changed and selection changed adapters.
     * <br>
     * Use this super constructor to setup your action.
     *
     * @param name              the action's text as displayed on the menu (if it is added to a menu)
     * @param iconName          the filename of the icon to use
     * @param tooltip           a longer description of the action that will be displayed in the tooltip. Please note
     *                          that html is not supported for menu actions on some platforms.
     * @param shortcut          a ready-created shortcut object or null if you don't want a shortcut. But you always
     *                          do want a shortcut, remember you can always register it with group=none, so you
     *                          won't be assigned a shortcut unless the user configures one. If you pass null here,
     *                          the user CANNOT configure a shortcut for your action.
     * @param registerInToolbar register this action for the toolbar preferences?
     */
    protected InnerAction(String name, String iconName, String tooltip, Shortcut shortcut, boolean registerInToolbar) {
        super(name, iconName, tooltip, shortcut, registerInToolbar, true);
        putValue("help", ht("/Dialog/MapRouletteCurrentTask"));
    }

    @Override
    public void updateEnabledState() {
        super.updateEnabledState();
    }
}

/**
 * Upload the edits, which finishes the started task (#115)
 */
private static final class FinishAction extends InnerAction {
    @Serial
    private static final long serialVersionUID = 2958043920482017623L;
    private final transient CurrentTaskPanel panel;

    FinishAction(CurrentTaskPanel panel) {
        super(tr("Upload edits and finish…"), "upload",
                tr("Upload your edits to OpenStreetMap, which finishes the task"),
                Shortcut.registerShortcut("maproulette:upload_and_finish", tr("MapRoulette: Upload edits and finish"),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false);
        this.panel = panel;
        putValue("help", ht("/Dialog/MapRouletteCurrentTask#Finish"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (MainApplication.getMenu() != null) {
            // JOSM's own upload: the upload hook asks if it finishes the task
            MainApplication.getMenu().upload.actionPerformed(e);
        }
    }

    @Override
    public void updateEnabledState() {
        if (this.panel == null) { // Only during the constructor
            return;
        }
        final var task = this.panel.task;
        if (task == null || !this.panel.showsLockedTask()) {
            this.setEnabled(false);
            this.setTooltip(tr("Start a task to work on it"));
        } else if (task.isChoiceTask()) {
            this.setEnabled(false);
            this.setTooltip(ExceptionDialogUtil.explainRefusal(Reason.CHOICE_TASK_STATUS));
        } else if (!MapRouletteAuth.isAvailable()) {
            this.setEnabled(false);
            this.setTooltip(MapRouletteAuth.unavailableTooltip());
        } else if (TaskEdits.count() == 0) {
            this.setEnabled(false);
            this.setTooltip(tr("Edit the map for this task first. Uploading the edits finishes the task."));
        } else {
            this.setEnabled(true);
            this.setTooltip(tr("Upload your edits to OpenStreetMap, which finishes the task"));
        }
    }
}

/**
 * Select the task primitives
 */
private static class SelectOsmPrimitives extends InnerAction {
    /**
     * The serial UID for this action
     */
    @Serial
    private static final long serialVersionUID = -5705885041487335379L;
    private final Supplier<Task> taskSuppler;

    SelectOsmPrimitives(Supplier<Task> taskSupplier) {
        super(tr("Select objects"), "dialogs/select", tr("Select the OSM objects for this task, downloading them if needed"),
                Shortcut.registerShortcut("maproulette:select_task_primitives",
                        tr("MapRoulette: Select task primitives"), KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                false);
        Objects.requireNonNull(taskSupplier);
        this.taskSuppler = taskSupplier;
        putValue("help", ht("/Dialog/MapRouletteCurrentTask#SelectOsmPrimitives"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        final var task = this.taskSuppler.get();
        if (task != null) {
            // Downloads the objects that are not in the data yet (#114)
            TaskData.loadAndSelect(task);
        }
    }

    @Override
    public void updateEnabledState() {
        if (this.taskSuppler != null) { // This check is only needed for the constructor. Watch JEP draft 8300786.
            final var task = this.taskSuppler.get();
            // Called on the EDT: until the challenge arrives, stay enabled for any task and let the worker find out
            this.setEnabled(task != null
                    && TaskPrimitives.cachedPrimitiveIds(task).map(ids -> !ids.isEmpty()).orElse(Boolean.TRUE));
        }
    }
}}
