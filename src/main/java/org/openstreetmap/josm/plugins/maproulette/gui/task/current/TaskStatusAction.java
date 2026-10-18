// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.Action;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.text.html.HTMLDocument;

import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.OsmDataManager;
import org.openstreetmap.josm.gui.ConditionalOptionPaneUtil;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
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
import org.openstreetmap.josm.plugins.maproulette.io.TaskStatusSender;
import org.openstreetmap.josm.plugins.maproulette.util.ExceptionDialogUtil;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
import org.openstreetmap.josm.tools.Shortcut;

import jakarta.annotation.Nullable;

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
        super(label(status), getIconName(status), label(status),
                Shortcut.registerShortcut(/* NO-SHORTCUT */ "maproulette:" + status.name().toLowerCase(Locale.ENGLISH),
                        tr("MapRoulette: Mark Task as {0}", status.description()), KeyEvent.CHAR_UNDEFINED,
                        Shortcut.NONE),
                false);
        this.status = status;
        this.currentTaskProvider = currentTaskProvider;
        this.currentDocumentProvider = currentDocumentProvider;
    }

    /**
     * Get the button label for a status, as the MapRoulette website names it
     *
     * @param status The status
     * @return The label
     */
    static String label(TaskStatus status) {
        return MRGuiHelper.statusLabel(status);
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
        final var task = this.currentTaskProvider.get();
        final var taskList = TaskListPanel.get();
        final var selected = othersToMark(taskList == null ? List.of() : taskList.getSelected(), task);
        final var source = e.getSource();
        // Load the challenge on the worker (a cooperative fix needs it), then mark the task on the EDT
        Workers.execute(() -> {
            final var loaded = task == null ? null : load(task);
            GuiHelper.runInEDT(() -> {
                handleTask(loaded);
                if (!selected.isEmpty() && confirmMarkAll(selected)) {
                    Workers.execute(() -> markAll(selected, source));
                } else {
                    updateButtons(source);
                }
            });
        });
    }

    /**
     * Get the other tasks to offer to mark along with the current task. That is only for a deliberate multi-select:
     * the selection must hold the current task and at least one other task (#104).
     *
     * @param selected The tasks selected in the task list
     * @param task     The current task, may be {@code null}
     * @return The selected tasks other than the current task, or an empty list
     */
    static List<TaskClusteredPoint> othersToMark(Collection<? extends TaskClusteredPoint> selected,
            @Nullable Task task) {
        if (task == null || selected.stream().noneMatch(p -> p.id() == task.id())) {
            return List.of();
        }
        return selected.stream().filter(p -> p.id() != task.id()).map(TaskClusteredPoint.class::cast).toList();
    }

    /**
     * Ask whether to mark the other selected tasks too. The default is No.
     *
     * @param selected The other selected tasks
     * @return {@code true} if the user agreed
     */
    boolean confirmMarkAll(List<TaskClusteredPoint> selected) {
        return markAllDialog(selected).showDialog().getValue() == 1;
    }

    /**
     * Build the dialog that asks whether to mark the other selected tasks too. Button 1 marks them, button 2 (the
     * default) does not.
     *
     * @param selected The other selected tasks
     * @return The dialog, not shown yet
     */
    ExtendedDialog markAllDialog(List<TaskClusteredPoint> selected) {
        final var ids = selected.stream().map(p -> Long.toString(p.id())).collect(Collectors.joining(", "));
        return new ExtendedDialog(MRGuiHelper.dialogParent(), tr("Mark other selected tasks?"), tr("Mark them too"),
                tr("Only the current task"))
                        .setContent(tr(
                                "<html>Also mark the other selected tasks ({0}) as {1}?<br>"
                                        + "This applies their tag fixes and geometry fixes.</html>",
                                ids, label(this.status)))
                        .setIcon(JOptionPane.WARNING_MESSAGE).setDefaultButton(2).setCancelButton(2);
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
            loaded.forEach(this::handleTask);
            if (error != null) {
                ExceptionDialogUtil.explainException(error);
            }
            updateButtons(source);
        });
    }

    /**
     * A task with its challenge
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
        if (SwingUtilities.getAncestorOfClass(CurrentTaskPanel.class, button)instanceof CurrentTaskPanel panel) {
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
            // Send the status now, as the web does (D15). A Fixed is linked to the changeset of the upload that follows,
            // but only if there is something to upload: a Fixed with no edit must not be linked to an unrelated upload.
            if (this.status == TaskStatus.FIXED && hasEditsToUpload()) {
                ModifiedObjects.addUnlinkedFixed(task);
            }
            MainApplication.worker.execute(() -> TaskStatusSender.sendStatuses(List.of(modifiedTask)));
        }
        Optional.ofNullable(CurrentTaskPanel.get()).ifPresent(p -> p.refreshModel(task));
    }

    /**
     * Check if the edit layer has something to upload
     *
     * @return {@code true} if it has
     */
    static boolean hasEditsToUpload() {
        final var editLayer = MainApplication.getLayerManager().getEditLayer();
        return editLayer != null && editLayer.requiresUploadToServer();
    }

    private ModifiedTask getModifiedTask(LoadedTask loaded) {
        final var task = loaded.task();
        if (loaded.challenge() == null) {
            ExceptionDialogUtil.explainException(loaded.challengeError());
        }
        // The comment and the review request are in the panel, filled in before picking the status (#115)
        final var panel = CurrentTaskPanel.get();
        return panel != null ? panel.feedback(task, this.status)
                : new ModifiedTask(task, this.status, null, null, null,
                        CurrentTaskPanel.getSelections(this.currentDocumentProvider.get()));
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
                    this.putValue(NAME, label(this.status));
                    this.setTooltip(ExceptionDialogUtil.explainRefusal(Reason.CHOICE_TASK_STATUS));
                    this.setEnabled(false);
                    return;
                }
                if (this.status == TaskStatus.FIXED && task.cooperativeWork() == null) {
                    // An edited task is finished by uploading its edits (#115)
                    this.putValue(NAME, label(this.status));
                    this.setTooltip(tr("Upload your edits to finish the task"));
                    this.setEnabled(false);
                    return;
                }
                if (task.cooperativeWork() == null || task.isChoiceTask()) {
                    this.putValue(NAME, label(this.status));
                    this.setTooltip(label(this.status));
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
                        this.putValue(NAME, label(this.status));
                        this.setTooltip(label(this.status));
                    }
                    }
                } else if (task.isCooperativeWorkOsc()) {
                    if (Objects.requireNonNull(this.status) == TaskStatus.FIXED) {
                        this.putValue(NAME, tr("Apply OSC"));
                        this.setTooltip(tr("Apply the suggested change from the task"));
                    } else {
                        this.putValue(NAME, label(this.status));
                        this.setTooltip(label(this.status));
                    }
                }
                if (ModifiedObjects.isFinishPending(task.id())) {
                    // The upload in progress finishes the task; another status would be overwritten
                    this.setTooltip(tr("The upload in progress finishes task {0}", Long.toString(task.id())));
                    this.setEnabled(false);
                } else if (MapRouletteAuth.isAvailable()) {
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
