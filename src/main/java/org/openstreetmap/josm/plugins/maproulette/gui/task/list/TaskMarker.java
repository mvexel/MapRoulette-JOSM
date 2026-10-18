// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.maproulette.gui.task.list;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

import javax.swing.Icon;

import org.openstreetmap.josm.plugins.maproulette.api.model.TaskClusteredPoint;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * The symbol of a task, the same on the map and in the list (#113): an open ring, a filled marker for the user's
 * started task, a muted check for a done task, and a dashed ring for a task another mapper holds. A selected task gets
 * a highlight ring.
 */
public final class TaskMarker {
    /** The blue of open and started tasks */
    public static final Color BLUE = new Color(0x1f5fad);
    /** The highlight of selected tasks */
    public static final Color HIGHLIGHT = new Color(0xffcf3f);
    /** The fill of done tasks */
    public static final Color DONE = new Color(0x8c9a8c);
    /** The ring of done tasks */
    static final Color DONE_RING = new Color(0xc8d2c8);
    /** The dashed ring of locked tasks */
    static final Color LOCKED = new Color(0x5a5a5a);
    /** Diameter of an open marker on the map */
    public static final int MAP_SIZE = 22;
    /** Diameter of the user's started task on the map */
    public static final int STARTED_SIZE = 30;
    /** Diameter of a group marker on the map */
    public static final int GROUP_SIZE = 36;

    /**
     * What a marker shows
     */
    public enum Kind {
        /** Open to work on, skipped, or can't complete: an open ring */
        OPEN,
        /** The user's started task: filled, with its number */
        STARTED,
        /** Done: muted, with a check */
        DONE,
        /** Another mapper holds it, or it is deleted or disabled: dashed */
        LOCKED;

        /**
         * Get the legend text
         *
         * @return The text
         */
        @Nonnull
        public String label() {
            return switch (this) {
            case OPEN -> tr("Open");
            case STARTED -> tr("Your started task");
            case DONE -> tr("Done");
            case LOCKED -> tr("Locked by someone else");
            };
        }
    }

    private TaskMarker() {
        // Hide the constructor
    }

    /**
     * Get the kind of marker of a task
     *
     * @param point The task
     * @return The kind
     */
    @Nonnull
    public static Kind kind(@Nonnull TaskClusteredPoint point) {
        return switch (TaskRows.chip(point)) {
        case STARTED -> Kind.STARTED;
        case DONE -> Kind.DONE;
        case LOCKED, UNAVAILABLE -> Kind.LOCKED;
        case OPEN, SKIPPED, CANT_COMPLETE -> Kind.OPEN;
        };
    }

    /**
     * Get the diameter of a marker on the map
     *
     * @param kind The kind
     * @return The diameter in pixels
     */
    public static int size(@Nonnull Kind kind) {
        return switch (kind) {
        case STARTED -> STARTED_SIZE;
        case OPEN -> MAP_SIZE;
        case DONE, LOCKED -> MAP_SIZE - 2;
        };
    }

    /**
     * Paint a marker
     *
     * @param g        The graphics
     * @param kind     The kind
     * @param x        The centre
     * @param y        The centre
     * @param size     The diameter
     * @param selected {@code true} to add the highlight ring
     * @param number   The text in a started marker, or {@code null}
     */
    public static void paint(@Nonnull Graphics2D g, @Nonnull Kind kind, double x, double y, int size,
            boolean selected, @Nullable String number) {
        final var g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final float line = Math.max(1.5f, size * 3f / MAP_SIZE);
            if (selected) {
                final var ring = size + 2 * line + 4;
                g2.setColor(HIGHLIGHT);
                g2.setStroke(new BasicStroke(line));
                g2.draw(circle(x, y, ring - line));
            }
            final var inner = circle(x, y, size - line);
            switch (kind) {
            case OPEN -> {
                g2.setColor(Color.WHITE);
                g2.fill(inner);
                g2.setColor(BLUE);
                g2.setStroke(new BasicStroke(line));
                g2.draw(inner);
            }
            case STARTED -> {
                g2.setColor(BLUE);
                g2.fill(inner);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(line));
                g2.draw(inner);
                if (number != null) {
                    g2.setFont(g2.getFont().deriveFont(Font.BOLD, size * 0.36f));
                    final var metrics = g2.getFontMetrics();
                    g2.drawString(number, (float) (x - metrics.stringWidth(number) / 2.0),
                            (float) (y + (metrics.getAscent() - metrics.getDescent()) / 2.0));
                }
            }
            case DONE -> {
                g2.setColor(DONE);
                g2.fill(inner);
                g2.setColor(DONE_RING);
                g2.setStroke(new BasicStroke(line * 2 / 3));
                g2.draw(inner);
                if (size >= 12) {
                    // A check mark
                    final var r = size / 2.0;
                    final var check = new Path2D.Double();
                    check.moveTo(x - r * 0.45, y + r * 0.02);
                    check.lineTo(x - r * 0.1, y + r * 0.38);
                    check.lineTo(x + r * 0.48, y - r * 0.35);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(Math.max(1.5f, size / 10f), BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g2.draw(check);
                }
            }
            case LOCKED -> {
                // White dashes over a dark ring: visible on light and dark imagery
                g2.setColor(new Color(255, 255, 255, 90));
                g2.fill(inner);
                g2.setColor(new Color(LOCKED.getRed(), LOCKED.getGreen(), LOCKED.getBlue(), 200));
                g2.setStroke(new BasicStroke(line));
                g2.draw(inner);
                final var dash = Math.max(2f, size / 6f);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(line * 2 / 3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                        new float[] { dash, dash * 0.8f }, 0f));
                g2.draw(inner);
            }
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * Paint a group of tasks that are too close to tell apart
     *
     * @param g     The graphics
     * @param x     The centre
     * @param y     The centre
     * @param count The number of tasks
     */
    public static void paintGroup(@Nonnull Graphics2D g, double x, double y, int count) {
        final var g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final var circle = circle(x, y, GROUP_SIZE - 3);
            g2.setColor(new Color(BLUE.getRed(), BLUE.getGreen(), BLUE.getBlue(), 220));
            g2.fill(circle);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(3f));
            g2.draw(circle);
            final var text = Integer.toString(count);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, count > 99 ? 11f : 13f));
            final var metrics = g2.getFontMetrics();
            g2.drawString(text, (float) (x - metrics.stringWidth(text) / 2.0),
                    (float) (y + (metrics.getAscent() - metrics.getDescent()) / 2.0));
        } finally {
            g2.dispose();
        }
    }

    /**
     * Paint a label next to a marker, such as "Your task"
     *
     * @param g          The graphics
     * @param text       The text
     * @param x          The left edge
     * @param y          The vertical centre
     * @param background The background
     * @param foreground The text colour
     */
    public static void paintLabel(@Nonnull Graphics2D g, @Nonnull String text, double x, double y,
            @Nonnull Color background, @Nonnull Color foreground) {
        final var g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(g2.getFont().deriveFont(12f));
            final var metrics = g2.getFontMetrics();
            final var width = metrics.stringWidth(text) + 12;
            final var height = metrics.getHeight() + 2;
            g2.setColor(background);
            g2.fillRoundRect((int) x, (int) (y - height / 2.0), width, height, 6, 6);
            g2.setColor(foreground);
            g2.drawString(text, (float) x + 6, (float) (y + (metrics.getAscent() - metrics.getDescent()) / 2.0));
        } finally {
            g2.dispose();
        }
    }

    /**
     * Get the symbol of a task as an icon, for the list and the legend
     *
     * @param kind     The kind
     * @param selected {@code true} to add the highlight ring
     * @return The icon
     */
    @Nonnull
    public static Icon icon(@Nonnull Kind kind, boolean selected) {
        return new MarkerIcon(kind, selected);
    }

    /**
     * Get the symbol of a group as an icon, for the legend
     *
     * @return The icon
     */
    @Nonnull
    public static Icon groupIcon() {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                final var g2 = (Graphics2D) g.create();
                try {
                    // Drawn at map size, scaled down
                    g2.translate(x, y);
                    g2.scale(16.0 / GROUP_SIZE, 16.0 / GROUP_SIZE);
                    paintGroup(g2, GROUP_SIZE / 2.0, GROUP_SIZE / 2.0, 12);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    private static Ellipse2D circle(double x, double y, double diameter) {
        return new Ellipse2D.Double(x - diameter / 2, y - diameter / 2, diameter, diameter);
    }

    /**
     * A marker as an icon
     *
     * @param kind     The kind
     * @param selected {@code true} to add the highlight ring
     */
    private record MarkerIcon(Kind kind, boolean selected) implements Icon {
        /** The size of the symbol without the highlight ring */
        private static final int SIZE = 14;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            final var centre = getIconWidth() / 2.0;
            paint((Graphics2D) g, this.kind, x + centre, y + centre, this.selected ? SIZE - 4 : SIZE, this.selected,
                    null);
        }

        @Override
        public int getIconWidth() {
            return SIZE + 4;
        }

        @Override
        public int getIconHeight() {
            return SIZE + 4;
        }
    }
}
