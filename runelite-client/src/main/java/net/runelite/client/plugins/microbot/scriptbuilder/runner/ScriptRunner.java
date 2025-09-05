package net.runelite.client.plugins.microbot.scriptbuilder.runner;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockDefinition;
import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockInstance;
import net.runelite.client.plugins.microbot.scriptbuilder.registry.BlockRegistry;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
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
    private int[] ifToElse; // -1 if none
    private int[] ifToEnd;  // -1 if none
    private int[] elseToEnd; // -1 if none
    private int[] elseToIf;  // -1 if none
    private int[] endToIf;   // -1 if none
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
        if (verbose) {
            log("Loaded steps: " + steps.size());
            for (int i = 0; i < steps.size(); i++) {
                BlockInstance s = steps.get(i);
                String d = s.getKind() == BlockInstance.Kind.ACTION ? String.valueOf(s.getDefinitionId()) : String.valueOf(s.getKind());
                log(String.format("[%d] %s", i, d));
            }
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        index = 0;
        executor.scheduleAtFixedRate(this::tick, 0, 300, TimeUnit.MILLISECONDS);
        log("Runner started");
    }

    public synchronized void stop() {
        running = false;
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        log("Runner stopped");
    }

    private void tick() {
        try {
            if (!running) return;
            if (index >= steps.size()) {
                Microbot.status = "Script finished";
                log("Script finished");
                stop();
                return;
            }

            if (inFlight == null) {
                BlockInstance step = steps.get(index);
                if (step.getKind() == null) step.setKind(BlockInstance.Kind.ACTION);
                switch (step.getKind()) {
                    case ACTION: {
                        BlockDefinition def = registry.get(step.getDefinitionId());
                        if (def == null) {
                            log.warn("Missing block definition: {}", step.getDefinitionId());
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
                                log("SleepUntil: no condition selected; skipping");
                                index++;
                                break;
                            }
                            Object[] cargs = coerceConditionArgs(condDef, step);
                            final BlockDefinition condRef = condDef;
                            final Object[] condArgs = cargs;
                            Microbot.status = "Waiting: " + condRef.getDisplayName();
                            log("Start SleepUntil: " + condRef.getDisplayName() + " timeout=" + timeoutMs);
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
                                            log("SleepUntil error: " + t.getMessage());
                                            return false;
                                        }
                                        try { Thread.sleep(100); } catch (InterruptedException ie) { return false; }
                                    }
                                } catch (Throwable t) {
                                    log("SleepUntil exception: " + t.getMessage());
                                    return false;
                                }
                                return false;
                            }).thenAccept(success -> {
                                inFlight = null;
                                if (!running) return;
                                if (success) {
                                    log("SleepUntil satisfied: " + condRef.getDisplayName());
                                    index++;
                                } else if (System.currentTimeMillis() - stepStart > stepTimeoutMs || true) {
                                    // Treat timeout of the sleepUntil step as completion
                                    log("SleepUntil timed out: " + condRef.getDisplayName());
                                    index++;
                                }
                            });
                            break;
                        }
                        Object[] args = coerceArgs(def, step);
                        if (verbose) {
                            log("Invoke: " + def.getId());
                            log("  rawArgs=" + java.util.Arrays.toString(step.getArgs().toArray()));
                            log("  types=" + java.util.Arrays.toString(def.getParams().stream().map(p -> p.getType().getSimpleName()).toArray()));
                            log("  coerced=" + java.util.Arrays.toString(args));
                        }
                        Method m = def.getMethod();
                        Microbot.status = "Running: " + def.getDisplayName();
                        log("Start: " + def.getDisplayName() + " args=" + java.util.Arrays.toString(args));
                        stepStart = System.currentTimeMillis();
                        inFlight = CompletableFuture.supplyAsync(() -> {
                            try {
                                Object res = m.invoke(null, args);
                                if (res instanceof Boolean) return (Boolean) res;
                                return true;
                            } catch (Throwable t) {
                                log.warn("Error invoking {}", def.getId(), t);
                                log("Error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                                return false;
                            }
                        }).thenAccept(success -> {
                            inFlight = null;
                            if (!running) return;
                            if (verbose) log("  returned=" + success + " durationMs=" + (System.currentTimeMillis() - stepStart));
                            if (Boolean.TRUE.equals(success)) {
                                log("Success: " + def.getDisplayName());
                                index++;
                            } else if (System.currentTimeMillis() - stepStart > stepTimeoutMs) {
                                Microbot.status = "Step timeout: " + def.getDisplayName();
                                log("Timeout: " + def.getDisplayName());
                                index++;
                            }
                        });
                        break;
                    }
                    case COMMENT: {
                        index++;
                        break;
                    }
                    case IF: {
                        // evaluate condition synchronously (fast methods)
                        boolean cond = false;
                        try {
                            BlockDefinition condDef = registry.get(step.getConditionDefinitionId());
                            if (condDef != null) {
                                Object[] cargs = coerceConditionArgs(condDef, step);
                                if (verbose) {
                                    log("If eval: " + (condDef != null ? condDef.getId() : "<none>"));
                                    log("  rawArgs=" + java.util.Arrays.toString(step.getConditionArgs().toArray()));
                                    log("  types=" + java.util.Arrays.toString(condDef.getParams().stream().map(p -> p.getType().getSimpleName()).toArray()));
                                    log("  coerced=" + java.util.Arrays.toString(cargs));
                                }
                                Object res = condDef.getMethod().invoke(null, cargs);
                                cond = res instanceof Boolean ? (Boolean) res : false;
                            }
                        } catch (Throwable t) {
                            log("If condition error: " + t.getMessage());
                        }
                        log("If evaluated: " + cond + (step.getConditionDefinitionId() != null ? (" via " + condDefLabel(step)) : " (no condition)"));
                        if (step.isNegateCondition()) cond = !cond;
                        if (cond) {
                            ifStack.push(index);
                            log("If TRUE, entering block");
                            index++;
                        } else {
                            int jump;
                            if (ifToElse[index] != -1) {
                                jump = ifToElse[index] + 1;
                            } else if (ifToEnd[index] != -1) {
                                jump = ifToEnd[index] + 1;
                            } else {
                                // No ELSE/ENDIF present; treat IF as guarding the immediate next action
                                jump = Math.min(index + 2, steps.size());
                            }
                            log("If FALSE, skipping to index " + jump);
                            index = jump;
                        }
                        break;
                    }
                    case ELSE: {
                        // If we are within the matching IF true-branch, skip to ENDIF
                        int ifIdx = elseToIf[index];
                        if (!ifStack.isEmpty() && ifStack.peek() == ifIdx) {
                            int end = elseToEnd[index] != -1 ? elseToEnd[index] : index + 1;
                            if (verbose) log("Else: in true branch, skip to " + (end + 1));
                            index = end + 1;
                        } else {
                            if (verbose) log("Else: entering else body");
                            index++;
                        }
                        break;
                    }
                    case ENDIF: {
                        if (!ifStack.isEmpty() && ifStack.peek() == endToIf[index]) {
                            ifStack.pop();
                        }
                        index++;
                        break;
                    }
                }
            } else {
                if (System.currentTimeMillis() - stepStart > stepTimeoutMs) {
                    inFlight.cancel(true);
                    inFlight = null;
                    log("Force-cancel: step timed out");
                    index++;
                }
            }
        } catch (Throwable t) {
            log.warn("Runner tick error", t);
            log("Runner error: " + t.getMessage());
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
                    // find the nearest ELSE tied to this IF
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
