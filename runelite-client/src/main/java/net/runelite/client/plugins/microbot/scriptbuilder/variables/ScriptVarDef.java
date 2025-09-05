package net.runelite.client.plugins.microbot.scriptbuilder.variables;

import java.util.List;
import java.util.Objects;

public final class ScriptVarDef {
    private final String name;
    private final ScriptVarType type;
    private final Object defaultValue;
    private final List<String> enumOptions;
    private final String label;
    private final String description;

    private ScriptVarDef(String name, ScriptVarType type, Object defaultValue, List<String> enumOptions, String label, String description) {
        this.name = Objects.requireNonNull(name);
        this.type = Objects.requireNonNull(type);
        this.defaultValue = defaultValue;
        this.enumOptions = enumOptions;
        this.label = label;
        this.description = description;
    }

    public static ScriptVarDef bool(String name, boolean defaultValue, String label) {
        return new ScriptVarDef(name, ScriptVarType.BOOLEAN, defaultValue, null, label, null);
    }

    public static ScriptVarDef integer(String name, int defaultValue, String label) {
        return new ScriptVarDef(name, ScriptVarType.INTEGER, defaultValue, null, label, null);
    }

    public static ScriptVarDef dbl(String name, double defaultValue, String label) {
        return new ScriptVarDef(name, ScriptVarType.DOUBLE, defaultValue, null, label, null);
    }

    public static ScriptVarDef string(String name, String defaultValue, String label) {
        return new ScriptVarDef(name, ScriptVarType.STRING, defaultValue, null, label, null);
    }

    public static ScriptVarDef enm(String name, List<String> options, String defaultValue, String label) {
        return new ScriptVarDef(name, ScriptVarType.ENUM, defaultValue, options, label, null);
    }

    public String getName() {
        return name;
    }

    public ScriptVarType getType() {
        return type;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public List<String> getEnumOptions() {
        return enumOptions;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
