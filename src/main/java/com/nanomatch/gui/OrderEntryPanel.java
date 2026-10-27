package com.nanomatch.gui;

import com.nanomatch.demo.SessionSnapshot;
import com.nanomatch.demo.SubmitResult;
import com.nanomatch.model.Side;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.text.ParseException;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;

/** Manual order entry: id, side, price and quantity go straight to the real engine. */
final class OrderEntryPanel extends JPanel {

    private final SessionController controller;
    private final JSpinner id = spinner(1, 1, Long.MAX_VALUE);
    private final JSpinner price = spinner(100, 1, 1_000_000_000);
    private final JSpinner quantity = spinner(10, 1, 1_000_000_000);
    private final JCheckBox autoId = new JCheckBox("next free id", true);
    private final Ui.FlatButton buy = Ui.sideButton(Side.BUY);
    private final Ui.FlatButton sell = Ui.sideButton(Side.SELL);
    private final Ui.FlatButton submit = new Ui.FlatButton("Submit", Theme.BUY);
    private final JLabel preview = Ui.label(" ", Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
    private final JLabel rangeNote = Ui.label(" ", Theme.ui(Font.PLAIN, 12f), Theme.FAINT);

    private final JLabel resultTitle = Ui.label("No order submitted yet", Theme.ui(Font.BOLD, 17), Theme.MUTED);
    private final JLabel resultDetail = Ui.label(" ", Theme.ui(Font.PLAIN, 13), Theme.TEXT);
    private final TradeTableModel lastFills = new TradeTableModel(0);

    private final BookLadder ladder = new BookLadder(8, true);
    private Side side = Side.BUY;
    private SessionSnapshot snapshot;

    OrderEntryPanel(SessionController controller) {
        super(new BorderLayout(0, 12));
        this.controller = controller;
        setOpaque(false);
        add(Ui.pageHeader("Order Entry", "Type a limit order and send it to the active engine. The result and the "
                + "book update as soon as the engine has processed it."), BorderLayout.NORTH);

        JPanel form = Ui.transparent(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 0, 6, 10);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        JPanel sides = Ui.transparent(new GridLayout(1, 2, 8, 0));
        buy.setPreferredSize(new Dimension(120, 44));
        sell.setPreferredSize(new Dimension(120, 44));
        buy.setFont(Theme.ui(Font.BOLD, 16));
        sell.setFont(Theme.ui(Font.BOLD, 16));
        sides.add(buy);
        sides.add(sell);
        buy.addActionListener(e -> setSide(Side.BUY));
        sell.addActionListener(e -> setSide(Side.SELL));

        autoId.setOpaque(false);
        autoId.setForeground(Theme.MUTED);
        autoId.addActionListener(e -> {
            if (autoId.isSelected() && snapshot != null) {
                id.setValue(snapshot.nextOrderId());
            }
        });
        // typing your own id switches the automatic id off
        ((JSpinner.DefaultEditor) id.getEditor()).getTextField().addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyTyped(java.awt.event.KeyEvent e) {
                autoId.setSelected(false);
            }
        });

        Ui.FlatButton atBid = Ui.secondary("= best bid");
        Ui.FlatButton atAsk = Ui.secondary("= best ask");
        atBid.setFont(Theme.ui(Font.PLAIN, 12));
        atAsk.setFont(Theme.ui(Font.PLAIN, 12));
        atBid.addActionListener(e -> {
            if (snapshot != null && snapshot.bestBid() > 0) {
                price.setValue(snapshot.bestBid());
            }
        });
        atAsk.addActionListener(e -> {
            if (snapshot != null && snapshot.bestAsk() > 0) {
                price.setValue(snapshot.bestAsk());
            }
        });

        int r = 0;
        addRow(form, c, r++, "Side", sides);
        addRow(form, c, r++, "Order id", Ui.row(8, wide(id, 130), autoId));
        addRow(form, c, r++, "Price (ticks)", Ui.row(8, wide(price, 130), atBid, atAsk));
        addRow(form, c, r++, "Quantity", Ui.row(8, wide(quantity, 130)));
        c.gridx = 1;
        c.gridy = r++;
        form.add(rangeNote, c);
        c.gridy = r++;
        c.insets = new Insets(12, 0, 4, 10);
        submit.setPreferredSize(new Dimension(320, 46));
        submit.setFont(Theme.ui(Font.BOLD, 15));
        form.add(submit, c);
        c.gridy = r;
        c.insets = new Insets(4, 0, 0, 10);
        form.add(preview, c);

        submit.addActionListener(e -> send());
        form.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "submit");
        form.getActionMap().put("submit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                send();
            }
        });
        for (JSpinner s : new JSpinner[] {price, quantity, id}) {
            s.addChangeListener(e -> refreshForm());
        }
        controller.onBusy(busy -> submit.setEnabled(!busy));

        Ui.Card formCard = new Ui.Card("New limit order", "Good-till-matched: whatever does not trade rests on the book")
                .body(form);

        JTable fills = new JTable(lastFills);
        Ui.styleTable(fills);
        JPanel resultBody = Ui.transparent(new BorderLayout(0, 8));
        JPanel resultText = Ui.transparent(new BorderLayout(0, 4));
        resultText.add(resultTitle, BorderLayout.NORTH);
        resultText.add(resultDetail, BorderLayout.CENTER);
        resultBody.add(resultText, BorderLayout.NORTH);
        JComponent fillsScroll = Ui.scroll(fills);
        fillsScroll.setPreferredSize(new Dimension(300, 150));
        resultBody.add(fillsScroll, BorderLayout.CENTER);
        Ui.Card resultCard = new Ui.Card("Result of the last order", "Trades it caused, as reported by the engine")
                .body(resultBody);

        JPanel left = Ui.transparent(new BorderLayout(0, 10));
        left.add(formCard, BorderLayout.NORTH);
        left.add(resultCard, BorderLayout.CENTER);

        Ui.Card bookCard = new Ui.Card("Order book", "Chips are resting orders: #id · remaining quantity. "
                + "Dashed outline = partially filled").body(Ui.scroll(ladder));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, bookCard);
        split.setResizeWeight(0.42);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        split.setDividerSize(10);
        add(split, BorderLayout.CENTER);

        setSide(Side.BUY);
        controller.onSnapshot(this::update);
    }

    private static JSpinner spinner(long value, long min, long max) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(Long.valueOf(value), Long.valueOf(min),
                Long.valueOf(max), Long.valueOf(1)));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
        spinner.setFont(Theme.mono(Font.PLAIN, 15));
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setFont(Theme.mono(Font.PLAIN, 15));
        return spinner;
    }

    private static JComponent wide(JComponent component, int width) {
        component.setPreferredSize(new Dimension(width, 34));
        return component;
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(Ui.label(label, Theme.ui(Font.BOLD, 13), Theme.MUTED), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
    }

    private void setSide(Side newSide) {
        side = newSide;
        buy.setBase(side == Side.BUY ? Theme.BUY : Theme.PANEL_3);
        sell.setBase(side == Side.SELL ? Theme.SELL : Theme.PANEL_3);
        buy.setForeground(side == Side.BUY ? Color.WHITE : Theme.MUTED);
        sell.setForeground(side == Side.SELL ? Color.WHITE : Theme.MUTED);
        submit.setBase(side == Side.BUY ? Theme.BUY : Theme.SELL);
        refreshForm();
    }

    private long value(JSpinner spinner) {
        try {
            spinner.commitEdit();
        } catch (ParseException ignored) {
            // keep the last valid value
        }
        return ((Number) spinner.getValue()).longValue();
    }

    private void refreshForm() {
        long p = ((Number) price.getValue()).longValue();
        long q = ((Number) quantity.getValue()).longValue();
        submit.setText("Submit  " + side + "  " + q + " @ " + p);
        submit.revalidate();
        if (snapshot == null) {
            return;
        }
        rangeNote.setText(snapshot.engine().maxPrice() == Long.MAX_VALUE
                ? snapshot.engine().displayName() + " accepts any price from 1 tick up"
                : snapshot.engine().displayName() + " accepts prices from " + snapshot.engine().minPrice() + " to "
                        + Ui.integer(snapshot.engine().maxPrice()) + " ticks");
        String text;
        Color color;
        if (side == Side.BUY) {
            long ask = snapshot.bestAsk();
            if (ask > 0 && p >= ask) {
                text = "Preview: crosses the best ask " + ask + ", so it should trade at the resting sell prices"
                        + (p > ask ? " (" + ask + " up to " + p + ")." : " (" + ask + ").");
                color = Theme.BUY;
            } else {
                text = ask > 0 ? "Preview: below the best ask " + ask + ", so it should rest as a bid."
                        : "Preview: no sell orders resting, so it should rest as a bid.";
                color = Theme.MUTED;
            }
        } else {
            long bid = snapshot.bestBid();
            if (bid > 0 && p <= bid) {
                text = "Preview: crosses the best bid " + bid + ", so it should trade at the resting buy prices"
                        + (p < bid ? " (" + bid + " down to " + p + ")." : " (" + bid + ").");
                color = Theme.SELL;
            } else {
                text = bid > 0 ? "Preview: above the best bid " + bid + ", so it should rest as an ask."
                        : "Preview: no buy orders resting, so it should rest as an ask.";
                color = Theme.MUTED;
            }
        }
        preview.setText(text);
        preview.setForeground(color);
    }

    private void send() {
        if (!submit.isEnabled()) {
            return;
        }
        long orderId = value(id);
        long p = value(price);
        long q = value(quantity);
        Side s = side;
        controller.submit(orderId, s, p, q, this::showResult);
    }

    private void showResult(SubmitResult r) {
        lastFills.setTrades(r.fills());
        if (!r.accepted()) {
            resultTitle.setText("Rejected");
            resultTitle.setForeground(Theme.ERROR);
            resultDetail.setText("<html><div style='width:380px'>" + Ui.escape(r.message()) + "</div></html>");
            return;
        }
        String state;
        Color color;
        if (r.fills().isEmpty()) {
            state = "Resting: no trade";
            color = Theme.MUTED;
        } else if (r.restedQuantity() == 0) {
            state = "Fully filled";
            color = Theme.OK;
        } else {
            state = "Partially filled";
            color = Theme.WARN;
        }
        resultTitle.setText(r.side() + " #" + r.orderId() + "  " + r.quantity() + " @ " + r.price() + "   →   "
                + state);
        resultTitle.setForeground(color);
        resultDetail.setText("<html>Ticket <b>" + r.ticket() + "</b> &nbsp;·&nbsp; traded <b>" + r.filledQuantity()
                + "</b> in " + r.fills().size() + " trade(s) &nbsp;·&nbsp; resting <b>" + r.restedQuantity()
                + "</b> &nbsp;·&nbsp; round trip " + Ui.nanos(r.roundTripNanos()) + "</html>");
    }

    private void update(SessionSnapshot s) {
        snapshot = s;
        if (autoId.isSelected()) {
            id.setValue(s.nextOrderId());
        }
        ladder.setSnapshot(s);
        refreshForm();
    }
}
