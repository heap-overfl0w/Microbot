package net.runelite.client.plugins.microbot.scriptbuilder.condition.uservar.ui;

import net.runelite.client.plugins.microbot.scriptbuilder.condition.uservar.UserVariableCondition;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarDef;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarRegistry;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarType;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVars;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class UserVariableConditionPanelUtil {
    private UserVariableConditionPanelUtil() {}

    public static void createUserVarPanel(JPanel panel, GridBagConstraints gbc) {
        JTextField nameField = new JTextField("User Variable");
        JTextField keyField = new JTextField();

        // Build available variables list from registries
        List<String> keys = new ArrayList<>();
        try {
            for (Map.Entry<String, ScriptVarRegistry> e : ScriptVarRegistry.registries().entrySet()) {
                String script = e.getKey();
                for (ScriptVarDef d : e.getValue().definitions()) {
                    keys.add(script + "." + d.getName());
                }
            }
        } catch (Exception ignored) {}

        JComboBox<String> keyCombo = new JComboBox<>(keys.toArray(new String[0]));
        keyCombo.setEditable(true);
        keyCombo.addActionListener(ev -> {
            Object sel = keyCombo.getSelectedItem();
            if (sel != null) keyField.setText(sel.toString());
        });

        JComboBox<ScriptVarType> typeCombo = new JComboBox<>(ScriptVarType.values());
        JComboBox<UserVariableCondition.Operator> opCombo = new JComboBox<>(UserVariableCondition.Operator.values());

        JTextField valueField = new JTextField();
        JComboBox<String> enumValueCombo = new JComboBox<>();

        // Try to sync type/options based on selected key
        Runnable syncFromKey = () -> {
            String fk = keyField.getText();
            int i = fk.indexOf('.');
            if (i > 0) {
                ScriptVarRegistry reg = ScriptVarRegistry.forKey(fk.substring(0, i));
                String var = fk.substring(i+1);
                for (ScriptVarDef d : reg.definitions()) {
                    if (d.getName().equals(var)) {
                        typeCombo.setSelectedItem(d.getType());
                        if (d.getType() == ScriptVarType.ENUM && d.getEnumOptions() != null) {
                            enumValueCombo.removeAllItems();
                            for (String opt : d.getEnumOptions()) enumValueCombo.addItem(opt);
                            enumValueCombo.setSelectedItem(String.valueOf(d.getDefaultValue()));
                        }
                        break;
                    }
                }
            }
        };

        keyField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { syncFromKey.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { syncFromKey.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { syncFromKey.run(); }
        });

        // Layout
        addLabeled(panel, gbc, "Name:", nameField);
        addLabeled(panel, gbc, "Variable Key:", keyCombo);
        addLabeled(panel, gbc, "Key Override:", keyField);
        addLabeled(panel, gbc, "Type:", typeCombo);
        addLabeled(panel, gbc, "Operator:", opCombo);
        addLabeled(panel, gbc, "Value:", valueField);

        // Store components for create method
        panel.putClientProperty("uv_name", nameField);
        panel.putClientProperty("uv_key", keyField);
        panel.putClientProperty("uv_type", typeCombo);
        panel.putClientProperty("uv_op", opCombo);
        panel.putClientProperty("uv_val", valueField);
        panel.putClientProperty("uv_enum_val", enumValueCombo);
    }

    private static void addLabeled(JPanel panel, GridBagConstraints gbc, String label, JComponent comp) {
        JLabel l = new JLabel(label);
        l.setForeground(Color.WHITE);
        l.setFont(FontManager.getRunescapeSmallFont());
        gbc.gridx = 0; gbc.weightx = 0; panel.add(l, gbc);
        gbc.gridx = 1; gbc.weightx = 1; panel.add(comp, gbc);
        gbc.gridy++;
    }

    public static UserVariableCondition createCondition(JPanel configPanel) {
        JPanel local = (JPanel) configPanel.getClientProperty("localConditionPanel");
        if (local == null) return null;

        JTextField nameField = (JTextField) local.getClientProperty("uv_name");
        JTextField keyField = (JTextField) local.getClientProperty("uv_key");
        @SuppressWarnings("unchecked")
        JComboBox<ScriptVarType> typeCombo = (JComboBox<ScriptVarType>) local.getClientProperty("uv_type");
        @SuppressWarnings("unchecked")
        JComboBox<UserVariableCondition.Operator> opCombo = (JComboBox<UserVariableCondition.Operator>) local.getClientProperty("uv_op");
        JTextField valueField = (JTextField) local.getClientProperty("uv_val");

        if (nameField == null || keyField == null || typeCombo == null || opCombo == null) return null;
        String name = nameField.getText();
        String key = keyField.getText();
        ScriptVarType type = (ScriptVarType) typeCombo.getSelectedItem();
        UserVariableCondition.Operator op = (UserVariableCondition.Operator) opCombo.getSelectedItem();
        String val = valueField != null ? valueField.getText() : "";
        if (type == ScriptVarType.BOOLEAN) {
            if (!"true".equalsIgnoreCase(val) && !"false".equalsIgnoreCase(val)) {
                // fallback to current value if available
                Object cur = ScriptVars.get(key);
                val = String.valueOf(cur != null ? cur : false);
            }
        }
        return new UserVariableCondition(name, key, type, op, val);
    }
}
