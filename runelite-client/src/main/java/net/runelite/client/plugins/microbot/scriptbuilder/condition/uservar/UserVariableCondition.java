package net.runelite.client.plugins.microbot.scriptbuilder.condition.uservar;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.runelite.client.plugins.microbot.scriptbuilder.condition.Condition;
import net.runelite.client.plugins.microbot.scriptbuilder.condition.ConditionType;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarDef;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarRegistry;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarType;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVars;

@EqualsAndHashCode(callSuper = false)
public class UserVariableCondition implements Condition {
    public enum Operator {
        EQUALS("=="),
        NOT_EQUALS("!="),
        GREATER_THAN(">"),
        GREATER_OR_EQUALS(">="),
        LESS_THAN("<"),
        LESS_OR_EQUALS("<=");

        private final String label;
        Operator(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    @Getter private final String name;
    @Getter private final String fullKey;
    @Getter private final ScriptVarType varType;
    @Getter private final Operator operator;
    @Getter private final String expected; // stored as string; coerced per type

    public UserVariableCondition(String name, String fullKey, ScriptVarType varType, Operator operator, String expected) {
        this.name = name;
        this.fullKey = fullKey;
        this.varType = varType;
        this.operator = operator;
        this.expected = expected;
    }

    @Override
    public boolean isSatisfied() {
        Object cur = ScriptVars.get(fullKey);
        if (cur == null) return false;
        switch (varType) {
            case BOOLEAN:
                boolean b = cur instanceof Boolean ? (Boolean) cur : Boolean.parseBoolean(String.valueOf(cur));
                boolean be = Boolean.parseBoolean(expected);
                return compare(b ? 1 : 0, be ? 1 : 0);
            case INTEGER:
                Integer iv = toInt(cur);
                Integer ie = parseInt(expected);
                if (iv == null || ie == null) return false;
                return compare(iv, ie);
            case DOUBLE:
                Double dv = toDouble(cur);
                Double de = parseDouble(expected);
                if (dv == null || de == null) return false;
                return compare(dv, de);
            case STRING:
            case ENUM:
                String sv = String.valueOf(cur);
                String se = expected;
                return operator == Operator.EQUALS ? sv.equals(se) : operator == Operator.NOT_EQUALS && !sv.equals(se);
            default:
                return false;
        }
    }

    private boolean compare(int a, int b) {
        switch (operator) {
            case EQUALS: return a == b;
            case NOT_EQUALS: return a != b;
            case GREATER_THAN: return a > b;
            case GREATER_OR_EQUALS: return a >= b;
            case LESS_THAN: return a < b;
            case LESS_OR_EQUALS: return a <= b;
            default: return false;
        }
    }

    private boolean compare(double a, double b) {
        switch (operator) {
            case EQUALS: return Double.compare(a, b) == 0;
            case NOT_EQUALS: return Double.compare(a, b) != 0;
            case GREATER_THAN: return a > b;
            case GREATER_OR_EQUALS: return a >= b;
            case LESS_THAN: return a < b;
            case LESS_OR_EQUALS: return a <= b;
            default: return false;
        }
    }

    private Integer toInt(Object o) {
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return null; }
    }
    private Integer parseInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return null; } }
    private Double toDouble(Object o) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; }
    }
    private Double parseDouble(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return null; } }

    @Override
    public String getDescription() {
        return name + " (" + fullKey + ")";
    }

    @Override
    public String getDetailedDescription() {
        String defDesc = null;
        try {
            int i = fullKey.indexOf('.');
            if (i > 0) {
                String script = fullKey.substring(0, i);
                String var = fullKey.substring(i+1);
                ScriptVarRegistry reg = ScriptVarRegistry.forKey(script);
                for (ScriptVarDef d : reg.definitions()) {
                    if (d.getName().equals(var)) { defDesc = d.getLabel(); break; }
                }
            }
        } catch (Exception ignored) {}
        Object cur = ScriptVars.get(fullKey);
        return (defDesc != null ? defDesc + "\n" : "") +
                "Type: " + varType + "\n" +
                "Operator: " + operator + "\n" +
                "Expected: " + expected + "\n" +
                "Current: " + cur;
    }

    @Override
    public ConditionType getType() {
        return ConditionType.USER_VARIABLE;
    }

    @Override
    public void reset(boolean randomize) {
        // no-op
    }

    @Override
    public void pause() { }

    @Override
    public void resume() { }
}
