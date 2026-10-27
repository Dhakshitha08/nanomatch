package com.nanomatch.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;

/** Colours and fonts of the dark trading-terminal look, and the Swing look-and-feel set-up. */
final class Theme {

    static final Color BG = new Color(0x0D1117);
    static final Color PANEL = new Color(0x151B23);
    static final Color PANEL_2 = new Color(0x1B2330);
    static final Color PANEL_3 = new Color(0x232D3D);
    static final Color BORDER = new Color(0x2B3546);
    static final Color TEXT = new Color(0xE6EDF3);
    static final Color MUTED = new Color(0x8B98A9);
    static final Color FAINT = new Color(0x5C6878);
    static final Color ACCENT = new Color(0x4C9EFF);
    static final Color BUY = new Color(0x2EBD85);
    static final Color BUY_DIM = new Color(0x123B2C);
    static final Color SELL = new Color(0xF6465D);
    static final Color SELL_DIM = new Color(0x45161F);
    static final Color WARN = new Color(0xF0B90B);
    static final Color OK = new Color(0x2EBD85);
    static final Color ERROR = new Color(0xF6465D);
    static final Color BASELINE = new Color(0x5B8DEF);
    static final Color LOCK_FREE = new Color(0xC084FC);
    static final Color SELECTION = new Color(0x24406B);

    static final String UI_FAMILY = pick("Segoe UI", "Inter", "Helvetica Neue", "Arial", "DejaVu Sans", "SansSerif");
    static final String MONO_FAMILY = pick("Cascadia Mono", "Consolas", "JetBrains Mono", "Menlo", "DejaVu Sans Mono",
            "Monospaced");

    private Theme() {
    }

    private static String pick(String... families) {
        Set<String> available;
        try {
            available = new HashSet<>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        } catch (Throwable e) {
            return families[families.length - 1];
        }
        for (String family : families) {
            if (available.contains(family)) {
                return family;
            }
        }
        return families[families.length - 1];
    }

    static Font ui(int style, float size) {
        return new Font(UI_FAMILY, style, Math.round(size));
    }

    static Font mono(int style, float size) {
        return new Font(MONO_FAMILY, style, Math.round(size));
    }

    static Color engineColor(com.nanomatch.demo.EngineKind kind) {
        return kind == com.nanomatch.demo.EngineKind.LOCK_FREE ? LOCK_FREE : BASELINE;
    }

    static Color sideColor(com.nanomatch.model.Side side) {
        return side == com.nanomatch.model.Side.BUY ? BUY : SELL;
    }

    static Color blend(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** Installs Nimbus with a dark palette, so standard controls match the custom-painted parts. */
    static void install() {
        try {
            UIManager.put("control", PANEL);
            UIManager.put("info", PANEL_2);
            UIManager.put("nimbusBase", new Color(0x1E2836));
            UIManager.put("nimbusAlertYellow", WARN);
            UIManager.put("nimbusDisabledText", FAINT);
            UIManager.put("nimbusFocus", ACCENT);
            UIManager.put("nimbusGreen", BUY);
            UIManager.put("nimbusInfoBlue", ACCENT);
            UIManager.put("nimbusLightBackground", PANEL_2);
            UIManager.put("nimbusOrange", WARN);
            UIManager.put("nimbusRed", SELL);
            UIManager.put("nimbusSelectedText", Color.WHITE);
            UIManager.put("nimbusSelectionBackground", SELECTION);
            UIManager.put("nimbusBorder", BORDER);
            UIManager.put("text", TEXT);
            UIManager.put("textForeground", TEXT);
            UIManager.put("textBackground", PANEL_2);
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
            // the default look and feel still works
        }
        FontUIResource base = new FontUIResource(ui(Font.PLAIN, 13));
        UIManager.put("defaultFont", base);
        for (String key : new String[] {"Label.font", "Button.font", "ToggleButton.font", "ComboBox.font",
                "Table.font", "TableHeader.font", "TextField.font", "FormattedTextField.font", "Spinner.font",
                "List.font", "TextArea.font", "TextPane.font", "EditorPane.font", "CheckBox.font", "RadioButton.font",
                "ToolTip.font", "OptionPane.messageFont", "OptionPane.buttonFont", "TitledBorder.font"}) {
            UIManager.put(key, base);
        }
        UIManager.put("Table.background", new ColorUIResource(PANEL));
        UIManager.put("Table.alternateRowColor", new ColorUIResource(PANEL_2));
        UIManager.put("Table.gridColor", new ColorUIResource(BORDER));
        UIManager.put("Table.foreground", new ColorUIResource(TEXT));
        UIManager.put("ToolTip.background", new ColorUIResource(PANEL_3));
        UIManager.put("ToolTip.foreground", new ColorUIResource(TEXT));
        UIManager.put("ScrollPane.background", new ColorUIResource(PANEL));
        UIManager.put("Viewport.background", new ColorUIResource(PANEL));
        UIManager.put("OptionPane.background", new ColorUIResource(PANEL));
        UIManager.put("Panel.background", new ColorUIResource(PANEL));
        UIManager.put("OptionPane.messageForeground", new ColorUIResource(TEXT));
    }
}
