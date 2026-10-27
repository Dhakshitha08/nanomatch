package com.nanomatch.gui;

import com.nanomatch.model.Side;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

/** Small reusable pieces of the interface: cards, tiles, buttons, badges, table styling and number formats. */
final class Ui {

    private Ui() {
    }

    // ------------------------------------------------------------------------------------------- formats

    private static final DecimalFormat INTEGER = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    static String integer(long value) {
        synchronized (INTEGER) {
            return INTEGER.format(value);
        }
    }

    static String price(long price) {
        return price == 0 ? "—" : Long.toString(price);
    }

    static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis));
    }

    /** Nanoseconds as a readable duration: 950 ns, 12.4 µs, 3.21 ms. */
    static String nanos(double nanos) {
        if (nanos < 1_000) {
            return String.format(Locale.ROOT, "%.0f ns", nanos);
        }
        if (nanos < 1_000_000) {
            return String.format(Locale.ROOT, nanos < 10_000 ? "%.2f µs" : "%.1f µs", nanos / 1_000);
        }
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000);
    }

    static String micros(double nanos) {
        double us = nanos / 1_000;
        return String.format(Locale.ROOT, us < 10 ? "%.2f" : us < 100 ? "%.1f" : "%.0f", us);
    }

    static String millionsPerSecond(double opsPerSecond) {
        return String.format(Locale.ROOT, "%.2f", opsPerSecond / 1_000_000);
    }

    // ------------------------------------------------------------------------------------------- labels

    static JLabel label(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    static JLabel title(String text) {
        return label(text, Theme.ui(Font.BOLD, 22), Theme.TEXT);
    }

    static JLabel heading(String text) {
        return label(text, Theme.ui(Font.BOLD, 15), Theme.TEXT);
    }

    static JLabel muted(String text) {
        return label(text, Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
    }

    /** A label that wraps its text at the given width. */
    static JLabel wrapped(String text, int width, Color color) {
        JLabel label = new JLabel("<html><div style='width:" + width + "px'>" + escape(text) + "</div></html>");
        label.setFont(Theme.ui(Font.PLAIN, 13));
        label.setForeground(color);
        return label;
    }

    /** Plain text that wraps at whatever width its container gives it. */
    static javax.swing.JTextArea textBlock(String text, Font font, Color color) {
        javax.swing.JTextArea area = new javax.swing.JTextArea(text) {
            @Override
            public Dimension getPreferredSize() {
                // wrap to the parent's width instead of growing sideways
                if (getParent() != null && getParent().getWidth() > 0) {
                    setSize(getParent().getWidth() - getParent().getInsets().left - getParent().getInsets().right,
                            Short.MAX_VALUE);
                }
                return super.getPreferredSize();
            }
        };
        area.setUI(new javax.swing.plaf.basic.BasicTextAreaUI()); // plain: no Nimbus background or border
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(font);
        area.setForeground(color);
        area.setHighlighter(null);
        return area;
    }

    static JLabel html(String html, int width) {
        JLabel label = new JLabel("<html><div style='width:" + width + "px'>" + html + "</div></html>");
        label.setFont(Theme.ui(Font.PLAIN, 13));
        label.setForeground(Theme.TEXT);
        return label;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Page header: big title and one explanatory line. */
    static JPanel pageHeader(String title, String subtitle) {
        JPanel header = transparent(new BorderLayout(0, 4));
        header.add(title(title), BorderLayout.NORTH);
        header.add(muted(subtitle), BorderLayout.CENTER);
        header.setBorder(BorderFactory.createEmptyBorder(0, 2, 10, 2));
        return header;
    }

    static JPanel transparent(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    static JPanel row(int gap, Component... components) {
        JPanel row = transparent(new FlowLayout(FlowLayout.LEFT, gap, 0));
        for (Component c : components) {
            row.add(c);
        }
        return row;
    }

    // ------------------------------------------------------------------------------------------- card

    /** A rounded panel with an optional title line. */
    static class Card extends JPanel {
        private final JLabel titleLabel;
        private final JPanel headerRight;

        Card(String title) {
            this(title, null);
        }

        Card(String title, String subtitle) {
            super(new BorderLayout(0, 8));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            JPanel header = transparent(new BorderLayout(8, 2));
            titleLabel = label(title == null ? "" : title, Theme.ui(Font.BOLD, 14), Theme.TEXT);
            JPanel left = transparent(new BorderLayout(0, 2));
            left.add(titleLabel, BorderLayout.NORTH);
            if (subtitle != null) {
                left.add(label(subtitle, Theme.ui(Font.PLAIN, 12), Theme.MUTED), BorderLayout.CENTER);
            }
            header.add(left, BorderLayout.CENTER);
            headerRight = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            header.add(headerRight, BorderLayout.EAST);
            if (title != null) {
                super.add(header, BorderLayout.NORTH);
            }
        }

        Card body(Component content) {
            add(content, BorderLayout.CENTER);
            return this;
        }

        Card headerRight(Component component) {
            headerRight.add(component);
            return this;
        }

        void setTitle(String title) {
            titleLabel.setText(title);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.PANEL);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.setColor(Theme.BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------------------------------- stat tile

    /** A key figure: small caption, large value, optional note, coloured accent bar. */
    static final class StatTile extends JComponent {
        private String caption;
        private String value = "—";
        private String note = "";
        private Color accent;
        private Color valueColor = Theme.TEXT;

        StatTile(String caption, Color accent) {
            this.caption = caption;
            this.accent = accent;
            setPreferredSize(new Dimension(170, 86));
            setMinimumSize(new Dimension(120, 80));
        }

        void set(String value, String note) {
            this.value = value;
            this.note = note == null ? "" : note;
            repaint();
        }

        void setValueColor(Color color) {
            this.valueColor = color;
            repaint();
        }

        void setAccent(Color color) {
            this.accent = color;
            repaint();
        }

        void setCaption(String caption) {
            this.caption = caption;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(Theme.PANEL);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g2.setColor(Theme.BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g2.setColor(accent);
            g2.fillRoundRect(0, 10, 4, h - 20, 4, 4);
            g2.setFont(Theme.ui(Font.PLAIN, 12));
            g2.setColor(Theme.MUTED);
            g2.drawString(caption.toUpperCase(Locale.ROOT), 14, 22);
            g2.setFont(fitFont(g2, value, Theme.mono(Font.BOLD, 22), w - 24));
            g2.setColor(valueColor);
            g2.drawString(value, 14, 52);
            g2.setFont(Theme.ui(Font.PLAIN, 11.5f));
            g2.setColor(Theme.FAINT);
            g2.drawString(clip(g2, note, w - 24), 14, 72);
            g2.dispose();
        }
    }

    static Font fitFont(Graphics2D g2, String text, Font font, int width) {
        Font f = font;
        while (f.getSize() > 11 && g2.getFontMetrics(f).stringWidth(text) > width) {
            f = f.deriveFont((float) f.getSize() - 1);
        }
        return f;
    }

    static String clip(Graphics2D g2, String text, int width) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        String s = text;
        while (!s.isEmpty() && fm.stringWidth(s + "…") > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    // ------------------------------------------------------------------------------------------- button

    /** A flat, rounded button in a given colour. */
    static final class FlatButton extends JButton {
        private Color base;
        private boolean hover;

        FlatButton(String text, Color base) {
            super(text);
            this.base = base;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setForeground(Color.WHITE);
            setFont(Theme.ui(Font.BOLD, 13));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(8, 16, 8, 16));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        void setBase(Color color) {
            this.base = color;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(getText()) + 32, fm.getHeight() + 16);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color fill = !isEnabled() ? Theme.PANEL_3
                    : getModel().isPressed() ? base.darker() : hover ? Theme.blend(base, Color.WHITE, 0.12) : base;
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(isEnabled() ? getForeground() : Theme.FAINT);
            g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    static FlatButton primary(String text) {
        return new FlatButton(text, new Color(0x2F6FDB));
    }

    static FlatButton secondary(String text) {
        FlatButton button = new FlatButton(text, Theme.PANEL_3);
        button.setForeground(Theme.TEXT);
        return button;
    }

    static FlatButton sideButton(Side side) {
        return new FlatButton(side == Side.BUY ? "BUY" : "SELL", side == Side.BUY ? Theme.BUY : Theme.SELL);
    }

    // ------------------------------------------------------------------------------------------- badge

    /** A small rounded label ("pill"). */
    static final class Badge extends JLabel {
        private Color fill;

        Badge(String text, Color fill, Color foreground) {
            super(text, SwingConstants.CENTER);
            this.fill = fill;
            setForeground(foreground);
            setFont(Theme.ui(Font.BOLD, 11.5f));
            setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        }

        void set(String text, Color fill, Color foreground) {
            setText(text);
            this.fill = fill;
            setForeground(foreground);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** A coloured status dot followed by text. */
    static final class StatusDot extends JLabel {
        private Color color;

        StatusDot(String text, Color color) {
            super(text);
            this.color = color;
            setForeground(Theme.TEXT);
            setFont(Theme.ui(Font.BOLD, 12.5f));
            setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
        }

        void set(String text, Color color) {
            setText(text);
            this.color = color;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = 9;
            int y = (getHeight() - d) / 2;
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
            g2.fillOval(1, y - 3, d + 6, d + 6);
            g2.setColor(color);
            g2.fillOval(4, y, d, d);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ------------------------------------------------------------------------------------------- tables

    static JScrollPane scroll(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.setBackground(Theme.PANEL);
        styleScrollBar(scroll.getVerticalScrollBar());
        styleScrollBar(scroll.getHorizontalScrollBar());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    static void styleScrollBar(JScrollBar bar) {
        bar.setPreferredSize(new Dimension(10, 10));
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = Theme.PANEL_3;
                trackColor = Theme.PANEL;
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, java.awt.Rectangle r) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isDragging ? Theme.FAINT : Theme.PANEL_3);
                g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
                g2.dispose();
            }

            private JButton zeroButton() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                return b;
            }
        });
    }

    static void styleTable(JTable table) {
        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBackground(Theme.PANEL);
        table.setForeground(Theme.TEXT);
        table.setSelectionBackground(Theme.SELECTION);
        table.setSelectionForeground(Color.WHITE);
        table.setFillsViewportHeight(true);
        table.setFont(Theme.mono(Font.PLAIN, 13));
        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(t, value, false, false, row, column);
                label.setOpaque(true);
                label.setBackground(Theme.PANEL_2);
                label.setForeground(Theme.MUTED);
                label.setFont(Theme.ui(Font.BOLD, 12));
                label.setHorizontalAlignment(column == 0 ? SwingConstants.LEFT : SwingConstants.RIGHT);
                label.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                        BorderFactory.createEmptyBorder(6, 10, 6, 10)));
                return label;
            }
        });
        CellRenderer renderer = new CellRenderer();
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(Long.class, renderer);
        table.setDefaultRenderer(Integer.class, renderer);
        table.setDefaultRenderer(String.class, renderer);
        table.setDefaultRenderer(Side.class, renderer);
    }

    /** Monospaced, right-aligned numbers; BUY / SELL coloured; zebra rows. */
    static class CellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, selected, false, row, column);
            label.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            label.setFont(Theme.mono(Font.PLAIN, 13));
            Color background = row % 2 == 0 ? Theme.PANEL : Theme.PANEL_2;
            label.setBackground(selected ? Theme.SELECTION : background);
            label.setForeground(Theme.TEXT);
            if (value instanceof Side side) {
                label.setText(side.name());
                label.setForeground(Theme.sideColor(side));
                label.setFont(Theme.mono(Font.BOLD, 13));
            } else if (value instanceof Long || value instanceof Integer) {
                label.setText(integer(((Number) value).longValue()));
            } else if (value instanceof String s) {
                if (s.startsWith("BUY")) {
                    label.setForeground(Theme.BUY);
                } else if (s.startsWith("SELL")) {
                    label.setForeground(Theme.SELL);
                }
            }
            label.setHorizontalAlignment(column == 0 ? SwingConstants.LEFT : SwingConstants.RIGHT);
            return label;
        }
    }

    // ------------------------------------------------------------------------------------------- drawing

    static void antialias(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    static void dashed(Graphics2D g2, float width) {
        g2.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] {4, 4}, 0));
    }
}
