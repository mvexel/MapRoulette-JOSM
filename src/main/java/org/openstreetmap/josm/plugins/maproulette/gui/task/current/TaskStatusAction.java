// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.swing.Action;
import javax.swing.JCheckBox;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.text.html.HTMLDocument;

import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.OsmDataManager;
import org.openstreetmap.josm.gui.ConditionalOptionPaneUtil;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.tagging.ac.AutoCompComboBox;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JosmTextArea;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteUnsupportedException.Reason;
import org.openstreetmap.josm.plugins.maproulette.api.model.Challenge;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.plugins.maproulette.api_caching.ChallengeCache;
import org.openstreetmap.josm.plugins.maproulette.api_caching.TaskCache;
import org.openstreetmap.josm.plugins.maproulette.data.ApplyCooperativeChange;
import org.openstreetmap.josm.plugins.maproulette.data.ApplyOscChange;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedTask;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Update the status for a task
 */
class TaskStatusAction extends CurrentTaskPanel.InnerAction {
    /**
     * The serial UID for this action
     */
    @Serial
    private static final long serialVersionUID = -7143294590412539737L;
    /**
     * The status for this task
     */
    private final TaskStatus status;
    /**
     * The supplier that will give us the current task
     */
    private final Supplier<Task> currentTaskProvider;
    private final Supplier<HTMLDocument> currentDocumentProvider;

    /**
     * Create a new task status action
     *
     * @param status              The status to use
     * @param currentTaskProvider The current task provider
     * @param currentDocumentProvider The current document provider -- used to generate completion responses from select tags
     */
    TaskStatusAction(TaskStatus status, Supplier<Task> currentTaskProvider,
            Supplier<HTMLDocument> currentDocumentProvider) {
        super(status.description(), getIconName(status), status.description(),
                Shortcut.registerShortcut(/* NO-SHORTCUT */ "maproulette:" + status.name().toLowerCase(Locale.ENGLISH),
                        tr("MapRoulette: Mark Task as {0}", status.description()), KeyEvent.CHAR_UNDEFINED,
                        Shortcut.NONE),
                false);
        this.status = status;
        this.currentTaskProvider = currentTaskProvider;
        this.currentDocumentProvider = currentDocumentProvider;
    }

    /**
     * Get the icon name for a status type
     *
     * @param status The status type
     * @return The icon name
     */
    private static String getIconName(TaskStatus status) {
        return switch (status) {
        case FIXED, ALREADY_FIXED -> "dialogs/validator";
        case TOO_HARD, FALSE_POSITIVE -> "cancel";
        case SKIPPED -> "svpRight";
        default -> "presets/misc/no_icon";
        };
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        final var selected = new ArrayList<TaskClusteredPoint>(
                MainApplication.getMap().getToggleDialog(TaskListPanel.class).getSelected());
        final var task = this.currentTaskProvider.get();
        if (task != null) {
            selected.removeIf(p -> p.id() == task.id());
        }
        final var source = e.getSource();
        // Load the challenge for the feedback dialog on the worker, then show the dialogs on the EDT
        Workers.execute(() -> {
            final var loaded = task == null ? null : load(task);
            GuiHelper.runInEDT(() -> {
                handleTask(loaded);
                if (!selected.isEmpty() && confirmMarkAll()) {
                    Workers.execute(() -> markAll(selected, source));
                } else {
                    updateButtons(source);
                }
            });
        });
    }

    /**
     * Ask whether to mark all selected tasks
     *
     * @return {@code true} if the user agreed
     */
    private static boolean confirmMarkAll() {
        return ConditionalOptionPaneUtil.showConfirmationDialog("TaskStatusAction.bulkUpdate",
                MRGuiHelper.dialogParent(),
                tr("<html>Mark all selected tasks?<br>This will apply all tag fixes and geometry fixes!</html>"),
                tr("Mark all selected tasks?"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                JOptionPane.YES_OPTION);
    }

    /**
     * Load the selected tasks on the worker and mark them on the EDT. Must not be called on the EDT.
     *
     * @param selected The selected tasks
     * @param source   The source of the action event
     */
    private void markAll(List<TaskClusteredPoint> selected, Object source) {
        final var loaded = new ArrayList<LoadedTask>(selected.size());
        IOException failure = null;
        try {
            for (var toMark : selected) {
                loaded.add(load(toMark instanceof Task t ? t : TaskCache.get(toMark.id())));
            }
        } catch (IOException ioException) {
            failure = ioException;
        }
        final var error = failure;
        GuiHelper.runInEDT(() -> {
            ConditionalOptionPaneUtil.startBulkOperation("maproulette.task.feedback");
            try {
                loaded.forEach(this::handleTask);
                if (error != null) {
                    ExceptionDialogUtil.explainException(error);
                }
            } finally {
                ConditionalOptionPaneUtil.endBulkOperation("maproulette.task.feedback");
            }
            updateButtons(source);
        });
    }

    /**
     * A task with the challenge its feedback dialog needs
     *
     * @param task           The task
     * @param challenge      The task's challenge, or {@code null} if it could not be loaded
     * @param challengeError Why the challenge could not be loaded, or {@code null}
     */
    private record LoadedTask(Task task, Challenge challenge, IOException challengeError) {
    }

    /**
     * Load the challenge for a task. Must not be called on the EDT.
     *
     * @param task The task
     * @return The task with its challenge
     */
    private static LoadedTask load(Task task) {
        try {
            return new LoadedTask(task, ChallengeCache.challenge(task.parentId()), null);
        } catch (IOException ioException) {
            return new LoadedTask(task, null, ioException);
        }
    }

    /**
     * Use the first enabled action on the button the action came from, and update the panel's actions
     *
     * @param source The source of the action event
     */
    private void updateButtons(Object source) {
        // Use the first enabled action (not this, in otherwords)
        SideButton button = null;
        if (source instanceof JMenuItem menuItem && menuItem.getParent()instanceof JPopupMenu menu
                && menu.getInvoker()instanceof SideButton sideButton) {
            if (sideButton.getAction() == this) {
                final var newAction = Stream.of(menu.getSubElements()).filter(JMenuItem.class::isInstance)
                        .map(item -> ((JMenuItem) item).getAction()).filter(Action::isEnabled)
                        .filter(action -> action != this).findFirst().orElse(this);
                sideButton.setAction(newAction);
            }
            button = sideButton;
            sideButton.setAction(this);
        } else if (source instanceof SideButton sideButton) {
            button = sideButton;
        }
        if (button != null && button.getParent().getParent().getParent()instanceof CurrentTaskPanel panel) {
            for (var action : panel.actions()) {
                action.updateEnabledState();
            }
        }
    }

    private void handleTask(LoadedTask loaded) {
        final var task = loaded == null ? null : loaded.task();
        if (task != null) {
            final var modifiedTask = getModifiedTask(loaded);
            if (modifiedTask == null) {
                return;
            }
            ModifiedObjects.addModifiedTask(modifiedTask);
            final var editDataSet = OsmDataManager.getInstance().getEditDataSet();
            if ((task.isCooperativeWorkOsmChange() || task.isCooperativeWorkOsc()) && this.status == TaskStatus.FIXED
                    && editDataSet == null) {
                // Nothing to apply the fix to; don't report the task as fixed
                new Notification(tr("Open an editable data layer before marking this task as fixed"))
                        .setIcon(JOptionPane.WARNING_MESSAGE).show();
                ModifiedObjects.removeModifiedTask(modifiedTask);
                return;
            }
            if (task.isCooperativeWorkOsmChange() && this.status == TaskStatus.FIXED) {
                final var command = new ApplyCooperativeChange(
                        Objects.requireNonNull(task.cooperativeWorkAsOsmChange())).generateCommand(editDataSet);
                if (command == null) {
                    // The task's objects are not all downloaded (the user has been told which); don't report it as fixed
                    ModifiedObjects.removeModifiedTask(modifiedTask);
                    return;
                }
                command.executeCommand();
                if (!command.getParticipatingPrimitives().isEmpty()) {
                    UndoRedoHandler.getInstance().add(command, false);
                }
            } else if (task.isCooperativeWorkOsc() && this.status == TaskStatus.FIXED) {
                final var message = tr("Apply OSC directly to the edit layer?");
                final var options = new String[] { tr("Apply"), tr("Show"), tr("Cancel") };
                final var option = ConditionalOptionPaneUtil.showOptionDialog("maproulette.task.apply_osc",
                        MRGuiHelper.dialogParent(), message, message, JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.YES_OPTION, options, options[0]);
                final var osc = Objects.requireNonNull(task.cooperativeWorkAsOsc());
                if (option == 0) {
                    UndoRedoHandler.getInstance().add(new ApplyOscChange(editDataSet, osc.a));
                } else if (option == 1) {
                    final var layer = new OsmDataLayer(osc.a, task.name(), null);
                    MainApplication.getLayerManager().addLayer(layer);
                } else {
                    ModifiedObjects.removeModifiedTask(modifiedTask);
                    return;
                }
            }
        }
        Optional.ofNullable(MainApplication.getMap().getToggleDialog(CurrentTaskPanel.class))
                .ifPresent(p -> p.refreshModel(task));
    }

    private ModifiedTask getModifiedTask(LoadedTask loaded) {
        final var task = loaded.task();
        final var panel = new JPanel(new GridBagLayout());
        final var comment = new JosmTextArea();
        final var tagBox = new AutoCompComboBox<String>();
        // MapRoulette can be asked for a review, but not to skip one the user's settings require
        final var reviewRequested = new JCheckBox(tr("Request Review"));
        comment.setRows(4);
        comment.setColumns(25);
        final var challenge = loaded.challenge();
        if (challenge != null) {
            if (challenge.extra().preferredTags() != null) {
                tagBox.getModel().addAllElements(Arrays.asList(challenge.extra().preferredTags().split(",")));
            }
            tagBox.setEditable(!challenge.extra().limitTags());
        } else {
            tagBox.setEditable(true);
            ExceptionDialogUtil.explainException(loaded.challengeError());
        }
        panel.add(comment, GBC.eol().fill(GBC.BOTH));
        panel.add(tagBox, GBC.eol().fill(GBC.HORIZONTAL));
        panel.add(reviewRequested, GBC.eol().anchor(GBC.LINE_START));

        var selection = ConditionalOptionPaneUtil.showOptionDialog("maproulette.task.feedback",
                MRGuiHelper.dialogParent(), panel, tr("Task Feedback"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, null);
        if (selection == JOptionPane.OK_OPTION) {
            return new ModifiedTask(task, this.status, comment.getText().trim().isEmpty() ? null : comment.getText(),
                    tagBox.getText().trim().isEmpty() ? null : tagBox.getText(),
                    reviewRequested.isSelected() ? Boolean.TRUE : null,
                    CurrentTaskPanel.getSelections(this.currentDocumentProvider.get()));
        }
        return null;
    }

    @Override
    public void updateEnabledState() {
        if (this.currentTaskProvider != null) {
            final var task = this.currentTaskProvider.get();
            if (task != null) {
                // Disable buttons when the state has been modified
                final var state = Optional.ofNullable(ModifiedObjects.getModifiedTask(task.id()))
                        .map(ModifiedTask::status).orElse(task.status());
                if (task.isChoiceTask() && this.status != TaskStatus.SKIPPED) {
                    // MapRoulette refuses these statuses (choice_task_status); only skip stays open
                    this.putValue(NAME, this.status.description());
                    this.setTooltip(ExceptionDialogUtil.explainRefusal(Reason.CHOICE_TASK_STATUS));
                    this.setEnabled(false);
                    return;
                }
                if (task.cooperativeWork() == null || task.isChoiceTask()) {
                    this.putValue(NAME, this.status.description());
                    this.setTooltip(this.status.description());
                } else if (task.isCooperativeWorkOsmChange()) {
                    switch (this.status) {
                    case FALSE_POSITIVE -> {
                        this.putValue(NAME, tr("No"));
                        this.setTooltip(tr("No: the suggested tags are wrong"));
                    }
                    case FIXED -> {
                        this.putValue(NAME, tr("Yes"));
                        this.setTooltip(tr("Yes: the suggested tags are correct"));
                    }
                    default -> {
                        this.putValue(NAME, this.status.description());
                        this.setTooltip(this.status.description());
                    }
                    }
                } else if (task.isCooperativeWorkOsc()) {
                    if (Objects.requireNonNull(this.status) == TaskStatus.FIXED) {
                        this.putValue(NAME, tr("Apply OSC"));
                        this.setTooltip(tr("Apply the suggested change from the task"));
                    } else {
                        this.putValue(NAME, this.status.description());
                        this.setTooltip(this.status.description());
                    }
                }
                if (MapRouletteAuth.isAvailable()) {
                    this.setEnabled(status != state);
                } else {
                    // Setting a status needs a MapRoulette login; explain why the button is disabled
                    this.setTooltip(MapRouletteAuth.unavailableTooltip());
                    this.setEnabled(false);
                }
                return;
            }
        }
        this.setEnabled(false);
    }
}
