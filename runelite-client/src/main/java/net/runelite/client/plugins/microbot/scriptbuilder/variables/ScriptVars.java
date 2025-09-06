package net.runelite.client.plugins.microbot.scriptbuilder.variables;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScriptVars {
    private static final Map<String, ScriptVarRegistry> REGISTRIES = new ConcurrentHashMap<>();

    private static ScriptVarRegistry reg(String scriptKey) {
        return REGISTRIES.computeIfAbsent(scriptKey, ScriptVarRegistry::forKey);
    }

    public static void register(String scriptKey, ScriptVarDef def) {
        reg(scriptKey).register(def);
    }

    public static Object get(String fullKey) {
        Key k = Key.of(fullKey);
        return reg(k.script).get(k.name);
    }

    public static boolean getBool(String fullKey) {
        Key k = Key.of(fullKey);
        return reg(k.script).getBoolean(k.name);
    }

    public static Integer getInt(String fullKey) {
        Key k = Key.of(fullKey);
        return reg(k.script).getInteger(k.name);
    }

    public static Double getDouble(String fullKey) {
        Key k = Key.of(fullKey);
        return reg(k.script).getDouble(k.name);
    }

    public static String getString(String fullKey) {
        Key k = Key.of(fullKey);
        return reg(k.script).getString(k.name);
    }

    public static void set(String fullKey, Object value) {
        Key k = Key.of(fullKey);
        reg(k.script).set(k.name, value);
    }

    public static void onChange(String fullKey, ScriptVarListener listener) {
        Key k = Key.of(fullKey);
        reg(k.script).onChange(k.name, listener);
    }

    public static void remove(String fullKey) {
        Key k = Key.of(fullKey);
        reg(k.script).remove(k.name);
    }

    public static void clear(String scriptKey) {
        reg(scriptKey).clear();
    }

    private static final class Key {
        final String script;
        final String name;

        private Key(String script, String name) {
            this.script = script;
            this.name = name;
        }

        static Key of(String fullKey) {
            int i = fullKey.indexOf('.');
            if (i <= 0 || i >= fullKey.length() - 1) throw new IllegalArgumentException("full key must be script.var");
            return new Key(fullKey.substring(0, i), fullKey.substring(i + 1));
        }
    }
}
