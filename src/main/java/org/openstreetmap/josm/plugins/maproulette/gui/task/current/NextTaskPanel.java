// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.current;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.io.IOException;
import java.io.Serial;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;

import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.widgets.JMultilineLabel;
import org.openstreetmap.josm.plugins.maproulette.api.ChallengeAPI;
import org.openstreetmap.josm.plugins.maproulette.api.auth.MapRouletteAuth;
import org.openstreetmap.josm.plugins.maproulette.api.client.MapRouletteClient;
import org.openstreetmap.josm.plugins.maproulette.api.enums.TaskStatus;
import org.openstreetmap.josm.plugins.maproulette.api.model.Task;
import org.openstreetmap.josm.plugins.maproulette.gui.MRGuiHelper;
import org.openstreetmap.josm.plugins.maproulette.gui.ModifiedObjects;
import org.openstreetmap.josm.plugins.maproulette.gui.TaskResults;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskListPanel;
import org.openstreetmap.josm.plugins.maproulette.gui.task.list.TaskRows;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.OpenBrowser;
import org.openstreetmap.josm.tools.Utils;

import jakarta.annotation.Nullable;

/**
 * What comes after a finished task: the next task in the same challenge, and the tasks done this session (#117)
 */
final class NextTaskPanel extends JPanel {
    @Serial
    private static final long serialVersionUID = -3926483109374726451L;
    /**
     * Start the next task after each upload that finishes a task
     */
    static final BooleanProperty AUTO_START = new BooleanProperty("maproulette.next.auto", false);
    /**
     * Pick the nearest task instead of following the challenge's priorities
     */
    static final BooleanProperty NEARBY = new BooleanProperty("maproulette.next.nearby", false);
    /**
     * How many tasks to ask MapRoulette for, so Pick another doesn't need a request each time
     */
    static final int LIMIT = 10;

    private final JToggleButton random = new JToggleButton(tr("Random"));
    private final JToggleButton nearby = new JToggleButton(tr("Nearby"));
    private final JLabel candidateName = new JLabel();
    private final JMultilineLabel candidateDetail = new JMultilineLabel("");
    private final JButton startButton = new JButton(tr("Start next task"));
    private final JButton pickButton = new JButton();
    private final JCheckBox autoBox = new JCheckBox(tr("Start the next task after each upload"));
    private final JLabel doneTitle = new JLabel(tr("Done this session"));
    private final JPanel doneList = new JPanel(new GridBagLayout());
    private final transient Runnable authListener = this::update;
    /**
     * The done this session list as shown, so it is only rebuilt when it changes
     */
    private transient List<TaskResults.Done> shownSession;
    /**
     * The finished task the next one follows
     */
    private transient Task finished;
    /**
     * The tasks MapRoulette offered, in its order
     */
    private transient Task[] candidates = new Task[0];
    /**
     * The task offered, or {@code null}
     */
    private transient Task candidate;
    /**
     * The tasks the user passed over with Pick another
     */
    private final Set<Long> passed = new HashSet<>();
    /**
     * Counts the requests, so an old answer doesn't replace a newer one
     */
    private int generation;
    private boolean loading;
    private boolean failed;
    /**
     * Start the next task as soon as it arrives
     */
    private boolean autoStart;

    NextTaskPanel() {
        super(new GridBagLayout());
        final var font = this.candidateName.getFont();
        final var title = new JLabel(tr("Next task: same challenge"));
        title.setFont(font.deriveFont(Font.BOLD));
        final var group = new ButtonGroup();
        group.add(this.random);
        group.add(this.nearby);
        this.random.setToolTipText(tr("Pick by the challenge''s priorities, as the MapRoulette website does"));
        this.nearby.setToolTipText(tr("Pick the open task nearest to the one you finished"));
        (NEARBY.get() ? this.nearby : this.random).setSelected(true);
        this.random.addActionListener(e -> this.setNearby(false));
        this.nearby.addActionListener(e -> this.setNearby(true));
        final var modes = new JPanel(new GridBagLayout());
        modes.add(this.random, GBC.std());
        modes.add(this.nearby, GBC.eol());
        this.add(title, GBC.std().fill(GBC.HORIZONTAL).insets(4, 4, 4, 2));
        this.add(modes, GBC.eol().anchor(GBC.LINE_END).insets(0, 4, 4, 2));
        final var box = new JPanel(new GridBagLayout());
        final var border = Optional.ofNullable(UIManager.getColor("Separator.foreground")).orElse(Color.GRAY);
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(border),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        this.candidateDetail.setFont(font.deriveFont(font.getSize2D() * 0.9f));
        box.add(this.candidateName, GBC.eol().fill(GBC.HORIZONTAL));
        box.add(this.candidateDetail, GBC.eol().fill(GBC.HORIZONTAL));
        this.add(box, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 2));
        this.startButton.setFont(this.startButton.getFont().deriveFont(Font.BOLD));
        this.startButton.addActionListener(e -> this.start());
        this.pickButton.addActionListener(e -> this.pickAnother());
        this.add(MRGuiHelper.buttonRows(List.of(List.of(this.startButton, this.pickButton))),
                GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 2));
        this.autoBox.setSelected(AUTO_START.get());
        this.autoBox.setToolTipText(tr("Once an upload finishes a task, start the next one in its challenge"));
        this.autoBox.addActionListener(e -> AUTO_START.put(this.autoBox.isSelected()));
        this.add(this.autoBox, GBC.eol().anchor(GBC.LINE_START).insets(2, 0, 4, 4));
        this.add(new JSeparator(), GBC.eol().fill(GBC.HORIZONTAL).insets(4, 4, 4, 4));
        this.doneTitle.setFont(font.deriveFont(Font.BOLD));
        this.add(this.doneTitle, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 2));
        this.add(this.doneList, GBC.eol().fill(GBC.HORIZONTAL).insets(4, 0, 4, 4));
        // Keeps the content at the top
        this.add(new JPanel(), GBC.eol().fill(GBC.BOTH));
        MapRouletteClient.getInstance().addAuthListener(this.authListener);
        this.update();
    }

    /**
     * Remove the listeners. The current task panel calls this when it is destroyed.
     */
    void destroy() {
        MapRouletteClient.getInstance().removeAuthListener(this.authListener);
    }

    /**
     * Offer the next task after a finished one. Call on the EDT.
     *
     * @param task      The finished task
     * @param autoStart {@code true} to start the next task as soon as it is found
     */
    void show(Task task, boolean autoStart) {
        if (this.finished == null || this.finished.id() != task.id()) {
            this.finished = task;
            this.passed.clear();
            this.candidates = new Task[0];
            this.autoStart = autoStart;
            this.fetch();
        } else if (autoStart && this.candidate != null) {
            this.start();
        } else {
            this.autoStart |= autoStart;
        }
        this.refreshDone();
    }

    private void setNearby(boolean nearby) {
        if (NEARBY.get() != nearby) {
            NEARBY.put(nearby);
            this.passed.clear();
            this.fetch();
        }
    }

    /**
     * Ask MapRoulette for the next tasks
     */
    private void fetch() {
        final var task = this.finished;
        if (task == null) {
            return;
        }
        final var request = ++this.generation;
        this.loading = true;
        this.failed = false;
        this.candidate = null;
        this.update();
        final var proximity = NEARBY.get() ? task.id() : -1;
        MainApplication.worker.execute(() -> {
            try {
                final var tasks = ChallengeAPI.randomTask(task.parentId(), null, null, LIMIT, proximity);
                GuiHelper.runInEDT(() -> this.arrived(request, tasks == null ? new Task[0] : tasks));
            } catch (IOException e) {
                Logging.debug(e);
                GuiHelper.runInEDT(() -> this.arrived(request, null));
            }
        });
    }

    /**
     * Take MapRoulette's answer
     *
     * @param request The request it answers
     * @param tasks   The tasks, or {@code null} if the request failed
     */
    private void arrived(int request, @Nullable Task[] tasks) {
        if (request != this.generation) {
            return;
        }
        this.loading = false;
        this.failed = tasks == null;
        this.candidates = tasks == null ? new Task[0] : tasks;
        var next = pick(this.candidates, this.excluded());
        if (next == null && !this.passed.isEmpty()) {
            // Pick another went through them all: start over
            this.passed.clear();
            next = pick(this.candidates, this.excluded());
        }
        this.candidate = next;
        this.update();
        final var start = this.autoStart;
        this.autoStart = false;
        if (start && next != null) {
            this.start();
        }
    }

    /**
     * Offer another of the tasks MapRoulette sent, or ask again once they are used up
     */
    private void pickAnother() {
        if (this.candidate != null) {
            this.passed.add(this.candidate.id());
        }
        final var next = pick(this.candidates, this.excluded());
        if (next == null) {
            this.fetch();
        } else {
            this.candidate = next;
            this.update();
        }
    }

    /**
     * Start the offered task
     */
    private void start() {
        final var next = this.candidate;
        final var list = TaskListPanel.get();
        if (next != null && list != null && MapRouletteAuth.isAvailable()) {
            list.start(next);
        }
    }

    /**
     * Get the tasks not to offer: the finished one, those done this session, the started ones and those passed over
     *
     * @return The task ids
     */
    private Set<Long> excluded() {
        final var excluded = new HashSet<>(this.passed);
        if (this.finished != null) {
            excluded.add(this.finished.id());
        }
        TaskResults.session().forEach(done -> excluded.add(done.task().id()));
        ModifiedObjects.getLockedTasks().forEach(task -> excluded.add(task.id()));
        return excluded;
    }

    /**
     * Pick the next task
     *
     * @param candidates The tasks MapRoulette offered, in its order
     * @param excluded   The tasks not to offer
     * @return The first task that isn't excluded and can be finished in JOSM, or {@code null}
     */
    @Nullable
    static Task pick(Task[] candidates, Collection<Long> excluded) {
        for (var task : candidates) {
            // Choice tasks are answered in the Street Tally app
            if (!excluded.contains(task.id()) && !task.isChoiceTask()) {
                return task;
            }
        }
        return null;
    }

    private void update() {
        final var next = this.candidate;
        final var grey = Optional.ofNullable(UIManager.getColor("Label.disabledForeground")).orElse(Color.GRAY);
        final String name;
        final String detail;
        if (next != null) {
            name = TaskRows.readableName(next);
            detail = detail(next.id(), NEARBY.get());
        } else if (this.loading) {
            name = tr("Looking for the next task…");
            detail = null;
        } else if (this.failed) {
            name = tr("The next task could not be loaded.");
            detail = null;
        } else {
            name = NEARBY.get() ? tr("No other open tasks nearby in this challenge.")
                    : tr("No other open tasks in this challenge.");
            detail = null;
        }
        this.candidateName.setText(name);
        this.candidateDetail.setVisible(detail != null);
        this.candidateDetail.setText(detail == null ? ""
                : String.format("<html><span style=\"color:#%06x\">%s</span></html>", grey.getRGB() & 0xFFFFFF,
                        Utils.escapeReservedCharactersHTML(detail)));
        final var canStart = next != null && MapRouletteAuth.isAvailable();
        this.startButton.setEnabled(canStart);
        this.startButton
                .setToolTipText(next != null && !MapRouletteAuth.isAvailable() ? MapRouletteAuth.unavailableTooltip()
                        : next == null ? null : tr("Start task {0}", Long.toString(next.id())));
        this.pickButton.setText(next == null ? tr("Try again") : tr("Pick another"));
        this.pickButton.setEnabled(!this.loading && this.finished != null);
        this.revalidate();
        this.repaint();
    }

    /**
     * Say how the next task was picked
     *
     * @param taskId The task
     * @param nearby {@code true} if it is the nearest
     * @return Such as "Picked by the challenge's priorities · task 151"
     */
    static String detail(long taskId, boolean nearby) {
        final var id = Long.toString(taskId);
        return nearby ? tr("Nearest open task · task {0}", id)
                : tr("Picked by the challenge''s priorities · task {0}", id);
    }

    /**
     * Show the tasks done this session, oldest first
     */
    void refreshDone() {
        final var session = TaskResults.session();
        if (session.equals(this.shownSession)) {
            return;
        }
        this.shownSession = session;
        this.doneList.removeAll();
        this.doneTitle.setVisible(!session.isEmpty());
        this.doneList.setVisible(!session.isEmpty());
        final var font = this.doneTitle.getFont().deriveFont(Font.PLAIN, this.doneTitle.getFont().getSize2D() * 0.9f);
        for (var done : session) {
            final var task = new JLabel(
                    tr("Task {0} · {1}", Long.toString(done.task().id()), TaskRows.readableName(done.task())));
            task.setFont(font);
            final var outcome = new JMultilineLabel("<html>" + outcomeHtml(done.result()) + "</html>");
            outcome.setFont(font);
            outcome.addHyperlinkListener(e -> {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) {
                    OpenBrowser.displayUrl(e.getURL().toString());
                }
            });
            this.doneList.add(task, GBC.std().fill(GBC.HORIZONTAL).insets(0, 0, 8, 2));
            this.doneList.add(outcome, GBC.eol().anchor(GBC.LINE_END));
        }
        this.revalidate();
        this.repaint();
    }

    /**
     * Describe the outcome of a task done this session
     *
     * @param result What MapRoulette recorded
     * @return Such as "Fixed · changeset 701250", with the changeset as a link, as HTML without the html element
     */
    static String outcomeHtml(TaskResults.Result result) {
        final var label = Utils.escapeReservedCharactersHTML(
                result.status() == TaskStatus.SKIPPED ? tr("Skipped") : MRGuiHelper.statusLabel(result.status()));
        if (result.status() != TaskStatus.FIXED || result.link() == TaskResults.Link.NONE) {
            return label;
        } else if (result.link() == TaskResults.Link.FAILED) {
            return label + " · " + Utils.escapeReservedCharactersHTML(tr("not linked"));
        } else if (result.changesetId() <= 0) {
            return label;
        }
        final var changeset = Long.toString(result.changesetId());
        final var url = Config.getUrls().getBaseBrowseUrl() + "/changeset/" + changeset;
        return label + " · <a href=\"" + Utils.escapeReservedCharactersHTML(url) + "\">"
                + Utils.escapeReservedCharactersHTML(tr("changeset {0}", changeset)) + "</a>";
    }

    /**
     * Get the task offered
     *
     * @return The task, or {@code null}
     */
    @Nullable
    Task candidate() {
        return this.candidate;
    }
}
