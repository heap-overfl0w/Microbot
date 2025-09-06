package net.runelite.client.plugins.microbot.scriptbuilder.variables;

public class ScriptVarBlocks {
    private static final String KEY = "scriptbuilder";

    public static boolean setString(String name, String value) {
        if (name == null) return false;
        ScriptVars.set(KEY + "." + name, value == null ? "" : value);
        return true;
    }

    public static boolean setInt(String name, int value) {
        if (name == null) return false;
        ScriptVars.set(KEY + "." + name, value);
        return true;
    }

    public static boolean setDouble(String name, double value) {
        if (name == null) return false;
        ScriptVars.set(KEY + "." + name, value);
        return true;
    }

    public static boolean equals(String name, String expected) {
        Object v = ScriptVars.get(KEY + "." + name);
        String s = v == null ? null : String.valueOf(v);
        String e = expected;
        if (s == null && e == null) return true;
        if (s == null) return false;
        if (e == null) return false;
        return s.equalsIgnoreCase(e);
    }

    public static boolean lessThan(String name, int threshold) {
        Object v = ScriptVars.get(KEY + "." + name);
        try {
            if (v instanceof Number) return ((Number) v).doubleValue() < threshold;
            if (v == null) return 0 < threshold;
            return Double.parseDouble(String.valueOf(v)) < threshold;
        } catch (Exception ex) {
            return false;
        }
    }

    public static boolean greaterOrEqual(String name, int threshold) {
        Object v = ScriptVars.get(KEY + "." + name);
        try {
            if (v instanceof Number) return ((Number) v).doubleValue() >= threshold;
            if (v == null) return 0 >= threshold;
            return Double.parseDouble(String.valueOf(v)) >= threshold;
        } catch (Exception ex) {
            return false;
        }
    }
}

