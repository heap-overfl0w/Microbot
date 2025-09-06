package net.runelite.client.plugins.microbot.scriptbuilder.runner;

import lombok.Getter;
import lombok.Setter;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockDefinition;
import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockInstance;
import net.runelite.client.plugins.microbot.scriptbuilder.registry.BlockRegistry;
import net.runelite.client.plugins.microbot.scriptbuilder.variables.ScriptVars;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class ScriptRunner {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ScriptBuilder-Runner");
        t.setDaemon(true);
        return t;
    });

    private final BlockRegistry registry;
    @Getter
    private final List<BlockInstance> steps = new ArrayList<>();

    private int index = 0;
    private Future<?> inFlight;
    private volatile boolean running = false;
    private long stepStart = 0L;
    private long stepTimeoutMs = 15000L;
    @Setter
    private volatile Consumer<String> logSink;
    @Setter
    private volatile boolean verbose = false;
    @Setter
    private volatile boolean loopEnabled = false;
    @Setter
    private volatile long loopDelayMs = 0L;
    private volatile boolean failureDetected = false;
    private volatile long nextLoopAt = 0L;
    private int[] ifToElse;
    private int[] ifToEnd;
    private int[] elseToEnd;
    private int[] elseToIf;
    private int[] endToIf;
    private final java.util.Deque<Integer> ifStack = new java.util.ArrayDeque<>();

    public ScriptRunner(BlockRegistry registry) {
        this.registry = registry;
    }

    public synchronized void load(List<BlockInstance> instances) {
        stop();
        steps.clear();
        steps.addAll(instances);
        index = 0;
        computeControlPairs();
        log("Loaded " + steps.size() + " step(s)");
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        index = 0;
        failureDetected = false;
        nextLoopAt = 0L;
        executor.scheduleAtFixedRate(this::tick, 0, 300, TimeUnit.MILLISECONDS);
        log("Script started (" + steps.size() + " step(s))");
    }

    public synchronized void stop() {
        running = false;
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        nextLoopAt = 0L;
        log("Script stopped");
    }

    private void tick() {
        try {
            if (!running) return;
            if (index >= steps.size()) {
                if (loopEnabled && !failureDetected && !steps.isEmpty()) {
                    long now = System.currentTimeMillis();
                    if (nextLoopAt == 0L) {
                        nextLoopAt = now + Math.max(0L, loopDelayMs);
                        long wait = Math.max(0L, nextLoopAt - now);
                        Microbot.status = "Looping in " + wait + "ms";
                        log("Looping in " + wait + "ms");
                        return;
                    }
                    if (now >= nextLoopAt) {
                        log("Looping to start");
                        nextLoopAt = 0L;
                        index = 0;
                    } else {
                        long wait = nextLoopAt - now;
                        Microbot.status = "Looping in " + wait + "ms";
                        return;
                    }
                } else {
                    Microbot.status = "Script finished";
                    log("Script finished");
                    stop();
                    return;
                }
            }

            if (inFlight == null) {
                BlockInstance step = steps.get(index);
                if (step.getKind() == null) step.setKind(BlockInstance.Kind.ACTION);
                switch (step.getKind()) {
                    case ACTION: {
                        BlockDefinition def = registry.get(step.getDefinitionId());
                        if (def == null) {
                            log("Unknown action: " + step.getDefinitionId());
                            if (loopEnabled) { failureDetected = true; stop(); return; }
                            index++;
                            return;
                        }
                        if ("SLEEPS.SLEEPUNTIL".equals(def.getId())) {
                            BlockDefinition condDef = registry.get(step.getConditionDefinitionId());
                            int timeoutParsed;
                            try {
                                String raw = step.getArgs().isEmpty() ? null : step.getArgs().get(0);
                                timeoutParsed = raw == null || raw.isEmpty() ? 5000 : Integer.parseInt(raw);
                            } catch (Exception ex) {
                                timeoutParsed = 5000;
                            }
                            final int timeoutMs = timeoutParsed;
                            if (condDef == null) {
                                log("SleepUntil missing condition");
                                if (loopEnabled) { failureDetected = true; stop(); return; }
                                index++;
                                break;
                            }
                            Object[] cargs = coerceConditionArgs(condDef, step);
                            final BlockDefinition condRef = condDef;
                            final Object[] condArgs = cargs;
                            Microbot.status = "Waiting: " + condRef.getDisplayName();
                            if (verbose) log("Wait until: " + condRef.getDisplayName() + " (" + timeoutMs + "ms)");
                            stepStart = System.currentTimeMillis();
                            inFlight = CompletableFuture.supplyAsync(() -> {
                                long end = System.currentTimeMillis() + timeoutMs;
                                try {
                                    while (System.currentTimeMillis() < end) {
                                        try {
                                            Object res = condRef.getMethod().invoke(null, condArgs);
                                            boolean ok = res instanceof Boolean && (Boolean) res;
                                            if (step.isNegateCondition()) ok = !ok;
                                            if (ok) return true;
                                        } catch (Throwable t) {
                                            log("Condition error: " + t.getClass().getSimpleName());
                                            return false;
                                        }
                                        try { Thread.sleep(100); } catch (InterruptedException ie) { return false; }
                                    }
                                } catch (Throwable t) {
                                    log("Wait error: " + t.getClass().getSimpleName());
                                    return false;
                                }
                                return false;
                            }).thenAccept(success -> {
                                inFlight = null;
                                if (!running) return;
                                if (success) {
                                    if (verbose) log("Condition met: " + condRef.getDisplayName());
                                    index++;
                                } else if (System.currentTimeMillis() - stepStart > stepTimeoutMs || true) {
                                    if (verbose) log("Condition ended: timeout");
                                    if (loopEnabled) { failureDetected = true; stop(); return; }
                                    index++;
                                }
                            });
                            break;
                        }
                        Object[] args = coerceArgs(def, step);
                        log("Action: " + prettyAction(def, step, args));
                        Method m = def.getMethod();
                        Method maybeExact = preferExactOverloadIfVarString(def, step);
                        if (maybeExact != null) {
                            Object[] withExact = java.util.Arrays.copyOf(args, args.length + 1);
                            withExact[withExact.length - 1] = Boolean.TRUE;
                            args = withExact;
                            m = maybeExact;
                            if (verbose) log("Using exact overload for variable string");
                        }
                        final Method invokeMethod = m;
                        final Object[] invokeArgs = args;
                        Microbot.status = "Running: " + def.getDisplayName();
                        if (verbose) log("Invoke: " + def.getMethod().getName());
                        stepStart = System.currentTimeMillis();
                        inFlight = CompletableFuture.supplyAsync(() -> {
                            try {
                                Object res = invokeMethod.invoke(null, invokeArgs);
                                if (res instanceof Boolean) return (Boolean) res;
                                return true;
                            } catch (Throwable t) {
                                log("Error: " + t.getClass().getSimpleName());
                                return false;
                            }
                        }).thenAccept(success -> {
                            inFlight = null;
                            if (!running) return;
                            if (Boolean.TRUE.equals(success)) {
                                log("OK");
                                index++;
                            } else if (System.currentTimeMillis() - stepStart > stepTimeoutMs) {
                                Microbot.status = "Step timeout: " + def.getDisplayName();
                                log("Timeout: " + def.getDisplayName());
                                if (loopEnabled) { failureDetected = true; stop(); return; }
                                index++;
                            } else {
                                log("Failed: " + def.getDisplayName());
                                if (loopEnabled) { failureDetected = true; stop(); return; }
                            }
                        });
                        break;
                    }
                    case COMMENT: {
                        index++;
                        break;
                    }
                    case IF: {
                        if (verbose) log("IF " + condDefLabel(step));
                        boolean cond = false;
                        try {
                            BlockDefinition condDef = registry.get(step.getConditionDefinitionId());
                            if (condDef != null) {
                                Object[] cargs = coerceConditionArgs(condDef, step);
                                Object res = condDef.getMethod().invoke(null, cargs);
                                cond = res instanceof Boolean ? (Boolean) res : false;
                            }
                        } catch (Throwable t) {
                            log("IF error: " + t.getClass().getSimpleName());
                            if (loopEnabled) { failureDetected = true; stop(); return; }
                        }
                        if (verbose) log("IF result: " + (step.isNegateCondition() ? !cond : cond));
                        if (step.isNegateCondition()) cond = !cond;
                        if (cond) {
                            ifStack.push(index);
                            if (verbose) log("IF enter");
                            index++;
                        } else {
                            int jump;
                            if (ifToElse[index] != -1) {
                                jump = ifToElse[index] + 1;
                            } else if (ifToEnd[index] != -1) {
                                jump = ifToEnd[index] + 1;
                            } else {
                                if (verbose) log("IF skip");
                                jump = Math.min(index + 2, steps.size());
                            }
                            if (verbose) log("Jump to " + jump);
                            index = jump;
                        }
                        break;
                    }
                    case ELSE: {
                        if (verbose) log("ELSE");
                        int ifIdx = elseToIf[index];
                        if (!ifStack.isEmpty() && ifStack.peek() == ifIdx) {
                            int end = elseToEnd[index] != -1 ? elseToEnd[index] : index + 1;
                            if (verbose) log("ELSE jump to " + (end + 1));
                            index = end + 1;
                        } else {
                            if (verbose) log("ELSE fallthrough");
                            index++;
                        }
                        break;
                    }
                    case ENDIF: {
                        if (!ifStack.isEmpty() && ifStack.peek() == endToIf[index]) {
                            ifStack.pop();
                        }
                        if (verbose) log("ENDIF");
                        index++;
                        break;
                    }
                }
            } else {
                if (System.currentTimeMillis() - stepStart > stepTimeoutMs) {
                    inFlight.cancel(true);
                    inFlight = null;
                    log("Cancelled in-flight step");
                    if (loopEnabled) { failureDetected = true; stop(); return; }
                    index++;
                }
            }
        } catch (Throwable t) {
            log("Runner error: " + t.getClass().getSimpleName());
            if (loopEnabled) { failureDetected = true; stop(); }
        }
    }

    private Method preferExactOverloadIfVarString(BlockDefinition def, BlockInstance inst) {
        try {
            Method base = def.getMethod();
            if (base == null) return null;
            Class<?>[] pts = base.getParameterTypes();
            boolean anyVarString = false;
            for (int i = 0; i < pts.length; i++) {
                if (pts[i] == String.class) {
                    String raw = i < inst.getArgs().size() ? inst.getArgs().get(i) : null;
                    if (raw != null && (raw.startsWith("var:") || (raw.startsWith("${var:") && raw.endsWith("}")))) {
                        anyVarString = true;
                        break;
                    }
                }
            }
            if (!anyVarString) return null;
            
            Class<?> decl = def.getDeclaringClass();
            for (Method m : decl.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers()) || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!m.getName().equals(base.getName())) continue;
                Class<?>[] alt = m.getParameterTypes();
                if (alt.length != pts.length + 1) continue;
                if (!(alt[alt.length - 1] == boolean.class || alt[alt.length - 1] == Boolean.class)) continue;
                boolean prefixMatch = true;
                for (int i = 0; i < pts.length; i++) if (alt[i] != pts[i]) { prefixMatch = false; break; }
                if (!prefixMatch) continue;
                return m;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String prettyAction(BlockDefinition def, BlockInstance inst, Object[] coercedArgs) {
        try {
            StringBuilder sb = new StringBuilder();
            String group = def.getGroup() != null ? def.getGroup() : "";
            String methodName = def.getMethod() != null ? def.getMethod().getName() : (def.getDisplayName() != null ? def.getDisplayName() : "");
            sb.append(group).append(": ").append(methodName).append("(");
            for (int i = 0; i < def.getParams().size(); i++) {
                String pname = def.getParams().get(i).getName();
                Object v = i < coercedArgs.length ? coercedArgs[i] : null;
                if (i > 0) sb.append(", ");
                sb.append(pname).append("=");
                if (v == null) sb.append("null"); else sb.append(String.valueOf(v));
            }
            sb.append(")");
            return sb.toString();
        } catch (Throwable t) {
            return def.getDisplayName();
        }
    }

    private Object[] coerceArgs(BlockDefinition def, BlockInstance inst) {
        int n = def.getParams().size();
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) {
            Class<?> type = def.getParams().get(i).getType();
            String raw = i < inst.getArgs().size() ? inst.getArgs().get(i) : null;
            out[i] = coerce(type, raw);
        }
        return out;
    }

    private Object coerce(Class<?> type, String raw) {
        
        if (raw != null) {
            String keyToken = null;
            if (raw.startsWith("${var:") && raw.endsWith("}")) {
                keyToken = raw.substring(6, raw.length() - 1);
            } else if (raw.startsWith("var:")) {
                keyToken = raw.substring(4);
            }
            if (keyToken != null) {
                try {
                    if (keyToken.startsWith("var:")) keyToken = keyToken.substring(4);
                    String fullKey = keyToken.contains(".") ? keyToken : ("scriptbuilder." + keyToken);
                    Object v = ScriptVars.get(fullKey);
                    if (type == String.class) return v != null ? String.valueOf(v) : null;
                    if (type == int.class || type == Integer.class) {
                        if (v instanceof Number) return ((Number) v).intValue();
                        try { return v != null ? Integer.parseInt(String.valueOf(v)) : 0; } catch (Exception e) { return 0; }
                    }
                    if (type == boolean.class || type == Boolean.class) {
                        if (v instanceof Boolean) return (Boolean) v;
                        return v != null && Boolean.parseBoolean(String.valueOf(v));
                    }
                    if (type == double.class || type == Double.class) {
                        if (v instanceof Number) return ((Number) v).doubleValue();
                        try { return v != null ? Double.parseDouble(String.valueOf(v)) : 0d; } catch (Exception e) { return 0d; }
                    }
                } catch (Exception ignored) {}
            }
        }
        if (type == String.class) {
            if (raw == null) return null;
            String t = raw.trim();
            return t.isEmpty() ? null : t;
        }
        if (type == int.class || type == Integer.class) {
            try { return raw == null ? 0 : Integer.parseInt(raw); } catch (Exception e) { return 0; }
        }
        if (type == boolean.class || type == Boolean.class) {
            return raw != null && Boolean.parseBoolean(raw);
        }
        if (type.isEnum()) {
            if (raw == null) return type.getEnumConstants()[0];
            try { return Enum.valueOf((Class) type, raw); } catch (Exception e) { return type.getEnumConstants()[0]; }
        }
        return null;
    }

    private Object[] coerceConditionArgs(BlockDefinition def, BlockInstance inst) {
        int n = def.getParams().size();
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) {
            Class<?> type = def.getParams().get(i).getType();
            String raw = i < inst.getConditionArgs().size() ? inst.getConditionArgs().get(i) : null;
            out[i] = coerce(type, raw);
        }
        return out;
    }

    public static File scriptsDir() {
        File dir = new File(RuneLite.RUNELITE_DIR, "scriptbuilder");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static void saveToFile(File f, List<BlockInstance> steps) throws Exception {
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(new com.google.gson.Gson().toJson(steps));
        }
    }

    public static List<BlockInstance> loadFromFile(File f) throws Exception {
        try (FileReader fr = new FileReader(f)) {
            java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<List<BlockInstance>>(){}.getType();
            return new com.google.gson.Gson().fromJson(fr, t);
        }
    }

    private void log(String s) {
        Consumer<String> l = logSink;
        if (l != null) l.accept(s);
    }

    private String condDefLabel(BlockInstance step) {
        BlockDefinition d = registry.get(step.getConditionDefinitionId());
        return d != null ? d.getDisplayName() : "<unset>";
    }

    private void computeControlPairs() {
        int n = steps.size();
        ifToElse = new int[n];
        ifToEnd = new int[n];
        elseToEnd = new int[n];
        elseToIf = new int[n];
        endToIf = new int[n];
        java.util.Arrays.fill(ifToElse, -1);
        java.util.Arrays.fill(ifToEnd, -1);
        java.util.Arrays.fill(elseToEnd, -1);
        java.util.Arrays.fill(elseToIf, -1);
        java.util.Arrays.fill(endToIf, -1);
        java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            BlockInstance b = steps.get(i);
            if (b.getKind() == null) b.setKind(BlockInstance.Kind.ACTION);
            if (b.getKind() == BlockInstance.Kind.IF) {
                stack.push(i);
            } else if (b.getKind() == BlockInstance.Kind.ELSE) {
                if (!stack.isEmpty()) {
                    int ifIdx = stack.peek();
                    ifToElse[ifIdx] = i;
                    elseToIf[i] = ifIdx;
                }
            } else if (b.getKind() == BlockInstance.Kind.ENDIF) {
                if (!stack.isEmpty()) {
                    int ifIdx = stack.pop();
                    ifToEnd[ifIdx] = i;
                    endToIf[i] = ifIdx;
                    
                    for (int j = ifIdx + 1; j < i; j++) {
                        if (steps.get(j).getKind() == BlockInstance.Kind.ELSE && elseToIf[j] == ifIdx) {
                            elseToEnd[j] = i;
                            break;
                        }
                    }
                }
            }
        }
    }
}
