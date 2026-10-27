package com.nanomatch.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;

/** Simple visual explanations of the matching rules, the two engine designs and the measurements. */
final class HowItWorksPanel extends JPanel {

    HowItWorksPanel() {
        super(new BorderLayout(0, 12));
        setOpaque(false);
        add(Ui.pageHeader("How It Works", "The ideas behind NanoMatch in six pictures. Scroll down."),
                BorderLayout.NORTH);
        Column column = new Column();
        column.add(card("1.  The life of an order", new Lifecycle()));
        column.add(card("2.  Price-time priority", new PriceTime()));
        column.add(card("3.  Execution price", new ExecutionPrice()));
        column.add(card("4.  The lock-based engine (baseline)", new LockBased()));
        column.add(card("5.  The lock-free engine: ring buffer + one matching thread", new LockFree()));
        column.add(card("6.  How performance is measured", new Measurement()));
        JScrollPane scroll = Ui.scroll(column);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);
    }

    private static JComponent card(String title, Diagram diagram) {
        Ui.Card card = new Ui.Card(title, null).body(diagram);
        card.setAlignmentX(LEFT_ALIGNMENT);
        JPanel wrapper = Ui.transparent(new BorderLayout());
        wrapper.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 12, 0));
        wrapper.add(card);
        return wrapper;
    }

    /** Vertical list that tracks the viewport width, so diagrams scale instead of scrolling sideways. */
    private static final class Column extends JPanel implements Scrollable {
        Column() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return r.height - 40;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // ============================================================================================ drawing base

    /** A drawing made in a fixed 1000-unit-wide design space, scaled to the available width. */
    private abstract static class Diagram extends JComponent {
        static final int DESIGN_W = 1000;
        private final int designH;

        Diagram(int designH) {
            this.designH = designH;
            setOpaque(false);
        }

        private double scale() {
            int w = getWidth() > 0 ? getWidth() : DESIGN_W;
            return Math.min(1.0, Math.max(0.55, w / (double) DESIGN_W));
        }

        @Override
        public Dimension getPreferredSize() {
            int w = getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth() : DESIGN_W;
            double s = Math.min(1.0, Math.max(0.55, w / (double) DESIGN_W));
            return new Dimension(Math.max(300, w - 4), (int) (designH * s));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Ui.antialias(g2);
            double s = scale();
            double offset = Math.max(0, (getWidth() - DESIGN_W * s) / 2);
            g2.transform(AffineTransform.getTranslateInstance(offset, 0));
            g2.scale(s, s);
            draw(g2);
            g2.dispose();
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            boolean changed = width != getWidth();
            super.setBounds(x, y, width, height);
            if (changed) {
                revalidate();
            }
        }

        abstract void draw(Graphics2D g);

        // ---- helpers

        static void box(Graphics2D g, int x, int y, int w, int h, Color fill, Color border, String... lines) {
            g.setColor(fill);
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(border);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(x, y, w, h, 14, 14);
            g.setStroke(new BasicStroke(1));
            int lineH = 18;
            int top = y + (h - lines.length * lineH) / 2 + 14;
            for (int i = 0; i < lines.length; i++) {
                g.setFont(i == 0 ? Theme.ui(Font.BOLD, 14) : Theme.ui(Font.PLAIN, 12.5f));
                g.setColor(i == 0 ? Theme.TEXT : Theme.MUTED);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(lines[i], x + (w - fm.stringWidth(lines[i])) / 2, top + i * lineH);
            }
        }

        static void arrow(Graphics2D g, int x1, int y1, int x2, int y2, Color color) {
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(x1, y1, x2, y2);
            double angle = Math.atan2(y2 - y1, x2 - x1);
            Path2D head = new Path2D.Double();
            head.moveTo(x2, y2);
            head.lineTo(x2 - 11 * Math.cos(angle - 0.4), y2 - 11 * Math.sin(angle - 0.4));
            head.lineTo(x2 - 11 * Math.cos(angle + 0.4), y2 - 11 * Math.sin(angle + 0.4));
            head.closePath();
            g.fill(head);
            g.setStroke(new BasicStroke(1));
        }

        static void text(Graphics2D g, String s, int x, int y, Font font, Color color) {
            g.setFont(font);
            g.setColor(color);
            g.drawString(s, x, y);
        }

        static void centered(Graphics2D g, String s, int cx, int y, Font font, Color color) {
            g.setFont(font);
            g.setColor(color);
            g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
        }

        static void chip(Graphics2D g, int x, int y, String s, Color fill) {
            g.setFont(Theme.mono(Font.BOLD, 12.5f));
            int w = g.getFontMetrics().stringWidth(s) + 16;
            g.setColor(fill);
            g.fillRoundRect(x, y, w, 24, 8, 8);
            g.setColor(Theme.TEXT);
            g.drawString(s, x + 8, y + 17);
        }

        static void numberBadge(Graphics2D g, int cx, int cy, String n, Color color) {
            g.setColor(color);
            g.fillOval(cx - 11, cy - 11, 22, 22);
            centered(g, n, cx, cy + 5, Theme.ui(Font.BOLD, 12), Color.WHITE);
        }
    }

    // ============================================================================================ diagrams

    private static final class Lifecycle extends Diagram {
        Lifecycle() {
            super(250);
        }

        @Override
        void draw(Graphics2D g) {
            Color edge = Theme.BORDER;
            box(g, 10, 30, 200, 80, Theme.PANEL_2, Theme.ACCENT, "Client thread", "submit(id, side,", "price, quantity)");
            arrow(g, 212, 70, 262, 70, Theme.ACCENT);
            box(g, 265, 30, 210, 80, Theme.PANEL_2, edge, "Gets a ticket", "its place in the single", "processing order");
            arrow(g, 477, 70, 527, 70, Theme.ACCENT);
            box(g, 530, 30, 210, 80, Theme.PANEL_2, edge, "Matched", "against the opposite side:", "best price, then FIFO");
            arrow(g, 742, 70, 792, 70, Theme.WARN);
            box(g, 795, 30, 195, 80, Theme.PANEL_2, Theme.WARN, "Trade(s) reported", "TradeSink.onTrade(buy,", "sell, price, qty)");
            arrow(g, 635, 112, 635, 152, Theme.MUTED);
            box(g, 530, 155, 210, 70, Theme.PANEL_2, edge, "Quantity left over?", "it rests on the book", "and waits (no cancel)");
            text(g, "Only limit orders. A BUY crosses when its price ≥ the best SELL price;", 10, 175,
                    Theme.ui(Font.PLAIN, 13), Theme.TEXT);
            text(g, "a SELL crosses when its price ≤ the best BUY price.", 10, 195, Theme.ui(Font.PLAIN, 13), Theme.TEXT);
            text(g, "Both engines follow exactly these rules (MatchingEngine).", 10, 220, Theme.ui(Font.PLAIN, 12.5f),
                    Theme.MUTED);
        }
    }

    private static final class PriceTime extends Diagram {
        PriceTime() {
            super(280);
        }

        @Override
        void draw(Graphics2D g) {
            text(g, "SELL side of the book (asks)", 20, 24, Theme.ui(Font.BOLD, 12.5f), Theme.MUTED);
            int[] prices = {102, 101, 100};
            String[][] queues = {{"#1 · 5"}, {"#4 · 5"}, {"#2 · 5", "#3 · 5"}};
            int y = 40;
            for (int i = 0; i < 3; i++) {
                g.setColor(i == 2 ? new Color(0x3A1D24) : Theme.PANEL_2);
                g.fillRoundRect(20, y, 420, 40, 10, 10);
                text(g, Integer.toString(prices[i]), 36, y + 26, Theme.mono(Font.BOLD, 16), Theme.SELL);
                int x = 110;
                for (String q : queues[i]) {
                    chip(g, x, y + 8, q, Theme.PANEL_3);
                    x += 90;
                }
                if (i == 2) {
                    text(g, "best ask", 340, y + 25, Theme.ui(Font.ITALIC, 12), Theme.SELL);
                }
                y += 48;
            }
            text(g, "time →  (left = arrived first)", 110, y + 12, Theme.ui(Font.ITALIC, 12), Theme.FAINT);
            numberBadge(g, 106, 140, "1", Theme.WARN);   // #2 at 100
            numberBadge(g, 196, 140, "2", Theme.WARN);   // #3 at 100
            numberBadge(g, 106, 92, "3", Theme.WARN);    // #4 at 101

            box(g, 470, 108, 170, 60, new Color(0x123B2C), Theme.BUY, "Incoming BUY #5", "12 @ 101");
            arrow(g, 468, 138, 445, 148, Theme.BUY);

            Font f = Theme.ui(Font.PLAIN, 13.5f);
            text(g, "Order of filling for BUY #5:", 670, 50, Theme.ui(Font.BOLD, 14), Theme.TEXT);
            text(g, "1.  #2 at 100: 5   (best price, arrived first)", 670, 80, f, Theme.TEXT);
            text(g, "2.  #3 at 100: 5   (same price, arrived later)", 670, 105, f, Theme.TEXT);
            text(g, "3.  #4 at 101: 2   (next best price; #4 keeps 3)", 670, 130, f, Theme.TEXT);
            text(g, "#1 at 102 is not touched: 102 > the limit 101.", 670, 160, f, Theme.MUTED);
            text(g, "Price priority: best price first.", 670, 200, Theme.ui(Font.BOLD, 13.5f), Theme.WARN);
            text(g, "Time priority: at the same price, first come,", 670, 225, Theme.ui(Font.BOLD, 13.5f), Theme.WARN);
            text(g, "first served (FIFO queue per price level).", 670, 245, Theme.ui(Font.BOLD, 13.5f), Theme.WARN);
        }
    }

    private static final class ExecutionPrice extends Diagram {
        ExecutionPrice() {
            super(170);
        }

        @Override
        void draw(Graphics2D g) {
            box(g, 20, 30, 230, 70, new Color(0x123B2C), Theme.BUY, "Resting BUY #1", "10 @ 105  (already on the book)");
            box(g, 300, 30, 230, 70, Theme.SELL_DIM, Theme.SELL, "Incoming SELL #2", "10 @ 100  (would accept 100)");
            arrow(g, 532, 65, 590, 65, Theme.WARN);
            box(g, 595, 30, 220, 70, Theme.PANEL_2, Theme.WARN, "TRADE 10 @ 105", "buy #1 / sell #2");
            text(g, "The trade price is the price of the RESTING order: the order that was waiting sets the price.", 20,
                    130, Theme.ui(Font.PLAIN, 13.5f), Theme.TEXT);
            text(g, "The incoming order can only do as well as its limit or better (here the seller gets 105, not 100).",
                    20, 152, Theme.ui(Font.PLAIN, 13), Theme.MUTED);
        }
    }

    private static final class LockBased extends Diagram {
        LockBased() {
            super(250);
        }

        @Override
        void draw(Graphics2D g) {
            for (int i = 0; i < 4; i++) {
                int y = 20 + i * 52;
                boolean owner = i == 0;
                box(g, 20, y, 130, 40, Theme.PANEL_2, owner ? Theme.BASELINE : Theme.BORDER, "Thread T" + (i + 1));
                if (owner) {
                    arrow(g, 152, y + 20, 288, 105, Theme.BASELINE);
                } else {
                    g.setColor(Theme.FAINT);
                    Ui.dashed(g, 1.5f);
                    g.drawLine(152, y + 20, 280, 110);
                    g.setStroke(new BasicStroke(1));
                }
            }
            text(g, "waiting for the lock", 160, 200, Theme.ui(Font.ITALIC, 12), Theme.FAINT);
            // padlock
            g.setColor(Theme.BASELINE);
            g.setStroke(new BasicStroke(4f));
            g.drawArc(316, 62, 36, 40, 0, 180);
            g.setStroke(new BasicStroke(1));
            g.fillRoundRect(304, 82, 60, 50, 10, 10);
            centered(g, "lock", 334, 113, Theme.ui(Font.BOLD, 13), Color.WHITE);
            text(g, "ReentrantLock", 290, 152, Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
            arrow(g, 370, 105, 430, 105, Theme.BASELINE);
            box(g, 435, 65, 230, 80, Theme.PANEL_2, Theme.BASELINE, "book.process(...)", "runs on the thread that",
                    "holds the lock");
            arrow(g, 667, 105, 717, 105, Theme.BASELINE);
            box(g, 720, 65, 260, 80, Theme.PANEL_2, Theme.BORDER, "LockBasedOrderBook", "TreeMap price → ArrayDeque",
                    "(FIFO queue per price)");
            text(g, "One thread at a time. submit() returns only after its order is fully matched.", 300, 195,
                    Theme.ui(Font.PLAIN, 13.5f), Theme.TEXT);
            text(g, "Simple and correct; with many threads they spend time queueing for the lock.", 300, 218,
                    Theme.ui(Font.PLAIN, 13), Theme.MUTED);
        }
    }

    private static final class LockFree extends Diagram {
        LockFree() {
            super(330);
        }

        @Override
        void draw(Graphics2D g) {
            for (int i = 0; i < 4; i++) {
                int y = 30 + i * 52;
                box(g, 20, y, 130, 40, Theme.PANEL_2, Theme.LOCK_FREE, "Thread T" + (i + 1));
                arrow(g, 152, y + 20, 300, 140, Theme.LOCK_FREE);
            }
            text(g, "claim a slot: CAS on", 150, 262, Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
            text(g, "the sequence, write,", 150, 280, Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
            text(g, "then set its publish flag", 150, 298, Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);

            // ring buffer
            int cx = 420;
            int cy = 145;
            int r = 100;
            int slots = 16;
            for (int i = 0; i < slots; i++) {
                double a0 = 360.0 * i / slots;
                Color fill;
                if (i < 5) {
                    fill = Theme.PANEL_3;          // consumed (free again)
                } else if (i < 10) {
                    fill = Theme.ACCENT;           // published, waiting for the matcher
                } else if (i < 12) {
                    fill = Theme.WARN;             // being written by producers
                } else {
                    fill = Theme.PANEL_2;          // free
                }
                g.setColor(fill);
                g.fill(new java.awt.geom.Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 90 - a0, -360.0 / slots + 2,
                        java.awt.geom.Arc2D.PIE));
            }
            g.setColor(Theme.PANEL);
            g.fillOval(cx - 58, cy - 58, 116, 116);
            centered(g, "ring buffer", cx, cy - 4, Theme.ui(Font.BOLD, 14), Theme.TEXT);
            centered(g, "65,536 slots", cx, cy + 16, Theme.ui(Font.PLAIN, 12), Theme.MUTED);

            int lx = 545;
            legend(g, lx, 40, Theme.ACCENT, "published, waiting to be matched");
            legend(g, lx, 62, Theme.WARN, "being written by a producer");
            legend(g, lx, 84, Theme.PANEL_3, "already matched (reusable)");

            arrow(g, 522, 175, 600, 175, Theme.LOCK_FREE);
            box(g, 605, 130, 175, 90, Theme.PANEL_2, Theme.LOCK_FREE, "1 matching thread", "takes slots in sequence",
                    "order, never a half-", "written one");
            arrow(g, 782, 175, 815, 175, Theme.LOCK_FREE);
            box(g, 818, 130, 170, 90, Theme.PANEL_2, Theme.BORDER, "ArrayOrderBook", "array slot per price",
                    "pooled FIFO lists", "no allocation");
            text(g, "Producers never take a lock. Only the matching thread touches the book (single writer).", 300,
                    268, Theme.ui(Font.PLAIN, 13.5f), Theme.TEXT);
            text(g, "submit() returns once the order is published; it is matched a moment later.", 300, 290,
                    Theme.ui(Font.PLAIN, 13), Theme.MUTED);
            text(g, "The ticket is the ring sequence. If the ring is full, producers wait for the matching thread.", 300,
                    310, Theme.ui(Font.PLAIN, 13), Theme.MUTED);
        }

        private static void legend(Graphics2D g, int x, int y, Color color, String label) {
            g.setColor(color);
            g.fillRoundRect(x, y - 11, 14, 14, 4, 4);
            text(g, label, x + 22, y, Theme.ui(Font.PLAIN, 12.5f), Theme.TEXT);
        }
    }

    private static final class Measurement extends Diagram {
        Measurement() {
            super(330);
        }

        @Override
        void draw(Graphics2D g) {
            // timeline
            text(g, "Latency of one order (same definition for both engines)", 20, 24, Theme.ui(Font.BOLD, 13.5f),
                    Theme.TEXT);
            g.setColor(Theme.MUTED);
            g.setStroke(new BasicStroke(2));
            g.drawLine(40, 80, 560, 80);
            g.setStroke(new BasicStroke(1));
            numberBadge(g, 60, 80, "t0", Theme.ACCENT);
            numberBadge(g, 540, 80, "t1", Theme.ACCENT);
            text(g, "submit(order) → ticket", 30, 115, Theme.mono(Font.PLAIN, 12.5f), Theme.TEXT);
            text(g, "processedCount() > ticket", 400, 115, Theme.mono(Font.PLAIN, 12.5f), Theme.TEXT);
            g.setColor(new Color(0x24406B));
            g.fillRoundRect(80, 70, 440, 20, 8, 8);
            centered(g, "latency = t1 − t0", 300, 85, Theme.ui(Font.BOLD, 12.5f), Color.WHITE);
            text(g, "Baseline: matched inside submit(). Lock-free: matched later by the matching thread,", 20, 145,
                    Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
            text(g, "so timing only submit() would be unfair. Both are timed until the order is fully processed.", 20,
                    163, Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);

            // distribution
            int bx = 620;
            int by = 150;
            text(g, "Many samples → a distribution", bx, 24, Theme.ui(Font.BOLD, 13.5f), Theme.TEXT);
            double[] heights = {20, 70, 110, 95, 60, 34, 20, 12, 8, 6, 4, 3, 3, 2, 2, 2};
            for (int i = 0; i < heights.length; i++) {
                g.setColor(Theme.blend(Theme.ACCENT, Theme.PANEL, 0.35));
                g.fillRect(bx + i * 22, by - (int) heights[i], 18, (int) heights[i]);
            }
            marker(g, bx + 2 * 22 + 9, by, "p50", Theme.OK);
            marker(g, bx + 9 * 22 + 9, by, "p99", Theme.WARN);
            marker(g, bx + 14 * 22 + 9, by, "p99.9", Theme.SELL);
            text(g, "fast  →  slow", bx, by + 40, Theme.ui(Font.ITALIC, 12), Theme.FAINT);

            // settings
            Font f = Theme.ui(Font.PLAIN, 13);
            text(g, "p50 = the typical order. p99 = 1 order in 100 is slower than this. p99.9 = 1 in 1,000.", 20, 215,
                    f, Theme.TEXT);
            text(g, "Throughput = orders accepted per second with all producers at full speed.", 20, 238, f,
                    Theme.TEXT);
            text(g, "Tool: JMH (Java Microbenchmark Harness). Warm-up 3 × 2 s, measurement 5 × 3 s, 2 forks (fresh "
                    + "JVMs), whole suite run 3 times;", 20, 272, Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
            text(g, "1, 2, 4 and 8 producer threads; same seeded orders for both engines. Reported value = median of "
                    + "the runs, with min to max.", 20, 292, Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
        }

        private static void marker(Graphics2D g, int x, int baseY, String label, Color color) {
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(x, baseY - 125, x, baseY + 6);
            g.setStroke(new BasicStroke(1));
            centered(g, label, x, baseY + 22, Theme.ui(Font.BOLD, 12), color);
        }
    }
}
