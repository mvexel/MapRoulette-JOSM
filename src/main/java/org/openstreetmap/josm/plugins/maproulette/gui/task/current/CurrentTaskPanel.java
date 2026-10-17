// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

import javax.swing.Action;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.UIManager;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.Option;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.osm.OsmDataManager;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.SideButton;
import org.openstreetmap.josm.gui.widgets.HtmlPanel;
import org.openstreetmap.josm.gui.widgets.VerticallyScrollablePanel;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
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
import org.openstreetmap.josm.plugins.maproulette.gui.preferences.MapRouletteServerPreference;
import org.openstreetmap.josm.plugins.maproulette.util.Workers;
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
        final var newActions = new InnerAction[] {
                new TaskStatusAction(TaskStatus.FALSE_POSITIVE, supplier, docSupplier),
                new TaskStatusAction(TaskStatus.TOO_HARD, supplier, docSupplier),
                new TaskStatusAction(TaskStatus.FIXED, supplier, docSupplier),
                new TaskStatusAction(TaskStatus.ALREADY_FIXED, supplier, docSupplier),
                new TaskStatusAction(TaskStatus.SKIPPED, supplier, docSupplier), new SelectOsmPrimitives(supplier) };

        final var falsePositive = new SideButton(newActions[0]);
        final var fixed = new SideButton(newActions[2]);
        falsePositive.createArrow(l -> showPopupMenu(falsePositive, newActions[0], newActions[1]));
        fixed.createArrow(l -> showPopupMenu(fixed, newActions[2], newActions[3]));
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
        this.add(MRGuiHelper.buttonRows(List.of(List.of(fixed, falsePositive),
                List.of(new SideButton(newActions[4]), new SideButton(newActions[5])))), BorderLayout.SOUTH);
        MapRouletteClient.getInstance().addAuthListener(this.authListener);
        ChallengeCache.addListener(this.challengeListener);
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
        // The side buttons destroy their own actions, once; Too Hard and Already Fixed are only in the menus
        GuiHelper.destroyComponents(this, false);
        this.actions[1].destroy();
        this.actions[3].destroy();
        this.openAction.destroy();
        MapRouletteClient.getInstance().removeAuthListener(this.authListener);
        ChallengeCache.removeListener(this.challengeListener);
    }

    private void refreshActions() {
        for (var action : this.actions) {
            action.updateEnabledState();
        }
    }

    private static void showPopupMenu(Component parent, Object... menuItems) {
        final var menu = new JPopupMenu();
        final var box = parent.getBounds();
        for (var item : menuItems) {
            if (item instanceof Action action) {
                menu.add(action);
            } else {
                throw new IllegalArgumentException("We don't currently support " + item.getClass());
            }
        }
        menu.show(parent, 0, box.y + box.height);
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
        if (currentTask == null) {
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
        this.idLabel.setText(tr("Task {0}", currentTask.id()));
        this.openButton.setVisible(true);

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
        super(tr("Select Primitives"), "dialogs/select", tr("Select the OSM primitives for this task"),
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
            // Finding the primitives may need the challenge from the server
            Workers.execute(() -> {
                final var primitives = TaskPrimitives.getPrimitiveIds(task);
                if (!primitives.isEmpty()) {
                    GuiHelper.runInEDT(() -> {
                        OsmDataManager.getInstance().getEditDataSet().setSelected(primitives);
                        AutoScaleAction.autoScale(AutoScaleAction.AutoScaleMode.SELECTION);
                    });
                }
            });
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
