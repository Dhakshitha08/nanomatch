package com.nanomatch.gui;

import com.nanomatch.demo.SessionSnapshot;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.Locale;
import javax.swing.JPanel;
import javax.swing.JTable;

/** Every trade the engine reported in this session, newest first, with simple statistics. */
final class TradeHistoryPanel extends JPanel {

    private final TradeTableModel model = new TradeTableModel(0);
    private final Ui.StatTile count = new Ui.StatTile("Trades", Theme.WARN);
    private final Ui.StatTile volume = new Ui.StatTile("Volume", Theme.WARN);
    private final Ui.StatTile vwap = new Ui.StatTile("VWAP", Theme.ACCENT);
    private final Ui.StatTile last = new Ui.StatTile("Last price", Theme.ACCENT);
    private final Ui.StatTile high = new Ui.StatTile("High", Theme.BUY);
    private final Ui.StatTile low = new Ui.StatTile("Low", Theme.SELL);

    TradeHistoryPanel(SessionController controller) {
        super(new BorderLayout(0, 12));
        setOpaque(false);
        add(Ui.pageHeader("Trade History", "Each row is one call the engine made to its TradeSink: buy order, sell "
                + "order, execution price (always the resting order's price) and quantity."), BorderLayout.NORTH);

        JPanel tiles = Ui.transparent(new GridLayout(1, 6, 10, 0));
        for (Ui.StatTile tile : new Ui.StatTile[] {count, volume, vwap, last, high, low}) {
            tiles.add(tile);
        }
        JTable table = new JTable(model);
        Ui.styleTable(table);
        Ui.Card card = new Ui.Card("All trades in this session", "Newest first. Aggressor = the incoming order that "
                + "caused the trade; the other order was resting on the book.").body(Ui.scroll(table));

        JPanel center = Ui.transparent(new BorderLayout(0, 12));
        center.add(tiles, BorderLayout.NORTH);
        center.add(card, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        controller.onSnapshot(this::update);
    }

    private void update(SessionSnapshot s) {
        model.setTrades(s.trades());
        long qty = 0;
        double notional = 0;
        long hi = 0;
        long lo = Long.MAX_VALUE;
        for (SessionSnapshot.Execution t : s.trades()) {
            qty += t.quantity();
            notional += (double) t.price() * t.quantity();
            hi = Math.max(hi, t.price());
            lo = Math.min(lo, t.price());
        }
        boolean any = !s.trades().isEmpty();
        count.set(Ui.integer(s.trades().size()), "executions");
        volume.set(Ui.integer(qty), "units traded");
        vwap.set(any ? String.format(Locale.ROOT, "%.2f", notional / qty) : "—", "volume-weighted avg price");
        last.set(any ? Long.toString(s.lastTradePrice()) : "—", any ? "most recent trade" : "no trades yet");
        high.set(any ? Long.toString(hi) : "—", "highest trade price");
        low.set(any ? Long.toString(lo) : "—", "lowest trade price");
    }
}
