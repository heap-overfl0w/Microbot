package net.runelite.client.plugins.microbot.scriptbuilder.variables;

@FunctionalInterface
public interface ScriptVarListener {
    void onChange(String fullKey, Object newValue);
}
