package com.nanomatch.gui;

import com.nanomatch.demo.EngineKind;
import com.nanomatch.demo.SessionSnapshot;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * NanoMatch demo application: a Swing front end for the real lock-based and lock-free matching engines.
 *
 * <pre>
 * java -cp target/benchmarks.jar com.nanomatch.gui.NanoMatchApp [--engine lockfree|baseline]
 * </pre>
 */
public final class NanoMatchApp {

    private static final String[] PAGES = {"Dashboard", "Order Entry", "Order Book", "Trade History",
        "Engine Comparison", "Performance", "Demo Scenarios", "How It Works"};

    private final JFrame frame = new JFrame("NanoMatch · Order-Matching Engine Demo");
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final List<NavItem> navItems = new ArrayList<>();
    private final SessionController controller;
    private final Ui.StatusDot engineStatus = new Ui.StatusDot("", Theme.OK);
    private final JLabel counters = Ui.label(" ", Theme.mono(Font.PLAIN, 12.5f), Theme.MUTED);
    private final JLabel busy = Ui.label(" ", Theme.ui(Font.ITALIC, 12), Theme.ACCENT);
    private final List<JComponent> pages = new ArrayList<>();

    private NanoMatchApp(EngineKind initial) {
        controller = new SessionController(initial);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.BG);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        root.add(topBar(), BorderLayout.NORTH);
        root.add(navigation(), BorderLayout.WEST);

        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        pages.add(new DashboardPanel(controller));
        pages.add(new OrderEntryPanel(controller));
        pages.add(new OrderBookPanel(controller));
        pages.add(new TradeHistoryPanel(controller));
        pages.add(new ComparisonPanel(controller));
        pages.add(new PerformancePanel());
        pages.add(new ScenarioPanel(controller));
        pages.add(new HowItWorksPanel());
        for (int i = 0; i < PAGES.length; i++) {
            content.add(pages.get(i), PAGES[i]);
        }
        root.add(content, BorderLayout.CENTER);

        for (int i = 0; i < PAGES.length; i++) {
            final int index = i;
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i,
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "page" + i);
            root.getActionMap().put("page" + i, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    showPage(index);
                }
            });
        }

        frame.setContentPane(root);
        java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds(); // screen minus the taskbar, in scaled (logical) pixels
        frame.setMinimumSize(new Dimension(Math.min(1100, screen.width), Math.min(680, screen.height)));
        frame.setSize(Math.min(1440, screen.width), Math.min(900, screen.height));
        if (screen.width < 1300 || screen.height < 800) {
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        frame.setLocationRelativeTo(null);

        controller.onSnapshot(this::updateTop);
        controller.onBusy(b -> busy.setText(b ? "engine working…" : " "));
        showPage(0);
    }

    private JComponent topBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.PANEL);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.BORDER);
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            }
        };
        bar.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        JLabel logo = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Ui.antialias(g2);
                g2.setColor(Theme.ACCENT);
                g2.fillRoundRect(0, 4, 28, 28, 8, 8);
                g2.setColor(Theme.BUY);
                g2.fillRect(6, 20, 4, 8);
                g2.setColor(Theme.SELL);
                g2.fillRect(12, 12, 4, 16);
                g2.setColor(Color.WHITE);
                g2.fillRect(18, 9, 4, 19);
                g2.setFont(Theme.ui(Font.BOLD, 19));
                g2.setColor(Theme.TEXT);
                g2.drawString("NanoMatch", 40, 25);
                int x = 40 + g2.getFontMetrics().stringWidth("NanoMatch") + 14;
                g2.setFont(Theme.ui(Font.PLAIN, 12.5f));
                g2.setColor(Theme.MUTED);
                g2.drawString("lock-free vs lock-based order matching", x, 25);
                g2.dispose();
            }
        };
        logo.setPreferredSize(new Dimension(420, 36));
        bar.add(logo, BorderLayout.WEST);
        JPanel right = Ui.row(18, busy, counters, engineStatus);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JComponent navigation() {
        JPanel nav = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.PANEL);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.BORDER);
                g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
            }
        };
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setBorder(BorderFactory.createEmptyBorder(14, 10, 14, 10));
        nav.setPreferredSize(new Dimension(212, 400));
        String[] groups = {"LIVE ENGINE", null, null, null, "ANALYSIS", null, "PRESENT", null};
        for (int i = 0; i < PAGES.length; i++) {
            if (groups[i] != null) {
                JLabel group = Ui.label(groups[i], Theme.ui(Font.BOLD, 11), Theme.FAINT);
                group.setBorder(BorderFactory.createEmptyBorder(i == 0 ? 0 : 14, 12, 6, 0));
                group.setAlignmentX(Component.LEFT_ALIGNMENT);
                nav.add(group);
            }
            NavItem item = new NavItem(i, PAGES[i]);
            navItems.add(item);
            nav.add(item);
        }
        nav.add(Box.createVerticalGlue());
        JLabel hint = Ui.label("<html>Ctrl+1 … Ctrl+8<br>switch pages</html>", Theme.ui(Font.PLAIN, 11.5f),
                Theme.FAINT);
        hint.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        nav.add(hint);
        return nav;
    }

    private void showPage(int index) {
        cards.show(content, PAGES[index]);
        for (NavItem item : navItems) {
            item.setSelected(item.index == index);
        }
    }

    private void updateTop(SessionSnapshot s) {
        Color color = s.status() == SessionSnapshot.Status.RUNNING ? Theme.OK
                : s.status() == SessionSnapshot.Status.FAILED ? Theme.ERROR : Theme.MUTED;
        engineStatus.set(s.engine().displayName() + "  ·  " + s.status(), color);
        engineStatus.setForeground(Theme.engineColor(s.engine()));
        counters.setText(String.format(Locale.ROOT, "orders %s   trades %s   bid %s / ask %s",
                Ui.integer(s.submitted()), Ui.integer(s.trades().size()), Ui.price(s.bestBid()),
                Ui.price(s.bestAsk())));
    }

    private void shutdown() {
        frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            controller.close(); // waits for the engine to finish and stops its matching thread
        } finally {
            frame.dispose();
            System.exit(0);
        }
    }

    /** One entry of the left navigation. */
    private final class NavItem extends JComponent {
        private final int index;
        private final String text;
        private boolean selected;
        private boolean hover;

        NavItem(int index, String text) {
            this.index = index;
            this.text = text;
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            setPreferredSize(new Dimension(190, 38));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Ctrl+" + (index + 1));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    showPage(NavItem.this.index);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        void setSelected(boolean value) {
            selected = value;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Ui.antialias(g2);
            if (selected || hover) {
                g2.setColor(selected ? Theme.PANEL_3 : Theme.PANEL_2);
                g2.fillRoundRect(0, 2, getWidth() - 2, getHeight() - 4, 10, 10);
            }
            if (selected) {
                g2.setColor(Theme.ACCENT);
                g2.fillRoundRect(0, 9, 4, getHeight() - 18, 4, 4);
            }
            g2.setFont(Theme.ui(selected ? Font.BOLD : Font.PLAIN, 14));
            g2.setColor(selected ? Theme.TEXT : Theme.MUTED);
            g2.drawString(text, 16, getHeight() / 2 + 5);
            g2.setFont(Theme.mono(Font.PLAIN, 11));
            g2.setColor(Theme.FAINT);
            String key = Integer.toString(index + 1);
            g2.drawString(key, getWidth() - 20, getHeight() / 2 + 4);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------------------------------- start

    public static void main(String[] args) {
        EngineKind engine = EngineKind.LOCK_FREE;
        String screenshots = null;
        for (int i = 0; i < args.length; i++) {
            if ("--engine".equals(args[i]) && i + 1 < args.length) {
                engine = EngineKind.fromCliName(args[++i]);
            } else if ("--screenshots".equals(args[i]) && i + 1 < args.length) {
                screenshots = args[++i];
            }
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        final EngineKind initial = engine;
        final String shotDir = screenshots;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> error.printStackTrace());
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            NanoMatchApp app;
            try {
                app = new NanoMatchApp(initial);
            } catch (RuntimeException e) {
                JOptionPane.showMessageDialog(null, "NanoMatch could not start: " + e, "NanoMatch",
                        JOptionPane.ERROR_MESSAGE);
                throw e;
            }
            app.frame.setVisible(true);
            if (shotDir != null) {
                app.captureAll(new File(shotDir));
            }
        });
    }

    /**
     * Saves a PNG of every page (used to check the layout and to put pictures of the GUI into the report),
     * after filling the live engine with a little real activity. Then closes the application.
     */
    private void captureAll(File dir) {
        dir.mkdirs();
        controller.submitRandom(40, results -> {
            Timer settle = new Timer(1500, null);
            settle.setRepeats(false);
            final int[] page = {0};
            settle.addActionListener(e -> {
                if (page[0] > 0) {
                    save(new File(dir, String.format(Locale.ROOT, "%d-%s.png", page[0],
                            PAGES[page[0] - 1].toLowerCase(Locale.ROOT).replace(' ', '-'))));
                }
                if (page[0] < PAGES.length) {
                    showPage(page[0]);
                    page[0]++;
                    settle.setInitialDelay(1200);
                    settle.restart();
                } else {
                    shutdown();
                }
            });
            settle.start();
        });
    }

    private void save(File file) {
        JComponent root = (JComponent) frame.getContentPane();
        BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        root.paintAll(g);
        g.dispose();
        try {
            ImageIO.write(image, "png", file);
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }
}
