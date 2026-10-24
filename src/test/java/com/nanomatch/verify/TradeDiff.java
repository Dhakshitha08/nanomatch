package com.nanomatch.verify;

import com.nanomatch.model.Trade;
import java.util.List;

/**
 * Compares two trade lists exactly and, when they differ, explains where: the first index at which they
 * disagree, plus a few trades before it for context. "Trade lists differ" is useless when debugging a
 * 100,000-order run; "trade #41,873 differs" is not.
 */
final class TradeDiff {

    private static final int CONTEXT = 3;

    private TradeDiff() {
    }

    /** Returns null if the lists are identical (same trades, same order), otherwise a readable description. */
    static String describeFirstDifference(List<Trade> left, List<Trade> right) {
        int common = Math.min(left.size(), right.size());
        int index = 0;
        while (index < common && left.get(index).equals(right.get(index))) {
            index++;
        }
        if (index == common && left.size() == right.size()) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        text.append("trade counts: left=").append(left.size()).append(", right=").append(right.size()).append('\n');
        text.append("first difference at index ").append(index).append('\n');
        for (int i = Math.max(0, index - CONTEXT); i <= index; i++) {
            text.append(i == index ? ">>> " : "    ")
                    .append('#').append(i)
                    .append("  left=").append(i < left.size() ? left.get(i) : "(missing)")
                    .append("  right=").append(i < right.size() ? right.get(i) : "(missing)")
                    .append('\n');
        }
        return text.toString();
    }

    /** Fails with a readable message unless the two lists are identical. */
    static void assertIdentical(String context, String leftName, List<Trade> left, String rightName,
                                List<Trade> right) {
        String difference = describeFirstDifference(left, right);
        if (difference != null) {
            throw new AssertionError(context + ": " + leftName + " (left) and " + rightName
                    + " (right) produced different trades\n" + difference);
        }
    }
}
