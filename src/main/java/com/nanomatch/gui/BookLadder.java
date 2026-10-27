package com.nanomatch.gui;

import com.nanomatch.demo.SessionSnapshot;
import com.nanomatch.model.Side;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * Price ladder of the order book: sell levels above (best ask nearest the middle), the spread, buy levels below
 * (best bid nearest the middle). Each level shows its total quantity, a depth bar and, optionally, its queue of
 * resting orders in time priority (left = first in line).
 */
final class BookLadder extends JComponent implements javax.swing.Scrollable {

    private static final int ROW = 28;
    private static final int HEADER = 26;
    private static final int SPREAD = 34;

    private final int maxLevels;
    private final boolean showQueue;
    private SessionSnapshot snapshot;

    private record Row(SessionSnapshot.Level level, int y) {
    }

    private final List<Row> rows = new ArrayList<>();

    BookLadder(int maxLevels, boolean showQueue) {
        this.maxLevels = maxLevels;
        this.showQueue = showQueue;
        setToolTipText("");
        setOpaque(false);
    }

    void setSnapshot(SessionSnapshot snapshot) {
        this.snapshot = snapshot;
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        int asks = snapshot == null ? 1 : Math.max(1, Math.min(maxLevels, snapshot.asks().size()));
        int bids = snapshot == null ? 1 : Math.max(1, Math.min(maxLevels, snapshot.bids().size()));
        boolean moreAsks = snapshot != null && snapshot.asks().size() > maxLevels;
        boolean moreBids = snapshot != null && snapshot.bids().size() > maxLevels;
        int height = HEADER + (asks + bids) * ROW + SPREAD + (moreAsks ? 20 : 0) + (moreBids ? 20 : 0) + 6;
        return new Dimension(showQueue ? 560 : 320, height);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
        return ROW;
    }

    @Override
    public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
        return Math.max(ROW, visible.height - ROW);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true; // always as wide as the panel, never a sideways scroll bar
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        for (Row row : rows) {
            if (event.getY() >= row.y() && event.getY() < row.y() + ROW) {
                SessionSnapshot.Level level = row.level();
                StringBuilder sb = new StringBuilder("<html><b>").append(level.side() == Side.BUY ? "BID" : "ASK")
                        .append(" ").append(level.price()).append("</b> &nbsp;total ").append(level.totalQuantity())
                        .append("<br>Queue (first in line first):");
                int n = 1;
                for (SessionSnapshot.RestingOrder o : level.queue()) {
                    sb.append("<br>").append(n++).append(". order #").append(o.orderId()).append(": ")
                            .append(o.remainingQuantity()).append(" left of ").append(o.originalQuantity())
                            .append(" (ticket ").append(o.ticket()).append(")");
                }
                return sb.append("</html>").toString();
            }
        }
        return null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        rows.clear();
        int w = getWidth();
        int priceX = 14;
        int qtyRight = 150;
        int queueX = 166;

        g2.setFont(Theme.ui(Font.BOLD, 11.5f));
        g2.setColor(Theme.MUTED);
        g2.drawString("PRICE", priceX, 17);
        drawRight(g2, "QUANTITY", qtyRight, 17);
        g2.drawString(showQueue ? "RESTING ORDERS  (time priority: first in line on the left)" : "DEPTH", queueX, 17);
        g2.setColor(Theme.BORDER);
        g2.drawLine(0, HEADER - 2, w, HEADER - 2);

        if (snapshot == null) {
            g2.dispose();
            return;
        }
        long maxQty = 1;
        for (SessionSnapshot.Level l : snapshot.asks()) {
            maxQty = Math.max(maxQty, l.totalQuantity());
        }
        for (SessionSnapshot.Level l : snapshot.bids()) {
            maxQty = Math.max(maxQty, l.totalQuantity());
        }

        int y = HEADER;
        // ---- asks: worst at top, best just above the spread
        List<SessionSnapshot.Level> asks = new ArrayList<>(snapshot.asks().subList(0,
                Math.min(maxLevels, snapshot.asks().size())));
        Collections.reverse(asks);
        if (snapshot.asks().size() > maxLevels) {
            g2.setFont(Theme.ui(Font.ITALIC, 11.5f));
            g2.setColor(Theme.FAINT);
            g2.drawString("+ " + (snapshot.asks().size() - maxLevels) + " more ask level(s) above", priceX, y + 14);
            y += 20;
        }
        if (asks.isEmpty()) {
            empty(g2, "No resting SELL orders", y, w, Theme.SELL);
            y += ROW;
        }
        for (int i = 0; i < asks.size(); i++) {
            paintLevel(g2, asks.get(i), y, w, maxQty, priceX, qtyRight, queueX, i == asks.size() - 1);
            y += ROW;
        }

        // ---- spread
        g2.setColor(Theme.PANEL_2);
        g2.fillRect(0, y + 3, w, SPREAD - 6);
        g2.setFont(Theme.ui(Font.BOLD, 12.5f));
        String spread;
        if (snapshot.spread() >= 0) {
            double mid = (snapshot.bestBid() + snapshot.bestAsk()) / 2.0;
            spread = String.format(Locale.ROOT, "SPREAD  %d tick%s     MID  %.1f", snapshot.spread(),
                    snapshot.spread() == 1 ? "" : "s", mid);
            g2.setColor(Theme.WARN);
        } else {
            spread = "SPREAD  —   (needs orders on both sides)";
            g2.setColor(Theme.MUTED);
        }
        g2.drawString(spread, priceX, y + SPREAD / 2 + 5);
        if (snapshot.lastTradePrice() > 0) {
            g2.setColor(Theme.MUTED);
            g2.setFont(Theme.ui(Font.PLAIN, 12));
            drawRight(g2, "last trade " + snapshot.lastTradePrice(), w - 12, y + SPREAD / 2 + 5);
        }
        y += SPREAD;

        // ---- bids: best at top
        List<SessionSnapshot.Level> bids = snapshot.bids().subList(0, Math.min(maxLevels, snapshot.bids().size()));
        if (bids.isEmpty()) {
            empty(g2, "No resting BUY orders", y, w, Theme.BUY);
            y += ROW;
        }
        for (int i = 0; i < bids.size(); i++) {
            paintLevel(g2, bids.get(i), y, w, maxQty, priceX, qtyRight, queueX, i == 0);
            y += ROW;
        }
        if (snapshot.bids().size() > maxLevels) {
            g2.setFont(Theme.ui(Font.ITALIC, 11.5f));
            g2.setColor(Theme.FAINT);
            g2.drawString("+ " + (snapshot.bids().size() - maxLevels) + " more bid level(s) below", priceX, y + 14);
        }
        g2.dispose();
    }

    private void empty(Graphics2D g2, String text, int y, int w, Color color) {
        g2.setFont(Theme.ui(Font.ITALIC, 12.5f));
        g2.setColor(Theme.blend(color, Theme.PANEL, 0.35));
        g2.drawString(text, 14, y + ROW / 2 + 5);
    }

    private void paintLevel(Graphics2D g2, SessionSnapshot.Level level, int y, int w, long maxQty, int priceX,
                            int qtyRight, int queueX, boolean best) {
        rows.add(new Row(level, y));
        Color side = Theme.sideColor(level.side());
        Color dim = level.side() == Side.BUY ? Theme.BUY_DIM : Theme.SELL_DIM;

        // depth bar, drawn from the right edge
        int barMax = w - queueX - 10;
        int bar = (int) Math.max(4, Math.round(barMax * (double) level.totalQuantity() / maxQty));
        g2.setColor(dim);
        g2.fillRect(w - 6 - bar, y + 3, bar, ROW - 6);
        if (best) {
            g2.setColor(new Color(side.getRed(), side.getGreen(), side.getBlue(), 28));
            g2.fillRect(0, y + 1, w, ROW - 2);
            g2.setColor(side);
            g2.fillRect(0, y + 3, 3, ROW - 6);
        }

        g2.setFont(Theme.mono(Font.BOLD, 14));
        g2.setColor(side);
        g2.drawString(Long.toString(level.price()), priceX, y + ROW / 2 + 5);
        g2.setFont(Theme.mono(Font.PLAIN, 14));
        g2.setColor(Theme.TEXT);
        drawRight(g2, Ui.integer(level.totalQuantity()), qtyRight, y + ROW / 2 + 5);

        if (showQueue) {
            int x = queueX;
            g2.setFont(Theme.mono(Font.PLAIN, 12));
            FontMetrics fm = g2.getFontMetrics();
            int limit = w - 14;
            for (int i = 0; i < level.queue().size(); i++) {
                SessionSnapshot.RestingOrder o = level.queue().get(i);
                String text = "#" + o.orderId() + " · " + o.remainingQuantity();
                int cw = fm.stringWidth(text) + 14;
                if (x + cw > limit) {
                    String more = "+" + (level.queue().size() - i) + " more";
                    g2.setColor(Theme.MUTED);
                    g2.drawString(more, x, y + ROW / 2 + 5);
                    break;
                }
                g2.setColor(i == 0 ? Theme.blend(side, Theme.PANEL, 0.55) : Theme.PANEL_3);
                g2.fillRoundRect(x, y + 5, cw, ROW - 10, 8, 8);
                if (o.partiallyFilled()) {
                    g2.setColor(Theme.WARN);
                    Ui.dashed(g2, 1.2f);
                    g2.drawRoundRect(x, y + 5, cw, ROW - 10, 8, 8);
                    g2.setStroke(new java.awt.BasicStroke(1));
                }
                g2.setColor(Theme.TEXT);
                g2.drawString(text, x + 7, y + ROW / 2 + 4);
                x += cw + 6;
            }
        }
    }

    private static void drawRight(Graphics2D g2, String text, int right, int baseline) {
        g2.drawString(text, right - g2.getFontMetrics().stringWidth(text), baseline);
    }
}
