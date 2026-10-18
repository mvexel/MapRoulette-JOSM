// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
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
import org.openstreetmap.josm.plugins.maproulette.gui.LoginControl;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.MapRouletteDialog;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.OsmLogin;
import org.openstreetmap.josm.plugins.maproulette.gui.TagChangeTable;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskChanges;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskData;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskEdits;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults;
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskRows;
import org.openstreetmap.josm.plugins.maproulette.io.TaskStatusSender;
import org.openstreetmap.josm.plugins.maproulette.io.upload.EarlyUploadHook;
import org.openstreetmap.josm.plugins.maproulette.io.upload.LateUploadHook;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.ImageProvider;
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
    private static final String INSTRUCTIONS_CARD = "instructions";
    private static final String NEXT_CARD = "next";
    /**
     * The model for the task
     */
    private final VerticallyScrollablePanel panel = new VerticallyScrollablePanel(new GridBagLayout());
    /**
     * The name of the challenge of the task
     */
    private final JLabel challengeLabel = new JLabel();
    /**
     * The name of the task. It zooms to the task, so the user finds the way back after panning away (#145).
     */
    private final JButton nameLink = new JButton();
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
     * Shows the instructions, or the next task once the shown task is finished (#117)
     */
    private final JPanel centerCards = new JPanel(new CardLayout());
    /**
     * The next task and the tasks done this session
     */
    private final NextTaskPanel nextPanel = new NextTaskPanel();
    /**
     * The finished tasks the next task was offered for, so the next task starts on its own only once per task
     */
    private final Set<Long> offered = new HashSet<>();
    /**
     * {@code true} while the panel shows a finished task, with the next task instead of the instructions
     */
    private boolean showsNext;
    /**
     * The lines of the finish strip, hidden once the task is finished
     */
    private final List<Component> finishStrip = new ArrayList<>();
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
     * The state line: what the task is at, with the next step (#116)
     */
    private final JPanel statePanel = new JPanel(new GridBagLayout());
    /**
     * A dot in the colour of the state
     */
    private final JLabel stateDot = new JLabel();
    /**
     * The state, which can hold a link to the changeset
     */
    private final JMultilineLabel stateText = new JMultilineLabel("");
    /**
     * More about the state, in grey under it
     */
    private final JMultilineLabel stateHint = new JMultilineLabel("");
    /**
     * The buttons that answer or fix the state, under it
     */
    private final JPanel choicesPanel = new JPanel(new BorderLayout());
    /**
     * A question about the shown task, which comes before its state until it is answered (#116)
     */
    private transient Prompt prompt;
    /**
     * {@code true} if the question's task was started when it was asked, so the question goes once the task is
     * finished or stopped
     */
    private boolean promptHeldLock;
    /**
     * What changed on the task's objects, so it can be checked before the upload (#148)
     */
    private final JMultilineLabel changesLabel = new JMultilineLabel("");
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
     * The task whose objects a download did not bring, or {@code -1}
     */
    private long notDownloaded = -1;
    /**
     * The task name and id, above the instructions
     */
    private final JPanel header = new JPanel(new GridBagLayout());
    /**
     * The edit count, the feedback and the finish buttons, below the instructions
     */
    private final JPanel south = new JPanel(new GridBagLayout());
    /**
     * The comment, the task tags and the review request, folded until the user opens them (#132)
     */
    private final JPanel feedbackPanel = new JPanel(new GridBagLayout());
    /**
     * Opens the feedback fields
     */
    private final JButton feedbackToggle = new JButton("\u25b8 " + tr("Add a comment or ask for a review…"));
    /**
     * {@code true} once the user opened the feedback fields for the current task
     */
    private boolean feedbackOpen;
    /**
     * The buttons for the outcomes with no edit
     */
    private final JPanel statusButtons;
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
     * Drops a question about a task once the task is no longer started, for example finished or stopped, so it doesn't
     * hide the task's real state (#116), then updates like {@link #editsListener}
     */
    private final Runnable lockListener = () -> {
        final var question = this.prompt;
        if (question != null && this.promptHeldLock && ModifiedObjects.getLockedTask(question.taskId()) == null) {
            this.prompt = null;
        }
        this.editsListener.run();
    };
    /**
     * Shows a task whose Fixed could not be sent, and updates the actions once a status is sent
     */
    private final transient Consumer<Map<Long, Task>> sentListener = tasks -> {
        this.refreshActions();
        this.refreshFinish();
        // The header shows the status that was sent
        this.refreshDataState();
    };
    /**
     * The task the comment and the review request were filled in for
     */
    private long feedbackTask = -1;
    /**
     * Re-evaluates the actions when the MapRoulette login changes
     */
    private final Runnable authListener = () -> {
        this.refreshActions();
        // The state line says when a login is needed
        this.refreshDataState();
    };
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
        final var header = this.header;
        // The task name is what people look for; the challenge above it is context
        final var font = this.challengeLabel.getFont();
        this.challengeLabel.setFont(font.deriveFont(font.getSize2D() * 0.9f));
        this.challengeLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        this.nameLink.setFont(font.deriveFont(Font.BOLD, font.getSize2D() * 1.2f));
        // A link-like button: it takes keyboard focus and has a tooltip, but no button border
        this.nameLink.setBorderPainted(false);
        this.nameLink.setContentAreaFilled(false);
        this.nameLink.setHorizontalAlignment(SwingConstants.LEADING);
        this.nameLink.setMargin(new Insets(0, 0, 0, 0));
        this.nameLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        this.nameLink.setToolTipText(tr("Zoom to task"));
        // The zoom icon says the name is clickable, without link colours JOSM doesn't use
        this.nameLink.setHorizontalTextPosition(SwingConstants.LEADING);
        this.nameLink.addActionListener(newActions[5]);
        header.add(this.challengeLabel, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 2, 4, 0));
        header.add(this.nameLink, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 0));
        header.add(this.idLabel, GBC.std().fill(GBC.HORIZONTAL).insets(4, 0, 4, 2));
        header.add(this.openButton, GBC.eol().anchor(GBC.LINE_END).insets(0, 0, 4, 2));
        this.add(header, BorderLayout.NORTH);
        final var gbc = GBC.eol();
        this.panel.add(new JLabel(tr("Instructions: ")), gbc);
        gbc.fill(GridBagConstraints.BOTH);
        this.panel.add(this.instructionPane, gbc);
        this.panel.add(this.cooperativeWork, gbc);
        this.centerCards.add(this.panel.getVerticalScrollPane(), INSTRUCTIONS_CARD);
        final var nextScroll = new JScrollPane(this.nextPanel);
        nextScroll.setBorder(null);
        nextScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        this.centerCards.add(nextScroll, NEXT_CARD);
        this.add(this.centerCards, BorderLayout.CENTER);
        this.commentField.setHint(tr("Comment for MapRoulette (optional)"));
        this.commentField.setToolTipText(tr("Sent with the task''s status"));
        this.tagBox.setToolTipText(tr("Task tags for MapRoulette (optional)"));
        // The text wraps beside the dot; a fix for the state sits at the end of the line
        this.stateText.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) {
                OpenBrowser.displayUrl(e.getURL().toString());
            }
        });
        this.stateHint.setFont(font.deriveFont(font.getSize2D() * 0.9f));
        // Fixed cells, since the hint and the buttons come and go
        this.statePanel.add(this.stateDot, GBC.std(0, 0).anchor(GBC.FIRST_LINE_START).insets(0, 5, 4, 0));
        this.statePanel.add(this.stateText, GBC.std(1, 0).fill(GBC.HORIZONTAL));
        this.statePanel.add(this.stateHint, GBC.std(1, 1).fill(GBC.HORIZONTAL));
        this.statePanel.add(this.choicesPanel, GBC.std(1, 2).fill(GBC.HORIZONTAL).insets(0, 2, 0, 0));
        final var south = this.south;
        south.add(this.statePanel, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        south.add(this.changesLabel, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 2, 4, 2));
        // A link-like line, so the fields take no room until they are wanted
        this.feedbackToggle.setBorderPainted(false);
        this.feedbackToggle.setContentAreaFilled(false);
        this.feedbackToggle.setHorizontalAlignment(SwingConstants.LEADING);
        this.feedbackToggle.setMargin(new Insets(0, 0, 0, 0));
        this.feedbackToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        this.feedbackToggle.addActionListener(e -> {
            this.feedbackOpen = true;
            this.refreshFeedbackFold();
            this.commentField.requestFocusInWindow();
        });
        south.add(this.feedbackToggle, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 0));
        this.feedbackPanel.add(this.commentField, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 0));
        this.feedbackPanel.add(this.tagBox, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 0));
        this.feedbackPanel.add(this.reviewBox, GBC.eol().anchor(GBC.LINE_START).insets(2, 0, 4, 0));
        south.add(this.feedbackPanel, GBC.eol().fill(GBC.HORIZONTAL));
        // Uploading is the main way to finish; the outcomes with no edit come after it
        final var finishButton = new SideButton(newActions[6]);
        finishButton.setFont(finishButton.getFont().deriveFont(Font.BOLD));
        this.finishCards.add(finishButton, FINISH_CARD);
        this.finishCards.add(new SideButton(newActions[4]), COOPERATIVE_CARD);
        south.add(this.finishCards, GBC.eol().fill(GBC.HORIZONTAL));
        // Wraps in a narrow panel. It renders HTML, which ignores setForeground, so the grey goes into the text.
        final var hintColor = Optional.ofNullable(UIManager.getColor("Label.disabledForeground")).orElse(Color.GRAY);
        final var uploadHint = new JMultilineLabel(String.format("<html><span style=\"color:#%06x\">%s</span></html>",
                hintColor.getRGB() & 0xFFFFFF, Utils.escapeReservedCharactersHTML(
                        tr("Edited the map? Upload here or with File > Upload data, and the task is finished."))));
        uploadHint.setFont(font.deriveFont(font.getSize2D() * 0.9f));
        south.add(uploadHint, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 4));
        final var noEdit = new JLabel(tr("Nothing to edit?"));
        noEdit.setFont(font.deriveFont(Font.BOLD));
        south.add(noEdit, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 0));
        this.statusButtons = MRGuiHelper
                .buttonRows(List.of(List.of(new SideButton(newActions[0]), new SideButton(newActions[1])),
                        // An empty cell keeps Can't Complete as wide as the others
                        List.of(new SideButton(newActions[2]), new JPanel()),
                        List.of(new SideButton(newActions[3]), new SideButton(newActions[5]))));
        south.add(this.statusButtons, GBC.eol().fill(GBC.HORIZONTAL));
        this.finishStrip.addAll(List.of(this.finishCards, uploadHint, noEdit, this.statusButtons));
        this.add(south, BorderLayout.SOUTH);
        MapRouletteClient.getInstance().addAuthListener(this.authListener);
        TaskEdits.addListener(this.editsListener);
        ModifiedObjects.addLockListener(this.lockListener);
        TaskStatusSender.addListener(this.sentListener);
        TaskResults.addListener(this.editsListener);
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
        this.nextPanel.destroy();
        MapRouletteClient.getInstance().removeAuthListener(this.authListener);
        TaskEdits.removeListener(this.editsListener);
        ModifiedObjects.removeLockListener(this.lockListener);
        TaskStatusSender.removeListener(this.sentListener);
        TaskResults.removeListener(this.editsListener);
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
     * Note that a download did not bring any of a task's objects, so the panel says so instead of offering the same
     * download again without a word (#145). Call on the EDT.
     *
     * @param taskId The task
     */
    public static void notDownloaded(long taskId) {
        final var panel = get();
        if (panel != null) {
            panel.notDownloaded = taskId;
            panel.refreshDataState();
        }
    }

    /**
     * Show whether the task's objects are in the edit layer, and which are selected
     */
    private void refreshDataState() {
        final var currentTask = this.task;
        if (currentTask == null) {
            this.refreshState();
            return;
        }
        final List<PrimitiveId> ids = TaskPrimitives.cachedPrimitiveIds(currentTask).map(List::copyOf)
                .orElse(List.of());
        final var dataSet = MainApplication.getLayerManager().getEditDataSet();
        final var started = showsLockedTask();
        final var selection = started ? selection(ids, dataSet, TaskRows.readableName(currentTask)) : null;
        // The selection goes on a line of its own, so the narrow panel doesn't cut it off
        this.idLabel.setText(selection == null ? header(currentTask)
                : "<html>" + Utils.escapeReservedCharactersHTML(header(currentTask)) + "<br>"
                        + Utils.escapeReservedCharactersHTML(selection) + "</html>");
        this.refreshState();
    }

    /**
     * Show the state line of the task
     */
    private void refreshState() {
        final var currentTask = this.task;
        final State state;
        if (currentTask == null) {
            state = null;
        } else {
            final List<PrimitiveId> ids = TaskPrimitives.cachedPrimitiveIds(currentTask).map(List::copyOf)
                    .orElse(List.of());
            final var missing = !TaskData.missing(ids, MainApplication.getLayerManager().getEditDataSet()).isEmpty();
            final var question = this.prompt;
            state = question != null && question.taskId() == currentTask.id()
                    && (!question.whileStarted() || showsLockedTask())
                            ? question.state()
                            : withLogin(currentTask,
                                    withOsmLogin(
                                            state(currentTask, showsLockedTask(), TaskEdits.count(),
                                                    TaskResults.get(currentTask.id()),
                                                    ModifiedObjects.isFinishPending(currentTask.id()),
                                                    LateUploadHook.isUnfinished(currentTask.id()), missing,
                                                    this.notDownloaded == currentTask.id()),
                                            showsLockedTask() && TaskEdits.count() > 0 && OsmLogin.needed()),
                                    LoginControl.canLogIn());
        }
        this.apply(state);
        this.refreshNext();
    }

    /**
     * Once the shown task is finished, show the next task in its challenge instead of the instructions and the finish
     * strip (#117)
     */
    private void refreshNext() {
        final var currentTask = this.task;
        final var result = currentTask == null || showsLockedTask() ? null : TaskResults.get(currentTask.id());
        final var next = result != null;
        if (next) {
            // The next task starts on its own only right after an upload finished the task, not when it is shown again
            final var first = this.offered.add(currentTask.id());
            this.nextPanel.show(currentTask,
                    first && result.status() == TaskStatus.FIXED && NextTaskPanel.AUTO_START.get());
        }
        if (next != this.showsNext) {
            this.showsNext = next;
            ((CardLayout) this.centerCards.getLayout()).show(this.centerCards, next ? NEXT_CARD : INSTRUCTIONS_CARD);
            this.finishStrip.forEach(component -> component.setVisible(!next));
            this.refreshFeedbackFold();
        }
    }

    /**
     * Show a state line
     *
     * @param state The state, or {@code null} to hide the line
     */
    void apply(@Nullable State state) {
        this.statePanel.setVisible(state != null);
        if (state == null) {
            return;
        }
        this.stateDot.setIcon(new Dot(state.tone().color()));
        this.stateText.setText("<html>" + state.html() + "</html>");
        this.stateHint.setVisible(state.hint() != null);
        // It renders HTML, which ignores setForeground, so the grey goes into the text
        final var grey = Optional.ofNullable(UIManager.getColor("Label.disabledForeground")).orElse(Color.GRAY);
        this.stateHint.setText(state.hint() == null ? ""
                : String.format("<html><span style=\"color:#%06x\">%s</span></html>", grey.getRGB() & 0xFFFFFF,
                        Utils.escapeReservedCharactersHTML(state.hint())));
        this.choicesPanel.removeAll();
        this.choicesPanel.setVisible(!state.choices().isEmpty());
        if (!state.choices().isEmpty()) {
            final var buttons = state.choices().stream().map(this::button).toList();
            // Short labels share a row; long ones, such as "Stop task 27 and start 151", get one each
            final var shared = state.choices().stream().allMatch(choice -> choice.label().length() <= 20);
            this.choicesPanel.add(
                    MRGuiHelper.buttonRows(shared ? List.of(buttons) : buttons.stream().map(List::of).toList()),
                    BorderLayout.CENTER);
        }
        this.statePanel.revalidate();
        this.statePanel.repaint();
    }

    private JButton button(Choice choice) {
        final var button = new JButton(choice.label());
        button.setToolTipText(choice.tooltip());
        button.addActionListener(e -> {
            // Answered: the state shows again
            if (this.prompt != null && this.prompt.state().choices().contains(choice)) {
                this.prompt = null;
                this.refreshState();
            }
            choice.action().run();
        });
        return button;
    }

    /**
     * Start the next task the panel offers after a finished one, as its Start button does. Call on the EDT.
     *
     * @return {@code false} if the panel offers no next task, for example while a task is still being worked on
     */
    public static boolean startNextTask() {
        final var panel = get();
        return panel != null && panel.showsNext && panel.nextPanel.start();
    }

    /**
     * Ask a question about a task in the state line, instead of a dialog (#116). It shows while the panel shows the
     * task, until the user answers it or another question replaces it. Call on the EDT.
     *
     * @param prompt The question
     * @return {@code false} if the panel doesn't show the task, so the caller asks in a dialog
     */
    public static boolean ask(Prompt prompt) {
        final var panel = get();
        return panel != null && panel.askHere(prompt);
    }

    /**
     * Ask a question in this panel's state line, see {@link #ask(Prompt)}
     *
     * @param prompt The question
     * @return {@code false} if the panel doesn't show the task
     */
    boolean askHere(Prompt prompt) {
        if (this.task == null || this.task.id() != prompt.taskId()) {
            return false;
        }
        this.prompt = prompt;
        this.promptHeldLock = ModifiedObjects.getLockedTask(prompt.taskId()) != null;
        this.refreshState();
        return true;
    }

    /**
     * Drop the question about a task, for example once the task is stopped. Call on the EDT.
     *
     * @param taskId The task
     */
    public static void dropPrompt(long taskId) {
        final var panel = get();
        if (panel != null && panel.prompt != null && panel.prompt.taskId() == taskId) {
            panel.prompt = null;
            panel.refreshState();
        }
    }

    /**
     * Mark the shown task Can't Complete, as its button does. Call on the EDT.
     *
     * @param taskId The task, which the panel must show
     */
    public static void cantComplete(long taskId) {
        final var panel = get();
        if (panel != null && panel.task != null && panel.task.id() == taskId) {
            final var action = panel.actions[2];
            if (action.isEnabled()) {
                action.actionPerformed(new ActionEvent(panel, ActionEvent.ACTION_PERFORMED, null));
            }
        }
    }

    /**
     * A button in the state line
     *
     * @param label   The label
     * @param tooltip The tooltip, or {@code null}
     * @param action  What it does
     */
    public record Choice(String label, @Nullable String tooltip, Runnable action) {
    }

    /**
     * A question about a task, shown in the state line
     *
     * @param taskId       The task it is about
     * @param whileStarted {@code true} if it only matters while the task is started
     * @param text         The question, as plain text
     * @param hint         More about it, or {@code null}
     * @param choices      The answers
     */
    public record Prompt(long taskId, boolean whileStarted, String text, @Nullable String hint,
            List<Choice> choices) {

    State state() {
        return new State(Tone.WARNING, html(this.text), this.hint, this.choices);
    }
}

/**
 * The colour of a state
 */
enum Tone {
    /** The task is started */
    WORKING(new Color(0x2e7d32)),
    /** MapRoulette recorded the result */
    DONE(new Color(0x1f5fad)),
    /** Something needs the user */
    WARNING(new Color(0xd08a00)),
    /** Waiting for something */
    WAITING(Color.GRAY);

    private final Color color;

    Tone(Color color) {
        this.color = color;
    }

    Color color() {
        return this.color;
    }

    }

    /**
     * The state line of a task
     *
     * @param tone The colour
     * @param html The state, as HTML without the html element
     * @param hint More about it, as plain text, or {@code null}
     * @param choices The buttons that fix or answer it
     */
    record State(Tone tone, String html, @Nullable String hint, List<Choice> choices) {
    }

    /**
     * Get the state line of a task: the first of an upload that finishes it, a Fixed that could not be sent, missing
     * objects, the work on a started task, and the result MapRoulette recorded
     *
     * @param task          The task
     * @param started       {@code true} if the user started it
     * @param edits         The edits for the started task
     * @param result        What MapRoulette recorded since the task was last started, or {@code null}
     * @param finishPending {@code true} while an upload that finishes the task runs
     * @param unfinished    {@code true} if the upload succeeded but Fixed could not be sent
     * @param missing       {@code true} if the task's objects are not in the edit layer
     * @param notDownloaded {@code true} if a download did not bring them
     * @return The state, or {@code null} for none
     */
    @Nullable
    static State state(Task task, boolean started, int edits, @Nullable TaskResults.Result result,
            boolean finishPending, boolean unfinished, boolean missing, boolean notDownloaded) {
        final var id = Long.toString(task.id());
        if (finishPending) {
            return new State(Tone.WAITING,
                    html(tr("Uploading. Task {0} is marked Fixed once the upload succeeds.", id)), null, List.of());
        } else if (unfinished) {
            return new State(Tone.WARNING,
                    html(tr("Your edits are uploaded, but Fixed was not sent for task {0}.", id)), null,
                    List.of(new Choice(tr("Send again"), null, () -> LateUploadHook.retryFinish(task.id()))));
        } else if (missing && (started || result == null)) {
            // A download that brought nothing: the server may be unreachable, or the objects deleted on OSM (#145)
            return new State(Tone.WARNING,
                    html(notDownloaded ? tr("Task {0}''s objects could not be downloaded. They may no longer exist on "
                            + "OpenStreetMap.", id) : tr("The task''s objects are not in the data.")),
                    null,
                    List.of(new Choice(tr("Download"),
                            tr("Download the task''s objects from OpenStreetMap and select them"),
                            () -> TaskData.loadAndSelect(task))));
        } else if (started && task.isChoiceTask()) {
            return new State(Tone.WORKING, html(tr("Working on task {0}", id)), null, List.of());
        } else if (started) {
            return edits == 0
                    ? new State(Tone.WORKING, html(tr("Working on task {0} · no edits since it was started", id)),
                            noEditsReason(), List.of())
                    : new State(Tone.WORKING, html(trn("Working on task {1} · {0} edit so far",
                            "Working on task {1} · {0} edits so far", edits, edits, id)), null, List.of());
        } else if (result != null) {
            return new State(result.link() == TaskResults.Link.FAILED ? Tone.WARNING : Tone.DONE, result.html(), null,
                    List.of());
        }
        return null;
    }

    /**
     * Say before the first upload that it asks for the OpenStreetMap account too, and offer JOSM's OSM login, so the
     * second login doesn't come as a surprise in the middle of the upload (#118)
     *
     * @param state  The state line otherwise
     * @param needed {@code true} if the task has edits to upload and JOSM has no OpenStreetMap login
     * @return The state line
     */
    @Nullable
    static State withOsmLogin(@Nullable State state, boolean needed) {
        if (!needed || state == null || state.tone() != Tone.WORKING || state.hint() != null
                || !state.choices().isEmpty()) {
            return state;
        }
        return new State(state.tone(), state.html(),
                tr("Uploading also needs your OpenStreetMap login. It''s separate from the MapRoulette login."),
                List.of(new Choice(tr("Log in to OpenStreetMap…"), tr("Opens JOSM''s OpenStreetMap login"),
                        () -> OsmLogin.logIn(CurrentTaskPanel::refreshData))));
    }

    /**
     * Say why the status buttons of a started task are disabled, with a Log in button, when the user is logged out
     * (#116)
     *
     * @param task     The task
     * @param state    The state line otherwise
     * @param canLogIn {@code true} if the user can log in from JOSM but isn't logged in
     * @return The state line
     */
    static State withLogin(Task task, @Nullable State state, boolean canLogIn) {
        if (!canLogIn || state == null || state.tone() != Tone.WORKING) {
            return state;
        }
        return new State(Tone.WARNING, html(tr("Log in to set the status of task {0}.", Long.toString(task.id()))),
                null, List.of(new Choice(tr("Log in…"), null, LoginControl::logInFromPanel)));
    }

    /**
     * Explain why a started task has no edits, which is why Upload is disabled
     *
     * @return The explanation
     */
    static String noEditsReason() {
        return tr("Edits from before Start count only if they change the task''s objects and aren''t uploaded yet. "
                + "Already uploaded them? Use Already fixed.");
    }

    private static String html(String text) {
        return Utils.escapeReservedCharactersHTML(text);
    }

    /**
     * A filled dot in the colour of the state, the same size as the text
     *
     * @param color The colour
     */
    private record Dot(Color color) implements Icon {

    private static final int SIZE = 8;

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        final var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(this.color);
        g2.fillOval(x, y, SIZE, SIZE);
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }

    }

    /**
     * Get the header line of a task. The state line says whether it is started (#116).
     *
     * @param task The task
     * @return "Task 27", followed by the MapRoulette status when the task isn't open, such as "Task 27 · Done ·
     *         Already fixed" (#146)
     */
    static String header(Task task) {
        final var header = tr("Task {0}", Long.toString(task.id()));
        final var status = TaskRows.statusLine(TaskRows.status(task));
        return status == null ? header : header + " · " + status;
    }

    /**
     * Describe the selected objects of a task
     *
     * @param ids      The task's objects
     * @param dataSet  The edit data set, or {@code null}
     * @param taskName The task's readable name, used when the task has one object
     * @return Such as "Bench "sue" selected", or {@code null} if none of them is selected
     */
    @Nullable
    static String selection(List<PrimitiveId> ids, @Nullable DataSet dataSet, String taskName) {
        final List<OsmPrimitive> selected = dataSet == null ? List.of()
                : ids.stream().map(dataSet::getPrimitiveById).filter(p -> p != null && p.isSelected()).toList();
        if (selected.isEmpty()) {
            return null;
        } else if (selected.size() == 1) {
            // The same name as the header and the list, not JOSM's "a bench named sue"
            return tr("{0} selected",
                    ids.size() == 1 ? taskName : DefaultNameFormatter.getInstance().format(selected.get(0)));
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
        // A question about another task would come back stale when that task is shown again (#166)
        if (this.prompt != null && (task == null || task.id() != this.prompt.taskId())) {
            this.prompt = null;
        }
        this.task = task;
        this.refreshPanel();
        // With no task, the panel gives the task list the room (#132)
        Optional.ofNullable(MapRouletteDialog.get()).ifPresent(MapRouletteDialog::updateLayout);
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
     * Check if the panel shows a task
     *
     * @return {@code true} if there is a current task
     */
    public boolean hasTask() {
        return this.task != null;
    }

    /**
     * Get the size the panel needs to be usable: its header, three lines of instructions and the buttons, which must
     * not cut off their labels (#132)
     *
     * @return The size
     */
    public Dimension neededSize() {
        final var lines = 3 * this.getFontMetrics(this.getFont()).getHeight();
        final var instructions = this.panel.getComponent(0).getPreferredSize().height + lines;
        final var width = Math.max(this.statusButtons.getPreferredSize().width, this.header.getMinimumSize().width);
        // The notices and the opened feedback fields come and go; leave them out, so the minimum stays put
        var south = this.south.getPreferredSize().height;
        for (var notice : List.of(this.stateHint, this.changesLabel)) {
            if (notice.isVisible()) {
                south -= notice.getPreferredSize().height + 4;
            }
        }
        if (this.feedbackPanel.isVisible()) {
            // Count the folded line instead
            south += this.feedbackToggle.getPreferredSize().height - this.feedbackPanel.getPreferredSize().height;
        }
        return new Dimension(width, this.header.getPreferredSize().height + instructions + south);
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
     * Show the state line, the task's changes, and whether the upload can finish the task
     */
    private void refreshFinish() {
        final var currentTask = this.task;
        final var started = currentTask != null && showsLockedTask() && !currentTask.isChoiceTask();
        final var edits = TaskEdits.count();
        final var summary = started && edits > 0
                ? TaskChanges.of(currentTask, MainApplication.getLayerManager().getEditDataSet())
                : TaskChanges.Summary.NONE;
        this.changesLabel.setVisible(!summary.lines().isEmpty());
        // The warning in plain text under the lines; the finish question shows it in bold
        this.changesLabel
                .setText(summary.lines().stream().map(line -> "\u2022 " + Utils.escapeReservedCharactersHTML(line))
                        .collect(Collectors.joining("<br>", "<html>", summary.warning() == null ? "</html>"
                                : "<br>" + Utils.escapeReservedCharactersHTML(summary.warning()) + "</html>")));
        this.refreshState();
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
        // Each task starts with the fields folded, except a rejected one, whose review request is always on
        this.feedbackOpen = isInRevision(currentTask);
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
     * Show the line that opens the feedback fields, or the fields once the user opened them
     */
    private void refreshFeedbackFold() {
        final var any = !this.showsNext
                && (this.commentField.isVisible() || this.tagBox.isVisible() || this.reviewBox.isVisible());
        this.feedbackToggle.setVisible(any && !this.feedbackOpen);
        this.feedbackPanel.setVisible(any && this.feedbackOpen);
        this.revalidate();
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
        refreshFeedbackFold();
        refreshFinish();
        if (currentTask == null) {
            this.refreshState();
            this.instructionPending = false;
            this.challengeLabel.setText(null);
            this.nameLink.setText(tr("No current task"));
            this.nameLink.setIcon(null);
            this.nameLink.setEnabled(false);
            this.idLabel.setText(null);
            this.openButton.setVisible(false);
            this.instructionPane.setText(tr("Start a task from the list to work on it here."));
            this.cooperativeWork.setVisible(false);
            return;
        }
        this.challengeLabel.setText(ChallengeCache.cached(currentTask.parentId()).map(Challenge::name)
                .orElseGet(() -> tr("Challenge {0}", Long.toString(currentTask.parentId()))));
        this.nameLink.setText(TaskRows.readableName(currentTask));
        this.nameLink.setIcon(ImageProvider.get("dialogs/autoscale", "selection", ImageProvider.ImageSizes.SMALLICON));
        this.nameLink.setEnabled(true);
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
                : tr("This server has no MapRoulette website"));
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
            // JOSM's own upload: the upload hook asks if it finishes the task, whatever the distance of the edits (#149)
            EarlyUploadHook.requestFinish();
            MainApplication.getMenu().upload.actionPerformed(e);
            // Queued after the upload check: an upload that stops before it doesn't leave the request for the next one
            MainApplication.worker.execute(EarlyUploadHook::clearFinishRequest);
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
            this.setTooltip(tr("No edits for task {0} since it was started.", Long.toString(task.id())) + ' '
                    + noEditsReason());
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
        super(tr("Zoom to task"), "dialogs/autoscale/selection",
                tr("Select the task''s objects and zoom to them, downloading them if needed"),
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
            // Downloads the objects that are not in the data yet (#114), then selects and zooms (#145)
            TaskData.loadAndSelect(task);
        }
    }

    @Override
    public void updateEnabledState() {
        if (this.taskSuppler != null) { // This check is only needed for the constructor. Watch JEP draft 8300786.
            final var task = this.taskSuppler.get();
            // A task with no objects zooms to its location
            this.setEnabled(task != null);
        }
    }
}}
