package net.runelite.client.plugins.microbot.scriptbuilder.registry;

import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockDefinition;
import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockParam;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.depositbox.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

public class BlockRegistry {
    private final Map<String, BlockDefinition> byId = new LinkedHashMap<>();

    public BlockRegistry() {
        registerFromClass(Rs2Bank.class, "Bank");
        registerFromClass(Rs2Walker.class, "Walking");
        registerFromClass(Rs2Inventory.class, "Inventory");
        registerFromClass(Rs2Equipment.class, "Equipment");
        registerFromClass(Rs2Widget.class, "Widgets");
        registerFromClass(Rs2Npc.class, "NPC");
        registerFromClass(Rs2GameObject.class, "Objects");
        registerFromClass(Rs2GroundItem.class, "Ground Items");
        registerFromClass(Rs2Dialogue.class, "Dialogue");
        registerFromClass(Rs2Camera.class, "Camera");
        registerFromClass(Rs2Keyboard.class, "Keyboard");
        registerFromClass(Rs2Tab.class, "Tabs");
        registerFromClass(Rs2Settings.class, "Settings");
        registerFromClass(Rs2Prayer.class, "Prayer");
        registerFromClass(Rs2Magic.class, "Magic");
        registerFromClass(Rs2Combat.class, "Combat");
        registerFromClass(Rs2DepositBox.class, "Deposit Box");
        registerFromClass(Rs2Shop.class, "Shop");
        registerFromClass(Rs2GrandExchange.class, "Grand Exchange");
        registerFromClass(Rs2Player.class, "Player");
        registerFromClass(net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarBlocks.class, "Vars");
        {
            java.util.List<BlockDefinition> copies = new java.util.ArrayList<>();
            for (BlockDefinition d : new java.util.ArrayList<>(byId.values())) {
                boolean aliasable = (d.getDeclaringClass() == net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVarBlocks.class)
                        && d.getMethod() != null;
                if (aliasable) {
                    String sig = java.util.Arrays.stream(d.getMethod().getParameterTypes())
                            .map(Class::getSimpleName)
                            .collect(java.util.stream.Collectors.joining(",", "(", ")"));
                    String aliasId = "Vars." + d.getMethod().getName() + sig;
                    if (!byId.containsKey(aliasId)) {
                        BlockDefinition alias = BlockDefinition.builder()
                                .id(aliasId)
                                .displayName(d.getDisplayName())
                                .group(d.getGroup())
                                .declaringClass(d.getDeclaringClass())
                                .method(d.getMethod())
                                .params(d.getParams())
                                .build();
                        copies.add(alias);
                    }
                }
            }
            for (BlockDefinition c : copies) byId.put(c.getId(), c);
        }
        registerFromClassFiltered(Global.class, "Sleeps",
                m -> {
                    String n = m.getName();
                    if ("sleep".equals(n)) {
                        Class<?>[] p = m.getParameterTypes();
                        return p.length == 1 && (p[0] == int.class || p[0] == Integer.class)
                                || (p.length == 2 && (p[0] == int.class || p[0] == Integer.class) && (p[1] == int.class || p[1] == Integer.class));
                    }
                    if ("sleepGaussian".equals(n)) {
                        Class<?>[] p = m.getParameterTypes();
                        return p.length == 2 && (p[0] == int.class || p[0] == Integer.class) && (p[1] == int.class || p[1] == Integer.class);
                    }
                    return false;
                });
        {
            List<BlockParam> params = new ArrayList<>();
            params.add(new BlockParam("timeoutMs", int.class));
            BlockDefinition sleepUntilDef = BlockDefinition.builder()
                    .id("SLEEPS.SLEEPUNTIL")
                    .displayName("Sleeps: sleepUntil(int timeoutMs)")
                    .group("Sleeps")
                    .declaringClass(Global.class)
                    .method(null)
                    .params(params)
                    .build();
            byId.put(sleepUntilDef.getId(), sleepUntilDef);
        }
        registerFromClassFiltered(net.runelite.client.plugins.microbot.Microbot.class, "Microbot",
                m -> {
                    String n = m.getName();
                    if ("showMessage".equals(n)) return true;
                    if ("log".equals(n)) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 1 && p[0] == String.class) return true;
                        if (p.length == 2 && p[0] == String.class && p[1].isEnum()) return true;
                        return false;
                    }
                    return false;
                });
    }

    private static boolean isSupportedParamType(Class<?> t) {
        return t == int.class || t == Integer.class
                || t == boolean.class || t == Boolean.class
                || t == String.class || t.isEnum();
    }

    private void registerFromClass(Class<?> clazz, String groupPrefix) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
            if (m.getDeclaringClass() != clazz) continue;
            if (m.isAnnotationPresent(Deprecated.class)) continue;
            if (m.getParameterCount() > 4) continue;
            if (!Arrays.stream(m.getParameterTypes()).allMatch(BlockRegistry::isSupportedParamType)) continue;
            if (Arrays.stream(m.getParameterTypes()).anyMatch(t -> t == boolean.class || t == Boolean.class)) continue;
            String id = clazz.getSimpleName() + "." + m.getName() + Arrays.stream(m.getParameterTypes())
                    .map(Class::getSimpleName).collect(Collectors.joining(",", "(", ")"));
            List<BlockParam> params = new ArrayList<>();
            java.lang.reflect.Parameter[] ps = m.getParameters();
            boolean namesPresent = Arrays.stream(ps).allMatch(java.lang.reflect.Parameter::isNamePresent);
            for (int i = 0; i < ps.length; i++) {
                String pname = inferParamName(m, i, ps[i].getType(), ps.length, namesPresent, ps);
                params.add(new BlockParam(pname, ps[i].getType()));
            }
            String display = groupPrefix + ": " + m.getName() + params.stream()
                    .map(bp -> bp.getType().getSimpleName() + " " + bp.getName())
                    .collect(Collectors.joining(", ", "(", ")"));
            BlockDefinition def = BlockDefinition.builder()
                    .id(id)
                    .displayName(display)
                    .group(groupPrefix)
                    .declaringClass(clazz)
                    .method(m)
                    .params(params)
                    .build();
            byId.put(id, def);
        }
    }

    private void registerFromClassFiltered(Class<?> clazz, String groupPrefix, java.util.function.Predicate<Method> filter) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
            if (m.getDeclaringClass() != clazz) continue;
            if (m.isAnnotationPresent(Deprecated.class)) continue;
            if (!filter.test(m)) continue;
            if (m.getParameterCount() > 4) continue;
            if (!Arrays.stream(m.getParameterTypes()).allMatch(BlockRegistry::isSupportedParamType)) continue;
            if (Arrays.stream(m.getParameterTypes()).anyMatch(t -> t == boolean.class || t == Boolean.class)) continue;

            String id = clazz.getSimpleName() + "." + m.getName() + Arrays.stream(m.getParameterTypes())
                    .map(Class::getSimpleName).collect(Collectors.joining(",", "(", ")"));
            List<BlockParam> params = new ArrayList<>();
            java.lang.reflect.Parameter[] ps = m.getParameters();
            boolean namesPresent = Arrays.stream(ps).allMatch(java.lang.reflect.Parameter::isNamePresent);
            for (int i = 0; i < ps.length; i++) {
                String pname = inferParamName(m, i, ps[i].getType(), ps.length, namesPresent, ps);
                params.add(new BlockParam(pname, ps[i].getType()));
            }
            String display = groupPrefix + ": " + m.getName() + params.stream()
                    .map(bp -> bp.getType().getSimpleName() + " " + bp.getName())
                    .collect(Collectors.joining(", ", "(", ")"));
            BlockDefinition def = BlockDefinition.builder()
                    .id(id)
                    .displayName(display)
                    .group(groupPrefix)
                    .declaringClass(clazz)
                    .method(m)
                    .params(params)
                    .build();
            byId.put(id, def);
        }
    }

    private static String inferParamName(Method m, int index, Class<?> type, int arity, boolean anyNamesPresent, java.lang.reflect.Parameter[] ps) {
        if (ps[index].isNamePresent()) return ps[index].getName();
        String method = m.getName().toLowerCase();
        String typeName = type.getSimpleName();
        if (type == String.class) {
            if (method.contains("action")) return "action";
            if (method.contains("item")) return index == 0 ? "itemName" : "secondaryItemName";
            if (method.contains("npc")) return "npcName";
            if (method.contains("object")) return "objectName";
            if (method.contains("widget")) return "widgetText";
            if (method.contains("spell")) return "spellName";
            if (method.contains("tab")) return "tabName";
            return arity == 1 ? "name" : (index == 0 ? "primaryName" : "secondaryName");
        }
        if (type == int.class || type == Integer.class) {
            if (method.contains("sleep")) {
                if (arity == 1) return "millis";
                return index == 0 ? "min" : "max";
            }
            if (method.contains("slot")) return "slot";
            if (method.contains("amount") || method.contains("qty") || method.contains("quantity") || method.contains("count")) return "amount";
            if (method.contains("item")) return "itemId";
            if (method.contains("npc")) return "npcId";
            if (method.contains("object")) return "objectId";
            if (method.contains("x")) return index == 0 ? "x" : (method.contains("y") ? "x" : "value");
            if (method.contains("y")) return index == 1 ? "y" : "value";
            return "value";
        }
        if (type == boolean.class || type == Boolean.class) {
            if (method.contains("exact") || method.equals("hasitem")) return "exact";
            if (method.contains("force")) return "force";
            if (method.contains("equip")) return "equip";
            return "flag";
        }
        if (type.isEnum()) {
            String n = type.getSimpleName();
            return Character.toLowerCase(n.charAt(0)) + n.substring(1);
        }
        return "arg" + index;
    }

    public List<BlockDefinition> all() {
        return new ArrayList<>(byId.values());
    }

    public BlockDefinition get(String id) {
        return byId.get(id);
    }

    public Map<String, List<BlockDefinition>> groups() {
        Map<String, List<BlockDefinition>> grouped = new LinkedHashMap<>();
        for (BlockDefinition d : byId.values()) {
            grouped.computeIfAbsent(d.getGroup(), k -> new ArrayList<>()).add(d);
        }
        for (List<BlockDefinition> list : grouped.values()) {
            list.sort(java.util.Comparator.comparing(BlockDefinition::getDisplayName));
        }
        return grouped;
    }

    public List<BlockDefinition> conditions() {
        List<BlockDefinition> out = new ArrayList<>();
        for (BlockDefinition d : byId.values()) {
            if (d.getMethod() != null) {
                Class<?> rt = d.getMethod().getReturnType();
                if (rt == boolean.class || rt == Boolean.class) out.add(d);
            }
        }
        out.sort(java.util.Comparator.comparing(BlockDefinition::getDisplayName));
        return out;
    }
}
