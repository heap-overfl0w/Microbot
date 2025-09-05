package net.runelite.client.plugins.microbot.scriptbuilder.variables;

import com.google.gson.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ScriptVarLoader {
    private ScriptVarLoader() {}

    public static int loadFromClasspath(String resourcePath) {
        InputStream in = ScriptVarLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) return 0;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            JsonArray vars = root.getAsJsonArray("variables");
            if (vars == null) return 0;
            int count = 0;
            for (JsonElement el : vars) {
                JsonObject v = el.getAsJsonObject();
                String key = v.get("key").getAsString();
                String type = v.get("type").getAsString();
                String label = v.has("label") ? v.get("label").getAsString() : key;
                String script = key.substring(0, key.indexOf('.'));
                String name = key.substring(key.indexOf('.') + 1);
                ScriptVarType t = ScriptVarType.valueOf(type);
                switch (t) {
                    case BOOLEAN: {
                        boolean def = v.has("default") && v.get("default").getAsBoolean();
                        ScriptVars.register(script, ScriptVarDef.bool(name, def, label));
                        count++;
                        break;
                    }
                    case INTEGER: {
                        int def = v.has("default") ? v.get("default").getAsInt() : 0;
                        ScriptVars.register(script, ScriptVarDef.integer(name, def, label));
                        count++;
                        break;
                    }
                    case DOUBLE: {
                        double def = v.has("default") ? v.get("default").getAsDouble() : 0d;
                        ScriptVars.register(script, ScriptVarDef.dbl(name, def, label));
                        count++;
                        break;
                    }
                    case STRING: {
                        String def = v.has("default") ? v.get("default").getAsString() : "";
                        ScriptVars.register(script, ScriptVarDef.string(name, def, label));
                        count++;
                        break;
                    }
                    case ENUM: {
                        List<String> options = new ArrayList<>();
                        JsonArray arr = v.getAsJsonArray("options");
                        if (arr != null) for (JsonElement o : arr) options.add(o.getAsString());
                        String def = v.has("default") ? v.get("default").getAsString() : (options.isEmpty() ? "" : options.get(0));
                        ScriptVars.register(script, ScriptVarDef.enm(name, options, def, label));
                        count++;
                        break;
                    }
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
}
