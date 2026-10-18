// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.io.Serial;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.TableCellRenderer;

import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;
import org.openstreetmap.josm.tools.Utils;

/**
 * Draws a task list row: a challenge header above the first task of each challenge, then the task's readable name
 * with a status chip, and a details line (#112)
 */
final class TaskRowRenderer extends JPanel implements TableCellRenderer {
    @Serial
    private static final long serialVersionUID = 2617457330442946311L;
    private static final Color HEADER_BACKGROUND = new Color(0xf0f0f0);
    private static final Color HEADER_FOREGROUND = new Color(0x444444);
    private static final Color DIVIDER = new Color(0xdcdcdc);
    private static final Color GREYED = new Color(0x6a6a6a);

    private final JLabel header = new JLabel();
    private final JPanel body = new JPanel(new BorderLayout(6, 2));
    private final JLabel name = new JLabel();
    private final JLabel chip = new JLabel();
    private final JLabel symbol = new JLabel();
    private final JLabel details = new JLabel();
    /**
     * The number of listed tasks of a challenge
     */
    private final LongUnaryOperator challengeSize;
    /**
     * Where distances are measured from
     */
    private final Supplier<ILatLon> from;
    /**
     * Gets the readable name of a task
     */
    private final Function<TaskClusteredPoint, String> names;

    /**
     * Create a new renderer
     *
     * @param challengeSize The number of listed tasks of a challenge, by challenge id
     * @param from          Where distances are measured from; it may return {@code null}
     * @param names         Gets the readable name of a task
     */
    TaskRowRenderer(LongUnaryOperator challengeSize, Supplier<ILatLon> from,
            Function<TaskClusteredPoint, String> names) {
        super(new BorderLayout());
        this.challengeSize = challengeSize;
        this.from = from;
        this.names = names;
        // Task and challenge names come from challenge authors: show them as text, never as HTML
        for (var label : new JLabel[] { this.header, this.name, this.chip, this.symbol, this.details }) {
            label.putClientProperty("html.disable", Boolean.TRUE);
        }
        this.header.setOpaque(true);
        this.header.setBackground(HEADER_BACKGROUND);
        this.header.setForeground(HEADER_FOREGROUND);
        this.header.setFont(this.header.getFont().deriveFont(Font.BOLD));
        this.header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, DIVIDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        this.chip.setOpaque(true);
        this.chip.setFont(this.chip.getFont().deriveFont(this.chip.getFont().getSize2D() - 1f));
        this.details.setFont(this.details.getFont().deriveFont(this.details.getFont().getSize2D() - 1f));
        final var line = new JPanel(new BorderLayout(6, 0));
        line.setOpaque(false);
        line.add(this.symbol, BorderLayout.LINE_START);
        line.add(this.name, BorderLayout.CENTER);
        line.add(this.chip, BorderLayout.LINE_END);
        this.body.add(line, BorderLayout.NORTH);
        this.body.add(this.details, BorderLayout.SOUTH);
        this.add(this.header, BorderLayout.NORTH);
        this.add(this.body, BorderLayout.CENTER);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
            int row, int column) {
        if (!(value instanceof TaskClusteredPoint point)) {
            return this;
        }
        final var previous = row > 0 ? table.getValueAt(row - 1, column) : null;
        final var first = !(previous instanceof TaskClusteredPoint previousPoint)
                || previousPoint.parentId() != point.parentId();
        this.header.setVisible(first);
        if (first) {
            final var size = this.challengeSize.applyAsLong(point.parentId());
            this.header.setText(TaskRows.challengeName(point) + " · " + trn("{0} task", "{0} tasks", size, size));
        }
        final var rowChip = TaskRows.chip(point);
        final var readableName = this.names.apply(point);
        this.name.setText(readableName);
        // The name can be cut off in a narrow panel; the challenge is only in the group header
        this.setToolTipText(tooltip(readableName, TaskRows.challengeName(point)));
        this.name.setFont(this.name.getFont().deriveFont(rowChip == TaskRows.Chip.STARTED ? Font.BOLD : Font.PLAIN));
        this.details.setText(TaskRows.details(point, this.from.get()));
        styleChip(rowChip);
        // The same symbol as the task's marker on the map (#113)
        this.symbol.setIcon(TaskMarker.icon(TaskMarker.kind(point), isSelected));

        final Color background;
        final Color foreground;
        if (isSelected) {
            background = table.getSelectionBackground();
            foreground = table.getSelectionForeground();
        } else {
            background = table.getBackground();
            foreground = rowChip == TaskRows.Chip.LOCKED ? GREYED : table.getForeground();
        }
        this.body.setBackground(background);
        this.setBackground(background);
        this.name.setForeground(foreground);
        this.details.setForeground(foreground);
        final Border focus = hasFocus ? UIManager.getBorder("Table.focusCellHighlightBorder") : null;
        final Border padding = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, DIVIDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8));
        this.body.setBorder(focus == null ? padding : BorderFactory.createCompoundBorder(focus, padding));

        final var height = this.getPreferredSize().height;
        if (table.getRowHeight(row) != height) {
            table.setRowHeight(row, height);
        }
        return this;
    }

    /**
     * Get the tooltip of a row
     *
     * @param readableName The task's readable name
     * @param challenge    The challenge name
     * @return The full name and the challenge, one per line
     */
    static String tooltip(String readableName, String challenge) {
        return "<html>" + Utils.escapeReservedCharactersHTML(readableName) + "<br>"
                + Utils.escapeReservedCharactersHTML(challenge) + "</html>";
    }

    /**
     * Set the text and colours of the chip
     *
     * @param rowChip The chip to show
     */
    private void styleChip(TaskRows.Chip rowChip) {
        this.chip.setText(rowChip.label());
        final Color background;
        final Color foreground;
        final Color line;
        boolean dashed = false;
        switch (rowChip) {
        case STARTED -> {
            background = new Color(0x2e7d32);
            foreground = Color.WHITE;
            line = background;
        }
        case DONE -> {
            background = new Color(0x1f5fad);
            foreground = Color.WHITE;
            line = background;
        }
        case SKIPPED, CANT_COMPLETE -> {
            background = new Color(0xfff3d6);
            foreground = new Color(0x6b4a00);
            line = new Color(0xd9a530);
        }
        case LOCKED, UNAVAILABLE -> {
            background = new Color(0xf2f2f2);
            foreground = new Color(0x555555);
            line = new Color(0x999999);
            dashed = true;
        }
        default -> {
            background = new Color(0xeeeeee);
            foreground = new Color(0x333333);
            line = new Color(0xbbbbbb);
        }
        }
        this.chip.setBackground(background);
        this.chip.setForeground(foreground);
        final Border outline = dashed ? BorderFactory.createDashedBorder(line, 3, 2)
                : BorderFactory.createLineBorder(line);
        this.chip.setBorder(BorderFactory.createCompoundBorder(outline, BorderFactory.createEmptyBorder(0, 6, 0, 6)));
    }
}
