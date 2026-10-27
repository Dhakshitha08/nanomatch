package com.nanomatch.gui;

import com.nanomatch.demo.ComparisonRun;
import com.nanomatch.demo.EngineKind;
import com.nanomatch.demo.SessionSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

/** The two designs side by side, switching the live session between them, and a live parity + throughput run. */
final class ComparisonPanel extends JPanel {

    private final SessionController controller;
    private final Ui.FlatButton useBaseline = new Ui.FlatButton("Use lock-based engine", Theme.BASELINE);
    private final Ui.FlatButton useLockFree = new Ui.FlatButton("Use lock-free engine", Theme.LOCK_FREE);
    private final JLabel active = Ui.label(" ", Theme.ui(Font.BOLD, 13), Theme.TEXT);

    private final JComboBox<Integer> producers = new JComboBox<>();
    private final JComboBox<String> orders = new JComboBox<>(new String[] {"20,000", "50,000", "100,000"});
    private final Ui.FlatButton run = Ui.primary("Run live comparison");
    private final JLabel progress = Ui.label("Not run yet.", Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
    private final RateBars bars = new RateBars();
    private final Ui.Badge concurrentBadge = new Ui.Badge("Parity, concurrent: not run", Theme.PANEL_3, Theme.MUTED);
    private final Ui.Badge singleBadge = new Ui.Badge("Parity, one producer: not run", Theme.PANEL_3, Theme.MUTED);
    private final JLabel parityDetail = Ui.label(" ", Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);

    ComparisonPanel(SessionController controller) {
        super(new BorderLayout(0, 12));
        this.controller = controller;
        setOpaque(false);
        add(Ui.pageHeader("Engine Comparison", "Same MatchingEngine interface, same matching rules, two different ways "
                + "of handling many threads at once."), BorderLayout.NORTH);

        JPanel designs = Ui.transparent(new GridLayout(1, 2, 12, 0));
        designs.add(designCard(EngineKind.LOCK_BASED, new String[][] {
            {"Entry", "submit() takes one ReentrantLock"},
            {"Matching runs", "on the calling thread, inside submit()"},
            {"submit() returns", "after the order is fully matched"},
            {"Order book", "LockBasedOrderBook: TreeMap price → ArrayDeque (FIFO queue)"},
            {"Many threads", "all producers queue for the same lock"},
            {"Ticket", "the order in which the lock was acquired"},
            {"Limits", "unbounded book; allocates per order"},
        }));
        designs.add(designCard(EngineKind.LOCK_FREE, new String[][] {
            {"Entry", "submit() claims a ring-buffer slot with a CAS, writes it, publishes it"},
            {"Matching runs", "on one dedicated thread (nanomatch-matcher)"},
            {"submit() returns", "as soon as the order is published"},
            {"Order book", "ArrayOrderBook: one array slot per price tick, pooled FIFO lists"},
            {"Many threads", "no locks; the book has a single writer"},
            {"Ticket", "the ring-buffer sequence number"},
            {"Limits", "prices 1 to 65,535; 2^20 resting orders; ring of 65,536 slots"},
        }));

        useBaseline.addActionListener(e -> switchTo(EngineKind.LOCK_BASED));
        useLockFree.addActionListener(e -> switchTo(EngineKind.LOCK_FREE));
        JPanel switchRow = Ui.transparent(new BorderLayout(12, 0));
        switchRow.add(active, BorderLayout.CENTER);
        switchRow.add(Ui.row(8, useBaseline, useLockFree), BorderLayout.EAST);
        Ui.Card switchCard = new Ui.Card("Engine used by Order Entry, Order Book and Demo Scenarios",
                "Switching closes the current engine (stopping its threads) and starts the other one with an empty "
                        + "book").body(switchRow);

        int cores = Runtime.getRuntime().availableProcessors();
        for (int p : new int[] {1, 2, 4, 8}) {
            producers.addItem(p);
        }
        producers.setSelectedItem(Math.min(4, Math.max(1, cores / 2)));
        orders.setSelectedIndex(1);
        run.addActionListener(e -> runComparison());

        JPanel controls = Ui.row(10, Ui.label("Producer threads", Theme.ui(Font.BOLD, 12.5f), Theme.MUTED), producers,
                Ui.label("Orders per producer", Theme.ui(Font.BOLD, 12.5f), Theme.MUTED), orders, run);
        JPanel liveBody = Ui.transparent(new BorderLayout(0, 10));
        JPanel top = Ui.transparent(new BorderLayout(0, 6));
        top.add(controls, BorderLayout.NORTH);
        top.add(progress, BorderLayout.CENTER);
        liveBody.add(top, BorderLayout.NORTH);
        liveBody.add(bars, BorderLayout.CENTER);
        JPanel parity = Ui.transparent(new BorderLayout(0, 6));
        parity.add(Ui.row(8, concurrentBadge, singleBadge), BorderLayout.NORTH);
        parity.add(parityDetail, BorderLayout.CENTER);
        parity.add(Ui.wrapped("This quick run uses the project's LoadSimulator (seeded orders, max speed, one untimed "
                + "warm-up run, then 3 timed runs per engine) on this computer. It is a live illustration, NOT the "
                + "benchmark: the measured results are the JMH runs on the Performance page. The parity check uses "
                + "the same method as ParityTest: the lock-free engine records which order got which ticket, and "
                + "that exact sequence is replayed through the baseline; every trade must be identical.", 900,
                Theme.FAINT), BorderLayout.SOUTH);
        liveBody.add(parity, BorderLayout.SOUTH);
        Ui.Card liveCard = new Ui.Card("Live check on this computer", "Both engines, same seeded orders, "
                + "same number of producer threads").body(liveBody);

        JPanel center = Ui.transparent(new BorderLayout(0, 12));
        center.add(designs, BorderLayout.NORTH);
        JPanel lower = Ui.transparent(new BorderLayout(0, 12));
        lower.add(switchCard, BorderLayout.NORTH);
        lower.add(liveCard, BorderLayout.CENTER);
        center.add(lower, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        controller.onSnapshot(this::update);
        controller.onBusy(busy -> {
            useBaseline.setEnabled(!busy);
            useLockFree.setEnabled(!busy);
        });
    }

    private JComponent designCard(EngineKind kind, String[][] rows) {
        StringBuilder html = new StringBuilder("<table cellspacing='0' cellpadding='3'>");
        for (String[] row : rows) {
            html.append("<tr><td valign='top' style='color:").append(Ui.hex(Theme.MUTED))
                    .append(";width:110px'><b>").append(row[0]).append("</b></td><td style='color:")
                    .append(Ui.hex(Theme.TEXT)).append("'>").append(Ui.escape(row[1])).append("</td></tr>");
        }
        html.append("</table>");
        JLabel table = Ui.html(html.toString(), 470);
        Ui.Card card = new Ui.Card(kind.displayName(), kind.className() + (kind == EngineKind.LOCK_FREE
                ? " + OrderRingBuffer + ArrayOrderBook" : " + LockBasedOrderBook")) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                Ui.antialias(g2);
                g2.setColor(Theme.engineColor(kind));
                g2.fillRoundRect(0, 0, getWidth() - 1, 5, 4, 4);
                g2.dispose();
            }
        };
        return card.body(table);
    }

    private void switchTo(EngineKind kind) {
        SessionSnapshot s = controller.snapshot();
        if (s.engine() == kind) {
            return;
        }
        if (s.submitted() > 0) {
            int answer = JOptionPane.showConfirmDialog(this, "Switching starts a fresh " + kind.displayName()
                    + " with an empty book.\nThe " + s.submitted() + " order(s) and " + s.trades().size()
                    + " trade(s) of the current session will be cleared.", "Switch engine",
                    JOptionPane.OK_CANCEL_OPTION);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        controller.reset(kind, snap -> { });
    }

    private void update(SessionSnapshot s) {
        active.setText("<html>Active now: <span style='color:" + Ui.hex(Theme.engineColor(s.engine())) + "'>"
                + s.engine().displayName() + "</span> (" + s.engine().className() + ")</html>");
        useBaseline.setVisible(s.engine() != EngineKind.LOCK_BASED);
        useLockFree.setVisible(s.engine() != EngineKind.LOCK_FREE);
    }

    private void runComparison() {
        int p = (Integer) producers.getSelectedItem();
        int perProducer = Integer.parseInt(((String) orders.getSelectedItem()).replace(",", ""));
        run.setEnabled(false);
        progress.setForeground(Theme.ACCENT);
        new SwingWorker<ComparisonRun.Result, String>() {
            @Override
            protected ComparisonRun.Result doInBackground() throws Exception {
                return ComparisonRun.run(p, perProducer, 3, 42, this::publish);
            }

            @Override
            protected void process(List<String> chunks) {
                progress.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                run.setEnabled(true);
                try {
                    show(get());
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    progress.setText("The run failed: " + cause);
                    progress.setForeground(Theme.ERROR);
                }
            }
        }.execute();
    }

    private void show(ComparisonRun.Result r) {
        progress.setForeground(Theme.MUTED);
        progress.setText(String.format(Locale.ROOT, "Finished in %.1f s: %d producer(s) × %s orders per engine run, "
                + "median of 3 timed runs (bars show min to max). This computer: %d logical cores; the lock-free "
                + "engine also needs one core for its matching thread.", r.elapsedMillis() / 1000.0, r.producers(),
                Ui.integer(r.ordersPerThread()), Runtime.getRuntime().availableProcessors()));
        bars.set(r.baseline(), r.lockFree());
        badge(concurrentBadge, "Parity, " + r.producers() + " concurrent producer(s)", r.concurrentParity());
        badge(singleBadge, "Parity, one producer", r.singleProducerParity());
        parityDetail.setText("<html><div style='width:900px'>" + Ui.escape(r.concurrentParity().detail())
                + "<br>" + Ui.escape(r.singleProducerParity().detail()) + "</div></html>");
        parityDetail.setForeground(r.concurrentParity().passed() && r.singleProducerParity().passed() ? Theme.TEXT
                : Theme.ERROR);
    }

    private static void badge(Ui.Badge badge, String what, ComparisonRun.Parity parity) {
        if (parity.passed()) {
            badge.set("✓ " + what + ": " + Ui.integer(parity.tradesCompared()) + " trades identical",
                    new Color(0x123B2C), Theme.OK);
        } else {
            badge.set("✗ " + what + ": MISMATCH", Theme.SELL_DIM, Theme.ERROR);
        }
    }

    /** Two horizontal bars: median orders per second, with a min-max whisker. */
    private static final class RateBars extends JComponent {
        private ComparisonRun.EngineMeasurement baseline;
        private ComparisonRun.EngineMeasurement lockFree;

        RateBars() {
            setPreferredSize(new Dimension(600, 110));
        }

        void set(ComparisonRun.EngineMeasurement b, ComparisonRun.EngineMeasurement l) {
            baseline = b;
            lockFree = l;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Ui.antialias(g2);
            int labelW = 190;
            int w = getWidth() - labelW - 170;
            if (baseline == null) {
                g2.setColor(Theme.FAINT);
                g2.setFont(Theme.ui(Font.ITALIC, 13));
                g2.drawString("Press \"Run live comparison\" to measure both engines here.", 4, 30);
                g2.dispose();
                return;
            }
            double max = Math.max(baseline.max(), lockFree.max()) * 1.05;
            int y = 8;
            for (ComparisonRun.EngineMeasurement m : List.of(baseline, lockFree)) {
                Color color = Theme.engineColor(m.kind());
                g2.setFont(Theme.ui(Font.BOLD, 13));
                g2.setColor(Theme.TEXT);
                g2.drawString(m.kind().displayName(), 4, y + 22);
                int bw = (int) (w * m.median() / max);
                g2.setColor(Theme.PANEL_2);
                g2.fillRoundRect(labelW, y + 6, w, 24, 8, 8);
                g2.setColor(color);
                g2.fillRoundRect(labelW, y + 6, Math.max(4, bw), 24, 8, 8);
                int x1 = labelW + (int) (w * m.min() / max);
                int x2 = labelW + (int) (w * m.max() / max);
                g2.setColor(Color.WHITE);
                g2.drawLine(x1, y + 18, x2, y + 18);
                g2.drawLine(x1, y + 12, x1, y + 24);
                g2.drawLine(x2, y + 12, x2, y + 24);
                g2.setFont(Theme.mono(Font.BOLD, 13));
                g2.setColor(Theme.TEXT);
                g2.drawString(String.format(Locale.ROOT, "%.2f M orders/s", m.median() / 1e6), labelW + w + 12,
                        y + 23);
                y += 44;
            }
            g2.dispose();
        }
    }
}
