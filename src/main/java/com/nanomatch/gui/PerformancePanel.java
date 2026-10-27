package com.nanomatch.gui;

import com.nanomatch.demo.BenchmarkResults;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;

/**
 * The measured JMH results (frozen benchmark runs), as charts and a table. Values are read from the results
 * folder; a value that was not measured is shown as "n/a".
 */
final class PerformancePanel extends JPanel {

    private static final String[] VIEWS = {"Throughput", "Steady latency", "Burst latency"};
    private static final String[] VIEW_HELP = {
        "Orders per second the engine accepted, with every producer submitting as fast as it can (JMH Throughput "
                + "mode). Higher is better.",
        "One sample = submit one order and wait until processedCount() passes its ticket; one order in flight per "
                + "producer (JMH SampleTime mode). Lower is better.",
        "One sample = 32 orders back to back, then wait until the last is processed; the time to absorb a whole "
                + "burst (JMH SampleTime mode). Lower is better.",
    };

    private final BarChart chart = new BarChart();
    private final JToggleButton[] viewButtons = new JToggleButton[VIEWS.length];
    private final JComboBox<String> percentile = new JComboBox<>(BenchmarkResults.PERCENTILES.toArray(new String[0]));
    private final JCheckBox logScale = new JCheckBox("Log scale", true);
    private final JLabel viewHelp = Ui.label(" ", Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
    private final JLabel source = Ui.label("Looking for measured results…", Theme.ui(Font.PLAIN, 12.5f), Theme.MUTED);
    private final JLabel environment = Ui.label(" ", Theme.ui(Font.PLAIN, 12f), Theme.FAINT);
    private final JLabel findings = Ui.label(" ", Theme.ui(Font.PLAIN, 13), Theme.TEXT);
    private final MetricsModel tableModel = new MetricsModel();
    private BenchmarkResults results;
    private int view;

    PerformancePanel() {
        super(new BorderLayout(0, 12));
        setOpaque(false);
        add(Ui.pageHeader("Performance", "Measured results of the JMH benchmark (EngineBenchmark), median of the "
                + "repeated runs. Nothing on this page is simulated."), BorderLayout.NORTH);

        ButtonGroup group = new ButtonGroup();
        JPanel segmented = Ui.row(6);
        for (int i = 0; i < VIEWS.length; i++) {
            JToggleButton button = new JToggleButton(VIEWS[i]);
            button.setFont(Theme.ui(Font.BOLD, 12.5f));
            button.setFocusPainted(false);
            final int index = i;
            button.addActionListener(e -> {
                view = index;
                logScale.setSelected(index != 0);
                refresh();
            });
            group.add(button);
            segmented.add(button);
            viewButtons[i] = button;
        }
        viewButtons[0].setSelected(true);
        percentile.setSelectedItem("p99");
        percentile.addActionListener(e -> refresh());
        logScale.setOpaque(false);
        logScale.setForeground(Theme.TEXT);
        logScale.setSelected(false);
        logScale.addActionListener(e -> refresh());
        Ui.FlatButton open = Ui.secondary("Open results folder…");
        open.addActionListener(e -> chooseFolder());

        JPanel controls = Ui.row(12, segmented, Ui.label("Percentile", Theme.ui(Font.BOLD, 12.5f), Theme.MUTED),
                percentile, logScale);

        JPanel chartBody = Ui.transparent(new BorderLayout(0, 8));
        chartBody.add(controls, BorderLayout.NORTH);
        chartBody.add(chart, BorderLayout.CENTER);
        chartBody.add(viewHelp, BorderLayout.SOUTH);
        Ui.Card chartCard = new Ui.Card("Baseline vs lock-free by number of producer threads", null).body(chartBody);

        JPanel findingsBody = Ui.transparent(new BorderLayout(0, 10));
        findingsBody.add(findings, BorderLayout.NORTH);
        findingsBody.setOpaque(false);
        javax.swing.JScrollPane findingsScroll = Ui.scroll(findingsBody);
        findingsScroll.setBorder(BorderFactory.createEmptyBorder());
        Ui.Card findingsCard = new Ui.Card("What the measurements show", "Computed from the numbers, not written "
                + "by hand").body(findingsScroll);
        findingsCard.setPreferredSize(new Dimension(360, 300));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chartCard, findingsCard);
        split.setResizeWeight(0.7);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        split.setDividerSize(10);

        JTable table = new JTable(tableModel);
        Ui.styleTable(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(8).setPreferredWidth(220);
        JPanel tableBody = Ui.transparent(new BorderLayout(0, 6));
        tableBody.add(Ui.scroll(table), BorderLayout.CENTER);
        JPanel sourceBox = Ui.transparent(new BorderLayout(0, 2));
        sourceBox.add(source, BorderLayout.NORTH);
        sourceBox.add(environment, BorderLayout.CENTER);
        tableBody.add(sourceBox, BorderLayout.SOUTH);
        Ui.Card tableCard = new Ui.Card("All metrics", "Latency in µs, throughput in million orders per second. "
                + "\"n/a\" = not measured").headerRight(open).body(tableBody);
        tableCard.setPreferredSize(new Dimension(800, 250));

        JPanel center = Ui.transparent(new BorderLayout(0, 12));
        center.add(split, BorderLayout.CENTER);
        center.add(tableCard, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        Optional<Path> folder = defaultFolder();
        if (folder.isPresent()) {
            load(folder.get());
        } else {
            source.setText("No measured results found (expected results/YYYY-MM-DD/summary.csv). Use \"Open results "
                    + "folder…\".");
            source.setForeground(Theme.WARN);
        }
    }

    private static Optional<Path> defaultFolder() {
        Optional<Path> fromWorkingDir = BenchmarkResults.findDefaultDirectory(Path.of(""));
        if (fromWorkingDir.isPresent()) {
            return fromWorkingDir;
        }
        try {
            Path code = Path.of(PerformancePanel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return BenchmarkResults.findDefaultDirectory(code.getParent());
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(results != null ? results.directory().toFile() : new File("."));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose a results folder that contains summary.csv");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Optional<Path> found = BenchmarkResults.findDefaultDirectory(chooser.getSelectedFile().toPath());
            load(found.orElse(chooser.getSelectedFile().toPath()));
        }
    }

    private void load(Path folder) {
        source.setText("Reading " + folder + " …");
        new SwingWorker<BenchmarkResults, Void>() {
            @Override
            protected BenchmarkResults doInBackground() throws Exception {
                return BenchmarkResults.load(folder);
            }

            @Override
            protected void done() {
                try {
                    results = get();
                    source.setText("Source: " + results.directory().toAbsolutePath() + "  ·  " + results.runs()
                            + " runs per value. p50 / p99 / p99.9 / throughput from the frozen summary.csv; p90 / p95 "
                            + "from the raw JMH JSON files of the same runs.");
                    source.setForeground(Theme.MUTED);
                    environment.setText(String.join("   ·   ", results.environment()));
                    tableModel.set(results);
                    refresh();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    source.setText("Could not read results: " + cause.getMessage());
                    source.setForeground(Theme.ERROR);
                }
            }
        }.execute();
    }

    private void refresh() {
        percentile.setEnabled(view != 0);
        viewHelp.setText("<html><div style='width:600px'>" + VIEW_HELP[view] + "</div></html>");
        if (results == null) {
            return;
        }
        String benchmark = view == 0 ? BenchmarkResults.THROUGHPUT : view == 1 ? BenchmarkResults.STEADY
                : BenchmarkResults.BURST;
        String metric = view == 0 ? BenchmarkResults.OPS : (String) percentile.getSelectedItem();
        List<BarChart.Group> groups = new ArrayList<>();
        for (int p : results.producers()) {
            groups.add(new BarChart.Group(p + (p == 1 ? " producer" : " producers"), List.of(
                    new BarChart.Bar("Lock-based", Theme.BASELINE,
                            results.get(p, BenchmarkResults.BASELINE, benchmark, metric).orElse(null)),
                    new BarChart.Bar("Lock-free", Theme.LOCK_FREE,
                            results.get(p, BenchmarkResults.LOCK_FREE, benchmark, metric).orElse(null)))));
        }
        if (view == 0) {
            chart.setData(groups, logScale.isSelected(), "Million orders per second (higher is better)", 1e6,
                    v -> String.format(Locale.ROOT, "%.2f", v));
        } else {
            chart.setData(groups, logScale.isSelected(), metric + " latency in µs (lower is better)", 1e3,
                    v -> String.format(Locale.ROOT, v < 10 ? "%.2f" : v < 100 ? "%.1f" : "%.0f", v));
        }
        findings.setText(findingsHtml());
    }

    /** Which engine wins each comparison, and by how much, derived from the medians. */
    private String findingsHtml() {
        String muted = Ui.hex(Theme.MUTED);
        StringBuilder sb = new StringBuilder("<html><table cellspacing='0' cellpadding='3'><tr><td style='color:")
                .append(muted).append("'>producers →</td>");
        for (int p : results.producers()) {
            sb.append("<td align='right' style='color:").append(muted).append("'><b>").append(p).append("</b></td>");
        }
        sb.append("</tr>");
        appendRow(sb, "Throughput", BenchmarkResults.THROUGHPUT, BenchmarkResults.OPS, true);
        appendRow(sb, "Steady p50", BenchmarkResults.STEADY, "p50", false);
        appendRow(sb, "Steady p99", BenchmarkResults.STEADY, "p99", false);
        appendRow(sb, "Steady p99.9", BenchmarkResults.STEADY, "p99.9", false);
        appendRow(sb, "Burst p50", BenchmarkResults.BURST, "p50", false);
        appendRow(sb, "Burst p99", BenchmarkResults.BURST, "p99", false);
        appendRow(sb, "Burst p99.9", BenchmarkResults.BURST, "p99.9", false);
        sb.append("</table><div style='width:265px'><p style='color:").append(Ui.hex(Theme.LOCK_FREE))
                .append("'>LF = lock-free better</p><p style='color:").append(Ui.hex(Theme.BASELINE))
                .append("'>LB = lock-based better</p><p style='color:").append(Ui.hex(Theme.FAINT))
                .append("'>The factor compares medians of the runs (for latency, lower is better). Values of 0.1 to "
                        + "0.3 µs are near the timer resolution of the benchmark machine. Not measured, so not "
                        + "shown: mean and maximum latency, garbage-collection pauses.</p></div></html>");
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, String title, String benchmark, String metric, boolean higherIsBetter) {
        sb.append("<tr><td><b>").append(title).append("</b>&nbsp;</td>");
        for (int p : results.producers()) {
            Optional<BenchmarkResults.Stat> b = results.get(p, BenchmarkResults.BASELINE, benchmark, metric);
            Optional<BenchmarkResults.Stat> l = results.get(p, BenchmarkResults.LOCK_FREE, benchmark, metric);
            sb.append("<td align='right'>");
            if (b.isEmpty() || l.isEmpty()) {
                sb.append("<span style='color:").append(Ui.hex(Theme.FAINT)).append("'>n/a</span>");
            } else if (b.get().median() == l.get().median()) {
                sb.append("<span style='color:").append(Ui.hex(Theme.MUTED)).append("'>equal</span>");
            } else {
                double bv = b.get().median();
                double lv = l.get().median();
                boolean lockFreeWins = higherIsBetter ? lv > bv : lv < bv;
                Color color = lockFreeWins ? Theme.LOCK_FREE : Theme.BASELINE;
                sb.append("<span style='color:").append(Ui.hex(color)).append("'>").append(lockFreeWins ? "LF " : "LB ")
                        .append(String.format(Locale.ROOT, "%.1f×", Math.max(bv, lv) / Math.min(bv, lv)))
                        .append("</span>");
            }
            sb.append("</td>");
        }
        sb.append("</tr>");
    }

    /** Every measured value, baseline and lock-free side by side. */
    private static final class MetricsModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Producers", "Benchmark", "Metric", "Lock-based", "Lock-free",
            "Better", "Ratio", "Stable (LB / LF)", "Source"};
        private final List<Object[]> rows = new ArrayList<>();

        void set(BenchmarkResults r) {
            rows.clear();
            String[][] metrics = {
                {BenchmarkResults.THROUGHPUT, BenchmarkResults.OPS},
                {BenchmarkResults.STEADY, "p50"}, {BenchmarkResults.STEADY, "p90"}, {BenchmarkResults.STEADY, "p95"},
                {BenchmarkResults.STEADY, "p99"}, {BenchmarkResults.STEADY, "p99.9"},
                {BenchmarkResults.BURST, "p50"}, {BenchmarkResults.BURST, "p90"}, {BenchmarkResults.BURST, "p95"},
                {BenchmarkResults.BURST, "p99"}, {BenchmarkResults.BURST, "p99.9"},
            };
            for (int p : r.producers()) {
                for (String[] m : metrics) {
                    Optional<BenchmarkResults.Stat> b = r.get(p, BenchmarkResults.BASELINE, m[0], m[1]);
                    Optional<BenchmarkResults.Stat> l = r.get(p, BenchmarkResults.LOCK_FREE, m[0], m[1]);
                    boolean throughput = m[0].equals(BenchmarkResults.THROUGHPUT);
                    String better = "—";
                    String ratio = "—";
                    if (b.isPresent() && l.isPresent() && b.get().median() != l.get().median()) {
                        double bv = b.get().median();
                        double lv = l.get().median();
                        boolean lf = throughput ? lv > bv : lv < bv;
                        better = lf ? "Lock-free" : "Lock-based";
                        ratio = String.format(Locale.ROOT, "%.2f×", Math.max(bv, lv) / Math.min(bv, lv));
                    } else if (b.isPresent() && l.isPresent()) {
                        better = "equal";
                    }
                    rows.add(new Object[] {p,
                        m[0].equals(BenchmarkResults.THROUGHPUT) ? "Throughput"
                                : m[0].equals(BenchmarkResults.STEADY) ? "Steady latency" : "Burst latency",
                        throughput ? "M orders/s" : m[1] + " (µs)",
                        format(b, throughput), format(l, throughput), better, ratio,
                        stable(b) + " / " + stable(l),
                        b.or(() -> l).map(s -> s.source().label()).orElse("not measured")});
                }
            }
            fireTableDataChanged();
        }

        private static String format(Optional<BenchmarkResults.Stat> stat, boolean throughput) {
            return stat.map(s -> throughput ? Ui.millionsPerSecond(s.median()) : Ui.micros(s.median()))
                    .orElse("n/a");
        }

        private static String stable(Optional<BenchmarkResults.Stat> stat) {
            return stat.map(s -> s.stable() == null ? "–" : s.stable() ? "yes" : "no").orElse("n/a");
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
            return rows.get(row)[column];
        }
    }
}
