package com.nanomatch.gui;

import com.nanomatch.demo.SessionSnapshot;
import com.nanomatch.model.Side;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;

/** Overview: engine status, key counters, top of the book, recent trades and the session's event log. */
final class DashboardPanel extends JPanel {

    private final Ui.StatTile engine = new Ui.StatTile("Engine", Theme.BASELINE);
    private final Ui.StatTile status = new Ui.StatTile("Status", Theme.OK);
    private final Ui.StatTile submitted = new Ui.StatTile("Orders submitted", Theme.ACCENT);
    private final Ui.StatTile processed = new Ui.StatTile("Orders processed", Theme.ACCENT);
    private final Ui.StatTile trades = new Ui.StatTile("Trades executed", Theme.WARN);
    private final Ui.StatTile volume = new Ui.StatTile("Volume traded", Theme.WARN);
    private final Ui.StatTile bestBid = new Ui.StatTile("Best bid", Theme.BUY);
    private final Ui.StatTile bestAsk = new Ui.StatTile("Best ask", Theme.SELL);
    private final Ui.StatTile spread = new Ui.StatTile("Spread", Theme.WARN);
    private final Ui.StatTile resting = new Ui.StatTile("Resting orders", Theme.MUTED);
    private final Ui.StatTile lastPrice = new Ui.StatTile("Last trade price", Theme.WARN);
    private final Ui.StatTile roundTrip = new Ui.StatTile("Last order round trip", Theme.MUTED);

    private final BookLadder ladder = new BookLadder(6, false);
    private final TradeTableModel recentTrades = new TradeTableModel(12, true);
    private final DefaultListModel<SessionSnapshot.Event> events = new DefaultListModel<>();
    private final Ui.Badge consistency = new Ui.Badge("", Theme.PANEL_3, Theme.TEXT);

    DashboardPanel(SessionController controller) {
        super(new BorderLayout(0, 12));
        setOpaque(false);
        add(Ui.pageHeader("Dashboard", "Live state of the real matching engine. Every number below comes from the "
                + "engine or from the trades it reported."), BorderLayout.NORTH);

        JPanel tiles = Ui.transparent(new GridLayout(2, 6, 10, 10));
        for (Ui.StatTile tile : List.of(engine, status, submitted, processed, trades, volume, bestBid, bestAsk, spread,
                resting, lastPrice, roundTrip)) {
            tiles.add(tile);
        }

        Ui.Card bookCard = new Ui.Card("Order book: top of book", "Best 6 levels per side. Full depth: Order Book")
                .headerRight(consistency);
        bookCard.body(Ui.scroll(ladder));

        JTable tradeTable = new JTable(recentTrades);
        Ui.styleTable(tradeTable);
        Ui.Card tradesCard = new Ui.Card("Recent trades", "Newest first, straight from the engine's TradeSink")
                .body(Ui.scroll(tradeTable));

        JList<SessionSnapshot.Event> eventList = new JList<>(events);
        eventList.setBackground(Theme.PANEL);
        eventList.setCellRenderer(new EventRenderer());
        Ui.Card eventCard = new Ui.Card("Session events", "Orders, trades and engine changes, newest first")
                .body(Ui.scroll(eventList));

        Ui.FlatButton add20 = Ui.primary("Add 20 random orders");
        Ui.FlatButton add200 = Ui.secondary("Add 200 random orders");
        Ui.FlatButton reset = Ui.secondary("Reset book (fresh engine)");
        add20.addActionListener(e -> controller.submitRandom(20, r -> { }));
        add200.addActionListener(e -> controller.submitRandom(200, r -> { }));
        reset.addActionListener(e -> controller.reset(controller.engineKind(), s -> { }));
        controller.onBusy(busy -> {
            add20.setEnabled(!busy);
            add200.setEnabled(!busy);
            reset.setEnabled(!busy);
        });
        JPanel actions = Ui.transparent(new BorderLayout(0, 8));
        actions.add(Ui.row(8, add20, add200, reset), BorderLayout.NORTH);
        actions.add(Ui.wrapped("Random orders come from the project's seeded OrderGenerator (prices 95 to 105, "
                + "quantity 1 to 20) and are submitted one by one to the engine.", 420, Theme.MUTED),
                BorderLayout.CENTER);
        Ui.Card actionCard = new Ui.Card("Quick actions").body(actions);

        JPanel left = Ui.transparent(new BorderLayout(0, 10));
        left.add(bookCard, BorderLayout.CENTER);
        left.add(actionCard, BorderLayout.SOUTH);
        JPanel right = Ui.transparent(new GridLayout(2, 1, 0, 10));
        right.add(tradesCard);
        right.add(eventCard);
        right.setPreferredSize(new java.awt.Dimension(560, 400));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        split.setDividerSize(10);

        JPanel center = Ui.transparent(new BorderLayout(0, 12));
        center.add(tiles, BorderLayout.NORTH);
        center.add(split, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        controller.onSnapshot(this::update);
    }

    private void update(SessionSnapshot s) {
        engine.setAccent(Theme.engineColor(s.engine()));
        engine.set(s.engine().className(), s.engine().displayName());
        switch (s.status()) {
            case RUNNING -> {
                status.set("RUNNING", s.statusDetail());
                status.setValueColor(Theme.OK);
                status.setAccent(Theme.OK);
            }
            case FAILED -> {
                status.set("FAILED", s.statusDetail());
                status.setValueColor(Theme.ERROR);
                status.setAccent(Theme.ERROR);
            }
            default -> {
                status.set("CLOSED", s.statusDetail());
                status.setValueColor(Theme.MUTED);
                status.setAccent(Theme.MUTED);
            }
        }
        submitted.set(Ui.integer(s.submitted()), "submittedCount()");
        processed.set(Ui.integer(s.processed()), s.processed() == s.submitted() ? "processedCount(), all done"
                : "processedCount(), in progress");
        trades.set(Ui.integer(s.trades().size()), "reported via TradeSink");
        volume.set(Ui.integer(s.tradedVolume()), "sum of trade quantities");
        bestBid.set(Ui.price(s.bestBid()), s.bids().isEmpty() ? "no buy orders resting"
                : Ui.integer(s.bids().get(0).totalQuantity()) + " at best bid");
        bestBid.setValueColor(Theme.BUY);
        bestAsk.set(Ui.price(s.bestAsk()), s.asks().isEmpty() ? "no sell orders resting"
                : Ui.integer(s.asks().get(0).totalQuantity()) + " at best ask");
        bestAsk.setValueColor(Theme.SELL);
        spread.set(s.spread() < 0 ? "—" : s.spread() + (s.spread() == 1 ? " tick" : " ticks"),
                s.spread() < 0 ? "needs both sides" : "best ask − best bid");
        resting.set(s.restingOrderCount(Side.BUY) + " / " + s.restingOrderCount(Side.SELL), "buy / sell orders");
        lastPrice.set(Ui.price(s.lastTradePrice()), s.trades().isEmpty() ? "no trades yet"
                : "resting order's price");
        roundTrip.set(s.lastRoundTripNanos() == 0 ? "—" : Ui.nanos(s.lastRoundTripNanos()),
                "GUI: submit → processed (not a benchmark)");

        SessionSnapshot.EngineView view = s.engineView();
        if (!view.available()) {
            consistency.set("engine check n/a", Theme.PANEL_3, Theme.MUTED);
        } else if (view.consistent()) {
            consistency.set("✓ matches engine's own report", new Color(0x123B2C), Theme.OK);
        } else {
            consistency.set("✗ differs from engine report", Theme.SELL_DIM, Theme.ERROR);
        }
        ladder.setSnapshot(s);
        recentTrades.setTrades(s.trades());
        events.clear();
        for (int i = s.events().size() - 1; i >= 0 && events.size() < 120; i--) {
            events.addElement(s.events().get(i));
        }
    }

    /** Colours events by kind. */
    static final class EventRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                      boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, false);
            SessionSnapshot.Event event = (SessionSnapshot.Event) value;
            label.setText(Ui.time(event.timeMillis()) + "   " + event.text());
            label.setFont(Theme.mono(Font.PLAIN, 12));
            label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            label.setBackground(selected ? Theme.SELECTION : index % 2 == 0 ? Theme.PANEL : Theme.PANEL_2);
            label.setForeground(switch (event.kind()) {
                case "TRADE" -> Theme.WARN;
                case "ERROR" -> Theme.ERROR;
                case "INFO" -> Theme.ACCENT;
                default -> event.text().startsWith("BUY") ? Theme.BUY : event.text().startsWith("SELL") ? Theme.SELL
                        : Theme.TEXT;
            });
            return label;
        }
    }
}
