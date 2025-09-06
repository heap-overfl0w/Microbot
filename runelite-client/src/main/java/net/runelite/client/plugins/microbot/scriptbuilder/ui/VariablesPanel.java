package net.runelite.client.plugins.microbot.scriptbuilder.ui;

import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarDef;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarRegistry;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarType;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVars;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Map;

public class VariablesPanel extends JPanel {
    private JPanel content;

    public VariablesPanel() {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(new EmptyBorder(8,8,8,8));

        JLabel title = new JLabel("User Variables");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(Color.WHITE);
        add(title, BorderLayout.NORTH);

        content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        add(scroll, BorderLayout.CENTER);

        // No bottom actions; removal is via right-click on rows

        rebuildContent();
    }

    private void rebuildContent() {
        content.removeAll();
        for (Map.Entry<String, ScriptVarRegistry> e : ScriptVarRegistry.registries().entrySet()) {
            String script = e.getKey();
            JPanel section = new JPanel();
            section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
            section.setBackground(ColorScheme.DARK_GRAY_COLOR);
            section.setBorder(BorderFactory.createTitledBorder(script));

            for (ScriptVarDef def : e.getValue().definitions()) {
                VariableRow row = new VariableRow(script, def);
                section.add(row);
            }

            content.add(section);
            content.add(Box.createVerticalStrut(8));
        }
        content.revalidate();
        content.repaint();
    }

    private JComponent createEditor(String fullKey, ScriptVarDef def) {
        switch (def.getType()) {
            case BOOLEAN: {
                boolean cur = Boolean.parseBoolean(String.valueOf(ScriptVars.get(fullKey)));
                JCheckBox cb = new JCheckBox();
                cb.setSelected(cur);
                cb.addActionListener(e -> ScriptVars.set(fullKey, cb.isSelected()));
                cb.setBackground(ColorScheme.DARK_GRAY_COLOR);
                return cb;
            }
            case INTEGER: {
                int cur = 0;
                try { Object v = ScriptVars.get(fullKey); cur = v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
                JSpinner sp = new JSpinner(new SpinnerNumberModel(cur, Integer.MIN_VALUE, Integer.MAX_VALUE, 1));
                sp.addChangeListener(e -> ScriptVars.set(fullKey, ((Number) sp.getValue()).intValue()));
                return sp;
            }
            case DOUBLE: {
                double cur = 0d;
                try { Object v = ScriptVars.get(fullKey); cur = v instanceof Number ? ((Number) v).doubleValue() : Double.parseDouble(String.valueOf(v)); } catch (Exception ignored) {}
                JSpinner sp = new JSpinner(new SpinnerNumberModel(cur, -1e9, 1e9, 0.1));
                sp.addChangeListener(e -> ScriptVars.set(fullKey, ((Number) sp.getValue()).doubleValue()));
                return sp;
            }
            case ENUM: {
                JComboBox<String> combo = new JComboBox<>(def.getEnumOptions().toArray(new String[0]));
                combo.setSelectedItem(String.valueOf(ScriptVars.get(fullKey)));
                combo.addActionListener(e -> ScriptVars.set(fullKey, combo.getSelectedItem()));
                return combo;
            }
            case STRING:
            default: {
                JTextField tf = new JTextField(String.valueOf(ScriptVars.get(fullKey)));
                tf.addActionListener(e -> ScriptVars.set(fullKey, tf.getText()));
                tf.addFocusListener(new java.awt.event.FocusAdapter() {
                    public void focusLost(java.awt.event.FocusEvent e) { ScriptVars.set(fullKey, tf.getText()); }
                });
                return tf;
            }
        }
    }

    

    private final class VariableRow extends JPanel {
        private final String script;
        private final ScriptVarDef def;
        private final String fullKey;

        private VariableRow(String script, ScriptVarDef def) {
            super(new BorderLayout());
            this.script = script;
            this.def = def;
            this.fullKey = script + "." + def.getName();

            setBackground(ColorScheme.DARK_GRAY_COLOR);
            JLabel label = new JLabel(def.getLabel() != null ? def.getLabel() : def.getName());
            label.setForeground(Color.WHITE);
            add(label, BorderLayout.WEST);

            JComponent editor = createEditor(fullKey, def);
            add(editor, BorderLayout.EAST);

            JPopupMenu menu = new JPopupMenu();
            JMenuItem remove = new JMenuItem("Remove");
            remove.addActionListener(e -> {
                int res = JOptionPane.showConfirmDialog(VariablesPanel.this, "Remove variable '" + def.getName() + "'?", "Confirm Remove", JOptionPane.OK_CANCEL_OPTION);
                if (res != JOptionPane.OK_OPTION) return;
                ScriptVarRegistry.forKey(script).remove(def.getName());
                rebuildContent();
            });
            menu.add(remove);

            java.awt.event.MouseAdapter popup = new java.awt.event.MouseAdapter() {
                private void maybe(java.awt.event.MouseEvent e) {
                    if (e.isPopupTrigger()) menu.show(VariableRow.this, e.getX(), e.getY());
                }
                @Override public void mousePressed(java.awt.event.MouseEvent e) { maybe(e); }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybe(e); }
            };
            addMouseListener(popup);
            for (Component c : getComponents()) c.addMouseListener(popup);
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        }
    }

    
}
