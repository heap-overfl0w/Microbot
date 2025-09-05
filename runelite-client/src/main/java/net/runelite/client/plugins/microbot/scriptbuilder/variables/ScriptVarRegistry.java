package net.runelite.client.plugins.microbot.scriptbuilder.variables;

import net.runelite.client.plugins.microbot.Microbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class ScriptVarRegistry {
    private static final String GROUP = "scriptVars";
    private static final Map<String, ScriptVarRegistry> BY_KEY = new ConcurrentHashMap<>();

    public static ScriptVarRegistry forScript(Class<?> scriptClass) {
        String key = deriveKey(scriptClass.getSimpleName());
        return BY_KEY.computeIfAbsent(key, ScriptVarRegistry::new);
    }

    public static ScriptVarRegistry forKey(String scriptKey) {
        return BY_KEY.computeIfAbsent(scriptKey, ScriptVarRegistry::new);
    }

    public static void clearForKey(String scriptKey) {
        BY_KEY.remove(scriptKey);
    }

    public static Map<String, ScriptVarRegistry> registries() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(BY_KEY));
    }

    private static String deriveKey(String simpleName) {
        String s = simpleName;
        if (s.endsWith("Script")) s = s.substring(0, s.length() - 6);
        return s.toLowerCase();
    }

    private final String scriptKey;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private ScriptVarRegistry(String scriptKey) {
        this.scriptKey = Objects.requireNonNull(scriptKey);
    }

    public String getScriptKey() {
        return scriptKey;
    }

    public void register(ScriptVarDef def) {
        String fullKey = fullKey(def.getName());
        entries.computeIfAbsent(def.getName(), n -> new Entry(fullKey, def));
    }

    public Object get(String name) {
        Entry e = entries.get(name);
        return e != null ? e.value.get() : null;
    }

    public Object getOrDefault(String name) {
        Entry e = entries.get(name);
        return e != null ? e.value.get() : null;
    }

    public boolean getBoolean(String name) {
        Object v = get(name);
        return v instanceof Boolean ? (Boolean) v : false;
    }

    public Integer getInteger(String name) {
        Object v = get(name);
        return v instanceof Integer ? (Integer) v : null;
    }

    public Double getDouble(String name) {
        Object v = get(name);
        return v instanceof Double ? (Double) v : null;
    }

    public String getString(String name) {
        Object v = get(name);
        return v instanceof String ? (String) v : null;
    }

    public void set(String name, Object value) {
        Entry e = entries.get(name);
        if (e == null) return;
        Object coerced = coerce(value, e.def.getType(), e.def.getEnumOptions());
        e.value.set(coerced);
        persist(e.fullKey, e.def.getType(), coerced);
        e.fire(coerced);
    }

    public void onChange(String name, ScriptVarListener listener) {
        Entry e = entries.get(name);
        if (e == null) return;
        e.listeners.add(listener);
    }

    public List<ScriptVarDef> definitions() {
        List<ScriptVarDef> list = new ArrayList<>();
        for (Entry e : entries.values()) list.add(e.def);
        return Collections.unmodifiableList(list);
    }

    public void updateLabel(String name, String newLabel) {
        Entry e = entries.get(name);
        if (e == null) return;
        ScriptVarDef d = e.def;
        ScriptVarDef nd;
        switch (d.getType()) {
            case BOOLEAN:
                nd = ScriptVarDef.bool(d.getName(), (Boolean) d.getDefaultValue(), newLabel);
                break;
            case INTEGER:
                nd = ScriptVarDef.integer(d.getName(), (Integer) d.getDefaultValue(), newLabel);
                break;
            case DOUBLE:
                nd = ScriptVarDef.dbl(d.getName(), (Double) d.getDefaultValue(), newLabel);
                break;
            case STRING:
                nd = ScriptVarDef.string(d.getName(), (String) d.getDefaultValue(), newLabel);
                break;
            case ENUM:
                nd = ScriptVarDef.enm(d.getName(), d.getEnumOptions(), (String) d.getDefaultValue(), newLabel);
                break;
            default:
                return;
        }
        Entry ne = new Entry(e.fullKey, nd);
        ne.value.set(e.value.get());
        ne.listeners.addAll(e.listeners);
        entries.put(name, ne);
    }

    public void clear() {
        entries.clear();
    }

    private String fullKey(String name) {
        return scriptKey + "." + name;
    }

    private static Object coerce(Object v, ScriptVarType type, List<String> options) {
        if (v == null) return null;
        switch (type) {
            case BOOLEAN:
                if (v instanceof Boolean) return v;
                if (v instanceof String) return Boolean.parseBoolean((String) v);
                return false;
            case INTEGER:
                if (v instanceof Integer) return v;
                if (v instanceof Number) return ((Number) v).intValue();
                if (v instanceof String) try { return Integer.parseInt((String) v); } catch (Exception ignored) {}
                return 0;
            case DOUBLE:
                if (v instanceof Double) return v;
                if (v instanceof Number) return ((Number) v).doubleValue();
                if (v instanceof String) try { return Double.parseDouble((String) v); } catch (Exception ignored) {}
                return 0d;
            case STRING:
                return String.valueOf(v);
            case ENUM:
                String sv = String.valueOf(v);
                if (options != null && options.contains(sv)) return sv;
                return options != null && !options.isEmpty() ? options.get(0) : sv;
            default:
                return v;
        }
    }

    private static String serialize(ScriptVarType type, Object v) {
        if (v == null) return null;
        switch (type) {
            case BOOLEAN:
                return Boolean.toString((Boolean) v);
            case INTEGER:
                return Integer.toString((Integer) v);
            case DOUBLE:
                return Double.toString((Double) v);
            case STRING:
            case ENUM:
                return String.valueOf(v);
            default:
                return String.valueOf(v);
        }
    }

    private static Object deserialize(ScriptVarType type, String s, ScriptVarDef def) {
        if (s == null) return def.getDefaultValue();
        switch (type) {
            case BOOLEAN:
                return Boolean.parseBoolean(s);
            case INTEGER:
                try { return Integer.parseInt(s); } catch (Exception e) { return def.getDefaultValue(); }
            case DOUBLE:
                try { return Double.parseDouble(s); } catch (Exception e) { return def.getDefaultValue(); }
            case STRING:
                return s;
            case ENUM:
                if (def.getEnumOptions() != null && def.getEnumOptions().contains(s)) return s;
                return def.getDefaultValue();
            default:
                return s;
        }
    }

    private static void persist(String fullKey, ScriptVarType type, Object v) {
        String sv = serialize(type, v);
        Microbot.getConfigManager().setConfiguration(GROUP, fullKey, sv);
    }

    private final class Entry {
        private final String fullKey;
        private final ScriptVarDef def;
        private final AtomicReference<Object> value;
        private final CopyOnWriteArrayList<ScriptVarListener> listeners = new CopyOnWriteArrayList<>();

        private Entry(String fullKey, ScriptVarDef def) {
            this.fullKey = fullKey;
            this.def = def;
            String saved = Microbot.getConfigManager().getConfiguration(GROUP, fullKey);
            Object initial = deserialize(def.getType(), saved, def);
            this.value = new AtomicReference<>(initial);
        }

        private void fire(Object v) {
            for (ScriptVarListener l : listeners) l.onChange(fullKey, v);
        }
    }
}
