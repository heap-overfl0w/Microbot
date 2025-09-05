package net.runelite.client.plugins.microbot.scriptbuilder;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import javax.swing.*;

import javax.inject.Inject;


@PluginDescriptor(
        name = "Script Builder (Alpha)",
        description = "Visual builder to compose and run scripts",
        tags = {"script builder"},
        enabledByDefault = false
)
@Slf4j
public class ScriptBuilderPlugin extends Plugin {

    @Inject
    private ConfigManager configManager;

    @Inject
    private ScriptBuilderConfig config;

    private JFrame frame;
    private ScriptBuilderPanel panel;

    @Provides
    ScriptBuilderConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ScriptBuilderConfig.class);
    }

    @Override
    protected void startUp() {
        if (config.enabled()) {
            openWindow();
        }
    }

    @Override
    protected void shutDown() {
        closeWindow();
    }

    private void openWindow() {
        if (frame != null) return;
        SwingUtilities.invokeLater(() -> {
            panel = new ScriptBuilderPanel(configManager);
            frame = new JFrame("Script Builder (Alpha)");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    int res = javax.swing.JOptionPane.showConfirmDialog(frame, "Close Script Builder?", "Confirm Close", javax.swing.JOptionPane.YES_NO_OPTION);
                    if (res == javax.swing.JOptionPane.YES_OPTION) {
                        try { if (panel != null) ScriptBuilderPlugin.invokeSaveBackup(panel); } catch (Exception ignored) {}
                        JFrame f = frame;
                        if (f != null) f.dispose();
                    }
                }
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    try { if (panel != null) ScriptBuilderPlugin.invokeSaveBackup(panel); } catch (Exception ignored) {}
                    try { configManager.setConfiguration("scriptbuilder", "enabled", false); } catch (Exception ignored) {}
                    frame = null;
                    panel = null;
                }
            });
            frame.setContentPane(panel);
            frame.setSize(900, 600);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private void closeWindow() {
        if (frame != null) {
            JFrame f = frame;
            try { if (panel != null) ScriptBuilderPlugin.invokeSaveBackup(panel); } catch (Exception ignored) {}
            frame = null;
            panel = null;
            SwingUtilities.invokeLater(f::dispose);
        }
    }

    private static void invokeSaveBackup(ScriptBuilderPanel panel) {
        try {
            java.lang.reflect.Method m = ScriptBuilderPanel.class.getMethod("saveBackupSilent");
            m.invoke(panel);
        } catch (Throwable ignored) {}
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged e) {
        if (!"scriptbuilder".equals(e.getGroup())) return;
        if ("enabled".equals(e.getKey())) {
            if (config.enabled()) openWindow(); else closeWindow();
        }
    }
}
