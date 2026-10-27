package com.nanomatch.gui;

import com.nanomatch.demo.BenchmarkResults;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;
import javax.swing.JComponent;

/**
 * Grouped bar chart: one group per producer count, one bar per engine. Bars show the median of the runs, the
 * whisker shows the minimum to maximum. A missing value is drawn as "n/a", never as zero.
 */
final class BarChart extends JComponent {

    /** One bar: its value (null if not measured), colour and name. */
    record Bar(String series, Color color, BenchmarkResults.Stat stat) {
    }

    /** One group of bars under a category label. */
    record Group(String label, List<Bar> bars) {
    }

    private List<Group> groups = List.of();
    private boolean log;
    private String axisLabel = "";
    private DoubleFunction<String> valueFormat = v -> String.format(Locale.ROOT, "%.2f", v);
    private double scale = 1; // divide raw values by this for display
    private final List<Rectangle> hitBoxes = new ArrayList<>();
    private final List<String> hitTexts = new ArrayList<>();

    BarChart() {
        setPreferredSize(new Dimension(640, 360));
        setToolTipText("");
        setOpaque(false);
    }

    void setData(List<Group> groups, boolean log, String axisLabel, double scale, DoubleFunction<String> format) {
        this.groups = groups;
        this.log = log;
        this.axisLabel = axisLabel;
        this.scale = scale;
        this.valueFormat = format;
        repaint();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        for (int i = 0; i < hitBoxes.size(); i++) {
            if (hitBoxes.get(i).contains(event.getPoint())) {
                return hitTexts.get(i);
            }
        }
        return null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Ui.antialias(g2);
        hitBoxes.clear();
        hitTexts.clear();
        int left = 74;
        int right = 18;
        int top = 34;
        int bottom = 52;
        int w = getWidth() - left - right;
        int h = getHeight() - top - bottom;
        if (w < 50 || h < 50 || groups.isEmpty()) {
            g2.dispose();
            return;
        }

        double min = Double.MAX_VALUE;
        double max = 0;
        for (Group group : groups) {
            for (Bar bar : group.bars()) {
                if (bar.stat() != null) {
                    max = Math.max(max, bar.stat().max() / scale);
                    min = Math.min(min, bar.stat().min() / scale);
                }
            }
        }
        if (max <= 0) {
            g2.setColor(Theme.MUTED);
            g2.setFont(Theme.ui(Font.ITALIC, 14));
            g2.drawString("No measured values for this selection.", left + 10, top + h / 2);
            g2.dispose();
            return;
        }

        // ---- axis and grid
        double lo;
        double hi;
        List<Double> ticks = new ArrayList<>();
        if (log) {
            lo = Math.pow(10, Math.floor(Math.log10(Math.max(min, 1e-9))));
            hi = Math.pow(10, Math.ceil(Math.log10(max * 1.15)));
            if (hi <= lo) {
                hi = lo * 10;
            }
            for (double t = lo; t <= hi * 1.0001; t *= 10) {
                ticks.add(t);
            }
        } else {
            lo = 0;
            double step = niceStep(max * 1.15 / 5);
            hi = Math.ceil(max * 1.15 / step) * step;
            for (double t = 0; t <= hi + step / 2; t += step) {
                ticks.add(t);
            }
        }
        final double fLo = lo;
        final double fHi = hi;
        java.util.function.DoubleUnaryOperator yOf = v -> {
            double frac = log ? (Math.log10(Math.max(v, fLo)) - Math.log10(fLo)) / (Math.log10(fHi) - Math.log10(fLo))
                    : (v - fLo) / (fHi - fLo);
            return top + h - frac * h;
        };

        g2.setFont(Theme.mono(Font.PLAIN, 11.5f));
        FontMetrics fm = g2.getFontMetrics();
        for (double t : ticks) {
            int y = (int) yOf.applyAsDouble(t);
            g2.setColor(Theme.BORDER);
            g2.drawLine(left, y, left + w, y);
            g2.setColor(Theme.MUTED);
            String label = tickLabel(t);
            g2.drawString(label, left - 8 - fm.stringWidth(label), y + 4);
            if (log) {
                g2.setColor(new Color(Theme.BORDER.getRed(), Theme.BORDER.getGreen(), Theme.BORDER.getBlue(), 90));
                for (int k = 2; k <= 9 && t * k <= fHi; k++) {
                    int ym = (int) yOf.applyAsDouble(t * k);
                    g2.drawLine(left, ym, left + w, ym);
                }
            }
        }
        g2.setColor(Theme.MUTED);
        g2.setFont(Theme.ui(Font.PLAIN, 12));
        g2.drawString(axisLabel + (log ? "  (log scale)" : ""), left, top - 14);

        // ---- legend
        int lx = left + w;
        if (!groups.isEmpty()) {
            List<Bar> legend = groups.get(0).bars();
            g2.setFont(Theme.ui(Font.BOLD, 12));
            for (int i = legend.size() - 1; i >= 0; i--) {
                Bar bar = legend.get(i);
                int tw = g2.getFontMetrics().stringWidth(bar.series());
                lx -= tw;
                g2.setColor(Theme.TEXT);
                g2.drawString(bar.series(), lx, top - 14);
                lx -= 18;
                g2.setColor(bar.color());
                g2.fillRoundRect(lx, top - 25, 12, 12, 3, 3);
                lx -= 18;
            }
        }

        // ---- bars
        int groupW = w / groups.size();
        for (int gi = 0; gi < groups.size(); gi++) {
            Group group = groups.get(gi);
            int n = group.bars().size();
            int barW = Math.min(64, (int) (groupW * 0.7 / n));
            int startX = left + gi * groupW + (groupW - barW * n - 8 * (n - 1)) / 2;
            for (int bi = 0; bi < n; bi++) {
                Bar bar = group.bars().get(bi);
                int x = startX + bi * (barW + 8);
                if (bar.stat() == null) {
                    g2.setColor(Theme.PANEL_2);
                    Ui.dashed(g2, 1);
                    g2.drawRect(x, top + h - 40, barW, 40);
                    g2.setStroke(new BasicStroke(1));
                    g2.setColor(Theme.FAINT);
                    g2.setFont(Theme.ui(Font.ITALIC, 11));
                    g2.drawString("n/a", x + barW / 2 - 9, top + h - 16);
                    continue;
                }
                double median = bar.stat().median() / scale;
                int y = (int) yOf.applyAsDouble(median);
                int base = top + h;
                g2.setColor(bar.color());
                g2.fillRoundRect(x, y, barW, Math.max(2, base - y), 6, 6);
                g2.fillRect(x, Math.max(y, base - 6), barW, Math.min(6, base - y));
                // whisker min..max
                int yMin = (int) yOf.applyAsDouble(bar.stat().min() / scale);
                int yMax = (int) yOf.applyAsDouble(bar.stat().max() / scale);
                int cx = x + barW / 2;
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawLine(cx, yMin, cx, yMax);
                g2.drawLine(cx - 5, yMin, cx + 5, yMin);
                g2.drawLine(cx - 5, yMax, cx + 5, yMax);
                g2.setStroke(new BasicStroke(1));
                // value label
                String text = valueFormat.apply(median);
                g2.setFont(Theme.mono(Font.BOLD, 11.5f));
                int tw = g2.getFontMetrics().stringWidth(text);
                g2.setColor(Theme.TEXT);
                g2.drawString(text, cx - tw / 2, Math.min(y, yMax) - 6);

                hitBoxes.add(new Rectangle(x, Math.min(y, yMax) - 18, barW, base - Math.min(y, yMax) + 18));
                StringBuilder tip = new StringBuilder("<html><b>").append(bar.series()).append(", ")
                        .append(group.label()).append("</b><br>median ").append(valueFormat.apply(median))
                        .append(" &nbsp;(min ").append(valueFormat.apply(bar.stat().min() / scale)).append(", max ")
                        .append(valueFormat.apply(bar.stat().max() / scale)).append(")<br>runs: ");
                for (int k = 0; k < bar.stat().values().length; k++) {
                    tip.append(k == 0 ? "" : "; ").append(valueFormat.apply(bar.stat().values()[k] / scale));
                }
                tip.append("<br>source: ").append(bar.stat().source().label());
                if (bar.stat().stable() != null) {
                    tip.append("<br>run-to-run spread ").append(String.format(Locale.ROOT, "%.1f%%",
                            bar.stat().spreadPct())).append(bar.stat().stable() ? " (stable)" : " (above tolerance)");
                }
                hitTexts.add(tip.append("</html>").toString());
            }
            g2.setColor(Theme.TEXT);
            g2.setFont(Theme.ui(Font.BOLD, 13));
            int lw = g2.getFontMetrics().stringWidth(group.label());
            g2.drawString(group.label(), left + gi * groupW + (groupW - lw) / 2, top + h + 22);
        }
        g2.setColor(Theme.FAINT);
        g2.setFont(Theme.ui(Font.PLAIN, 11.5f));
        String note = "Bar = median of the runs · whisker = min to max of the runs · hover a bar for details";
        g2.drawString(note, left, top + h + 44);
        g2.dispose();
    }

    private static double niceStep(double raw) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double residual = raw / magnitude;
        double nice = residual <= 1 ? 1 : residual <= 2 ? 2 : residual <= 2.5 ? 2.5 : residual <= 5 ? 5 : 10;
        return nice * magnitude;
    }

    private static String tickLabel(double v) {
        if (v == 0) {
            return "0";
        }
        if (v >= 100) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        if (v >= 1) {
            return v == Math.rint(v) ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
