package com.nanomatch.gui;

import com.nanomatch.demo.SessionSnapshot;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/** Trades reported by the engine, newest first. Optionally limited to the most recent rows. */
final class TradeTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"#", "Time", "Aggressor", "Buy order", "Sell order", "Price", "Quantity"};
    private static final String[] COMPACT = {"#", "Buy", "Sell", "Price", "Qty"};
    private static final int[] COMPACT_MAP = {0, 3, 4, 5, 6};

    private final int limit;
    private final boolean compact;
    private List<SessionSnapshot.Execution> trades = List.of();

    TradeTableModel(int limit) {
        this(limit, false);
    }

    /** @param compact only #, buy order, sell order, price and quantity (for narrow places) */
    TradeTableModel(int limit, boolean compact) {
        this.limit = limit;
        this.compact = compact;
    }

    void setTrades(List<SessionSnapshot.Execution> all) {
        if (all == trades) {
            return;
        }
        trades = all;
        fireTableDataChanged();
    }

    private SessionSnapshot.Execution at(int row) {
        return trades.get(trades.size() - 1 - row);
    }

    @Override
    public int getRowCount() {
        return limit > 0 ? Math.min(limit, trades.size()) : trades.size();
    }

    @Override
    public int getColumnCount() {
        return compact ? COMPACT.length : COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return compact ? COMPACT[column] : COLUMNS[column];
    }

    @Override
    public Object getValueAt(int row, int col) {
        SessionSnapshot.Execution t = at(row);
        int column = compact ? COMPACT_MAP[col] : col;
        switch (column) {
            case 0: return t.sequence();
            case 1: return Ui.time(t.timeMillis());
            case 2: return t.aggressorSide() + " #" + t.aggressorId();
            case 3: return "#" + t.buyOrderId();
            case 4: return "#" + t.sellOrderId();
            case 5: return t.price();
            case 6: return t.quantity();
            default: return "";
        }
    }
}
