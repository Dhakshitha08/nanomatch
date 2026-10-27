package com.nanomatch.gui;

import com.nanomatch.demo.DemoScenario;
import com.nanomatch.demo.DemoScenarios;
import com.nanomatch.demo.SessionSnapshot;
import com.nanomatch.demo.SubmitResult;
import com.nanomatch.model.Order;
import com.nanomatch.model.Trade;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;

/**
 * Scripted demonstrations of the matching rules. Each scenario starts a fresh engine of the active type, sends its
 * orders one at a time (step by step or automatically) and compares the engine's actual trades with the trades the
 * rule requires.
 */
final class ScenarioPanel extends JPanel {

    private final SessionController controller;
    private final List<DemoScenario> scenarios = DemoScenarios.all();
    private final JList<DemoScenario> list = new JList<>(scenarios.toArray(new DemoScenario[0]));
    private final JLabel title = Ui.label(" ", Theme.ui(Font.BOLD, 18), Theme.TEXT);
    private final javax.swing.JTextArea rule = Ui.textBlock(" ", Theme.ui(Font.PLAIN, 13.5f), Theme.ACCENT);
    private final JPanel stepsPanel = Ui.transparent(null);
    private final List<StepRow> stepRows = new ArrayList<>();
    private final Ui.FlatButton start = Ui.primary("Start");
    private final Ui.FlatButton next = new Ui.FlatButton("Next order ▶", Theme.BUY);
    private final Ui.FlatButton runAll = Ui.secondary("Run all");
    private final javax.swing.JTextArea engineNote = Ui.textBlock(" ", Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
    private final Ui.Badge verdict = new Ui.Badge("not run", Theme.PANEL_3, Theme.MUTED);
    private final javax.swing.JTextArea explanation = Ui.textBlock(" ", Theme.ui(Font.PLAIN, 13), Theme.TEXT);
    private final BookLadder ladder = new BookLadder(6, true);
    private final TradeTableModel trades = new TradeTableModel(0, true);
    private final Timer autoTimer = new Timer(900, e -> step());

    private DemoScenario current;
    private int nextStep = -1; // -1 = not started
    private boolean waiting;
    private final List<Trade> actual = new ArrayList<>();

    ScenarioPanel(SessionController controller) {
        super(new BorderLayout(0, 12));
        this.controller = controller;
        setOpaque(false);
        add(Ui.pageHeader("Demo Scenarios", "Scripted orders that show one matching rule each. The orders go to the "
                + "real engine; the trades shown are the engine's own output."), BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.PANEL);
        list.setCellRenderer(new ScenarioRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                select(list.getSelectedValue());
            }
        });
        Ui.Card listCard = new Ui.Card("Scenarios", null).body(Ui.scroll(list));
        listCard.setPreferredSize(new Dimension(255, 400));

        stepsPanel.setLayout(new BoxLayout(stepsPanel, BoxLayout.Y_AXIS));
        start.addActionListener(e -> begin(false));
        next.addActionListener(e -> step());
        runAll.addActionListener(e -> begin(true));
        autoTimer.setRepeats(true);

        JPanel head = Ui.transparent(new BorderLayout(0, 4));
        head.add(title, BorderLayout.NORTH);
        head.add(rule, BorderLayout.CENTER);
        head.add(engineNote, BorderLayout.SOUTH);
        JPanel buttons = Ui.row(8, start, next, runAll);
        JPanel resultBox = Ui.transparent(new BorderLayout(0, 6));
        resultBox.add(Ui.label("Check against the rule:", Theme.ui(Font.BOLD, 13), Theme.MUTED), BorderLayout.NORTH);
        resultBox.add(Ui.row(0, verdict), BorderLayout.CENTER);
        resultBox.add(explanation, BorderLayout.SOUTH);

        JPanel detail = Ui.transparent(new BorderLayout(0, 12));
        detail.add(head, BorderLayout.NORTH);
        JPanel middle = Ui.transparent(new BorderLayout(0, 10));
        middle.add(buttons, BorderLayout.NORTH);
        middle.add(stepsPanel, BorderLayout.CENTER);
        detail.add(middle, BorderLayout.CENTER);
        detail.add(resultBox, BorderLayout.SOUTH);
        Ui.Card detailCard = new Ui.Card(null).body(detail);

        JTable tradeTable = new JTable(trades);
        Ui.styleTable(tradeTable);
        JComponent tradeScroll = Ui.scroll(tradeTable);
        tradeScroll.setPreferredSize(new Dimension(300, 160));
        JPanel right = Ui.transparent(new GridLayout(2, 1, 0, 10));
        right.add(new Ui.Card("Book now", "Chips: #id · remaining (left = first in line)").body(Ui.scroll(ladder)));
        right.add(new Ui.Card("Trades reported by the engine", null).body(tradeScroll));
        right.setPreferredSize(new Dimension(410, 400));

        JPanel center = Ui.transparent(new BorderLayout(12, 0));
        center.add(listCard, BorderLayout.WEST);
        center.add(detailCard, BorderLayout.CENTER);
        center.add(right, BorderLayout.EAST);
        add(center, BorderLayout.CENTER);

        controller.onSnapshot(s -> {
            ladder.setSnapshot(s);
            trades.setTrades(s.trades());
            engineNote.setText("Runs on: " + s.engine().displayName() + ". Start clears the book (fresh engine of the "
                    + "same type). Switch engines on Engine Comparison.");
            engineNote.setForeground(Theme.engineColor(s.engine()));
        });
        list.setSelectedIndex(0);
    }

    private void select(DemoScenario scenario) {
        autoTimer.stop();
        current = scenario;
        nextStep = -1;
        actual.clear();
        title.setText(scenario.title());
        rule.setText("Rule: " + scenario.rule());
        stepsPanel.removeAll();
        stepRows.clear();
        int n = 1;
        for (DemoScenario.Step step : scenario.steps()) {
            StepRow row = new StepRow(n++, step);
            stepRows.add(row);
            stepsPanel.add(row);
        }
        stepsPanel.revalidate();
        stepsPanel.repaint();
        verdict.set("not run", Theme.PANEL_3, Theme.MUTED);
        explanation.setText(" ");
        updateButtons();
    }

    private void begin(boolean automatic) {
        if (current == null || waiting) {
            return;
        }
        autoTimer.stop();
        select(current);
        waiting = true;
        updateButtons();
        controller.reset(controller.engineKind(), snapshot -> {
            waiting = false;
            nextStep = 0;
            stepRows.get(0).setState(StepRow.State.NEXT, null);
            updateButtons();
            if (automatic) {
                autoTimer.setInitialDelay(500);
                autoTimer.start();
            }
        });
    }

    private void step() {
        if (current == null || nextStep < 0 || nextStep >= current.steps().size() || waiting) {
            if (nextStep >= (current == null ? 0 : current.steps().size())) {
                autoTimer.stop();
            }
            return;
        }
        final int index = nextStep;
        Order order = current.steps().get(index).order();
        waiting = true;
        updateButtons();
        controller.submit(order.id(), order.side(), order.price(), order.quantity(), result -> {
            waiting = false;
            onResult(index, result);
        });
    }

    private void onResult(int index, SubmitResult result) {
        StepRow row = stepRows.get(index);
        if (!result.accepted()) {
            row.setState(StepRow.State.REJECTED, result.message());
            autoTimer.stop();
            nextStep = -1;
            verdict.set("✗ order rejected", Theme.SELL_DIM, Theme.ERROR);
            updateButtons();
            return;
        }
        StringBuilder text = new StringBuilder();
        for (SessionSnapshot.Execution fill : result.fills()) {
            actual.add(new Trade(fill.buyOrderId(), fill.sellOrderId(), fill.price(), fill.quantity()));
            text.append(text.length() == 0 ? "" : ",  ").append("TRADE ").append(fill.quantity()).append(" @ ")
                    .append(fill.price()).append(" with #").append(fill.restingOrderId());
        }
        if (result.restedQuantity() > 0) {
            text.append(text.length() == 0 ? "" : ",  ").append("rests ").append(result.restedQuantity());
        }
        row.setState(StepRow.State.DONE, text.toString());
        nextStep = index + 1;
        if (nextStep < stepRows.size()) {
            stepRows.get(nextStep).setState(StepRow.State.NEXT, null);
        } else {
            autoTimer.stop();
            DemoScenario.Check check = current.check(actual);
            verdict.set((check.passed() ? "✓ " : "✗ ") + (check.passed() ? "PASS: " + actual.size()
                            + " trade(s), exactly as the rule requires" : check.summary()),
                    check.passed() ? new Color(0x123B2C) : Theme.SELL_DIM, check.passed() ? Theme.OK : Theme.ERROR);
            explanation.setText(current.explanation());
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean started = nextStep >= 0 && current != null && nextStep < current.steps().size();
        next.setEnabled(started && !waiting && !autoTimer.isRunning());
        start.setEnabled(!waiting && !autoTimer.isRunning());
        runAll.setEnabled(!waiting && !autoTimer.isRunning());
    }

    /** One order of the scenario with its narration and what happened. */
    private static final class StepRow extends JPanel {
        enum State { PENDING, NEXT, DONE, REJECTED }

        private final int number;
        private final DemoScenario.Step step;
        private final JLabel outcome = Ui.label(" ", Theme.mono(Font.BOLD, 12.5f), Theme.WARN);
        private State state = State.PENDING;

        StepRow(int number, DemoScenario.Step step) {
            super(new BorderLayout(10, 2));
            this.number = number;
            this.step = step;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 52, 8, 10));
            Order o = step.order();
            JLabel order = Ui.label("<html><span style='color:" + Ui.hex(Theme.sideColor(o.side())) + "'><b>"
                    + o.side() + "</b></span> &nbsp;#" + o.id() + " &nbsp; <b>" + o.quantity() + " @ " + o.price()
                    + "</b></html>", Theme.mono(Font.PLAIN, 14), Theme.TEXT);
            javax.swing.JTextArea narration = Ui.textBlock(step.narration(), Theme.ui(Font.PLAIN, 13), Theme.MUTED);
            JPanel text = Ui.transparent(new BorderLayout(0, 3));
            text.add(order, BorderLayout.NORTH);
            text.add(narration, BorderLayout.CENTER);
            text.add(outcome, BorderLayout.SOUTH);
            add(text, BorderLayout.CENTER);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        void setState(State newState, String detail) {
            state = newState;
            if (detail != null) {
                outcome.setText(detail.isEmpty() ? "no trade" : detail);
                outcome.setForeground(newState == State.REJECTED ? Theme.ERROR : Theme.WARN);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Ui.antialias(g2);
            if (state == State.NEXT) {
                g2.setColor(Theme.PANEL_2);
                g2.fillRoundRect(0, 2, getWidth() - 1, getHeight() - 4, 10, 10);
            }
            Color circle = switch (state) {
                case DONE -> Theme.OK;
                case NEXT -> Theme.ACCENT;
                case REJECTED -> Theme.ERROR;
                default -> Theme.PANEL_3;
            };
            g2.setColor(circle);
            g2.fillOval(12, 12, 28, 28);
            g2.setColor(state == State.PENDING ? Theme.MUTED : Color.WHITE);
            g2.setFont(Theme.ui(Font.BOLD, 13));
            String text = state == State.DONE ? "✓" : Integer.toString(number);
            g2.drawString(text, 26 - g2.getFontMetrics().stringWidth(text) / 2, 31);
            g2.dispose();
        }
    }

    private static final class ScenarioRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                      boolean focus) {
            DemoScenario s = (DemoScenario) value;
            JLabel label = (JLabel) super.getListCellRendererComponent(list, "", index, selected, false);
            label.setText("<html><div style='width:190px'><b>" + (index + 1) + ". " + Ui.escape(s.title())
                    + "</b><br><span style='color:"
                    + Ui.hex(Theme.MUTED) + "'>" + s.steps().size() + " orders · " + s.expectedTrades().size()
                    + " expected trade(s)</span></div></html>");
            label.setFont(Theme.ui(Font.PLAIN, 13));
            label.setForeground(Theme.TEXT);
            label.setBackground(selected ? Theme.SELECTION : Theme.PANEL);
            label.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            return label;
        }
    }
}
