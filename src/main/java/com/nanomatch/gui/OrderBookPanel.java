package com.nanomatch.gui;

import com.nanomatch.demo.SessionSnapshot;
import com.nanomatch.model.Side;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/** Full depth of the book, every resting order, and the cross-check against the engine's own report. */
final class OrderBookPanel extends JPanel {

    private final BookLadder ladder = new BookLadder(20, true);
    private final RestingModel restingModel = new RestingModel();
    private final Ui.StatTile bestBid = new Ui.StatTile("Best bid", Theme.BUY);
    private final Ui.StatTile bestAsk = new Ui.StatTile("Best ask", Theme.SELL);
    private final Ui.StatTile spread = new Ui.StatTile("Spread", Theme.WARN);
    private final Ui.StatTile depth = new Ui.StatTile("Price levels", Theme.MUTED);
    private final JLabel[][] check = new JLabel[4][3];
    private final Ui.Badge verdict = new Ui.Badge("", Theme.PANEL_3, Theme.TEXT);

    OrderBookPanel(SessionController controller) {
        super(new BorderLayout(0, 12));
        setOpaque(false);
        add(Ui.pageHeader("Order Book", "Resting orders by price level. Sells on top, buys below; within a level the "
                + "left-most order is first in line (time priority)."), BorderLayout.NORTH);

        JPanel tiles = Ui.transparent(new GridLayout(1, 4, 10, 0));
        tiles.add(bestBid);
        tiles.add(bestAsk);
        tiles.add(spread);
        tiles.add(depth);

        Ui.Card ladderCard = new Ui.Card("Price ladder", "Hover a level to see its queue in detail")
                .body(Ui.scroll(ladder));

        JTable table = new JTable(restingModel);
        Ui.styleTable(table);
        Ui.Card restingCard = new Ui.Card("Resting orders", "Best price first; queue position 1 trades next")
                .body(Ui.scroll(table));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, ladderCard, restingCard);
        split.setResizeWeight(0.55);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        split.setDividerSize(10);

        JPanel grid = Ui.transparent(new GridLayout(5, 4, 12, 4));
        for (String h : new String[] {"", "Rebuilt from trades", "Engine reports", "Match"}) {
            grid.add(Ui.label(h, Theme.ui(Font.BOLD, 12), Theme.MUTED));
        }
        String[] names = {"Best bid", "Best ask", "Resting buy quantity", "Resting sell quantity"};
        for (int r = 0; r < 4; r++) {
            grid.add(Ui.label(names[r], Theme.ui(Font.PLAIN, 13), Theme.TEXT));
            for (int col = 0; col < 3; col++) {
                check[r][col] = Ui.label("—", col == 2 ? Theme.ui(Font.BOLD, 15) : Theme.mono(Font.PLAIN, 13),
                        Theme.TEXT);
                grid.add(check[r][col]);
            }
        }
        JPanel checkBody = Ui.transparent(new BorderLayout(16, 0));
        checkBody.add(grid, BorderLayout.CENTER);
        checkBody.add(Ui.wrapped("The ladder is rebuilt from the orders sent and the trades the engine reported, "
                + "because the engines do not expose their queues. After every order the engine is idle, so its "
                + "read-only bestBidPrice(), bestAskPrice() and restingQuantity() are read and compared.", 360,
                Theme.MUTED), BorderLayout.EAST);
        Ui.Card checkCard = new Ui.Card("Cross-check with the engine", null).headerRight(verdict).body(checkBody);

        JPanel center = Ui.transparent(new BorderLayout(0, 12));
        center.add(tiles, BorderLayout.NORTH);
        center.add(split, BorderLayout.CENTER);
        center.add(checkCard, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        controller.onSnapshot(this::update);
    }

    private void update(SessionSnapshot s) {
        ladder.setSnapshot(s);
        restingModel.set(s);
        bestBid.set(Ui.price(s.bestBid()), s.bids().isEmpty() ? "no buy orders" : "highest resting buy price");
        bestBid.setValueColor(Theme.BUY);
        bestAsk.set(Ui.price(s.bestAsk()), s.asks().isEmpty() ? "no sell orders" : "lowest resting sell price");
        bestAsk.setValueColor(Theme.SELL);
        spread.set(s.spread() < 0 ? "—" : s.spread() + (s.spread() == 1 ? " tick" : " ticks"),
                s.spread() < 0 ? "needs both sides" : "best ask − best bid");
        depth.set(s.bids().size() + " / " + s.asks().size(), "bid levels / ask levels");

        SessionSnapshot.EngineView v = s.engineView();
        long[][] values = {
            {s.bestBid(), v.bestBid()},
            {s.bestAsk(), v.bestAsk()},
            {s.restingQuantity(Side.BUY), v.bidQuantity()},
            {s.restingQuantity(Side.SELL), v.askQuantity()},
        };
        for (int r = 0; r < 4; r++) {
            boolean priceRow = r < 2;
            check[r][0].setText(priceRow ? Ui.price(values[r][0]) : Ui.integer(values[r][0]));
            if (!v.available()) {
                check[r][1].setText("n/a");
                check[r][2].setText("—");
                check[r][2].setForeground(Theme.MUTED);
                continue;
            }
            check[r][1].setText(priceRow ? Ui.price(values[r][1]) : Ui.integer(values[r][1]));
            boolean same = values[r][0] == values[r][1];
            check[r][2].setText(same ? "✓" : "✗");
            check[r][2].setForeground(same ? Theme.OK : Theme.ERROR);
        }
        if (!v.available()) {
            verdict.set("engine report not available", Theme.PANEL_3, Theme.MUTED);
        } else if (v.consistent()) {
            verdict.set("✓ consistent", new Color(0x123B2C), Theme.OK);
        } else {
            verdict.set("✗ inconsistent", Theme.SELL_DIM, Theme.ERROR);
        }
    }

    /** Resting orders in book priority: bids best first, then asks best first, FIFO within a level. */
    private static final class RestingModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Side", "Price", "Queue", "Order", "Ticket", "Qty",
            "Filled", "Left"};

        private record RowData(SessionSnapshot.RestingOrder order, int position) {
        }

        private List<RowData> rows = List.of();

        void set(SessionSnapshot s) {
            List<RowData> list = new ArrayList<>();
            for (List<SessionSnapshot.Level> side : List.of(s.bids(), s.asks())) {
                for (SessionSnapshot.Level level : side) {
                    for (int i = 0; i < level.queue().size(); i++) {
                        list.add(new RowData(level.queue().get(i), i + 1));
                    }
                }
            }
            rows = list;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            RowData r = rows.get(row);
            SessionSnapshot.RestingOrder o = r.order();
            switch (column) {
                case 0: return o.side();
                case 1: return o.price();
                case 2: return r.position();
                case 3: return "#" + o.orderId();
                case 4: return o.ticket();
                case 5: return o.originalQuantity();
                case 6: return o.originalQuantity() - o.remainingQuantity();
                case 7: return o.remainingQuantity();
                default: return "";
            }
        }
    }
}
