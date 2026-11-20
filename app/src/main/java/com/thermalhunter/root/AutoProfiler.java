package com.thermalhunter.root;

import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto attribution: maintains a lowest-priority daemon thread in root process, periodically low-frequency
 * sampling host processes that are "injected with modules and consuming CPU" to continuously report CPU consumed by each LSPosed module.
 *
 * Overhead control:
 * - Runs only when App is reading data (UI visible / floating window active), pauses after 20s without consumer;
 * - 30-second cycles, sampling 15s at 40Hz; skips cycle entirely if hosts are idle;
 * - Samples at most 4 processes simultaneously.
 */
final class AutoProfiler implements Runnable {

    private static final int PERIOD_MS = 30_000, IDLE_RECHECK_MS = 10_000, WINDOW_SEC = 15, FREQ = 40;
    private static final double MIN_HOST_CPU = 2.0;   // Do not sample host processes with CPU (single-core %) below this value
    private static final double EMA = 0.6;             // Weight for new window
    private static final long DEMAND_TIMEOUT_MS = 20_000;

    private final Scanner scanner;
    private volatile boolean enabled = true;
    private volatile boolean broken;                   // simpleperf unavailable, stop attempting
    private volatile long lastDemand;
    private volatile JSONObject latest;
    private final Map<String, Double> emaCpu = new HashMap<>();

    AutoProfiler(Scanner scanner) {
        this.scanner = scanner;
    }

    void setEnabled(boolean on) {
        enabled = on;
        if (!on) latest = null;
    }

    /** Called on each SNAPSHOT: indicates active data consumer, returns latest attribution result (may be null). */
    JSONObject poll() {
        lastDemand = SystemClock.elapsedRealtime();
        return enabled ? latest : null;
    }

    void start() {
        Thread t = new Thread(this, "auto-profiler");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);
        } catch (Throwable ignored) {
        }
        while (true) {
            long start = SystemClock.elapsedRealtime();
            int next;
            try {
                next = cycle();
            } catch (Throwable e) {
                latest = error("Auto attribution error: " + e);
                next = PERIOD_MS;
            }
            long spent = SystemClock.elapsedRealtime() - start;
            sleep(Math.max(2000, next - spent));
        }
    }

    /**
     * @return Interval to next cycle (ms): full cycle after sampling; quick recheck when hosts are idle;
     * 2s when inactive (no consumer / disabled / scanner data not ready) to start promptly.
     */
    private int cycle() throws Exception {
        if (!enabled || broken || SystemClock.elapsedRealtime() - lastDemand > DEMAND_TIMEOUT_MS) return 2000;
        // Process CPU is only meaningful after at least two scans (no diff on first scan, all 0)
        if (scanner.scans < 2) return 2000;
        if (!Profiler.available()) {
            broken = true;
            latest = error("System lacks built-in simpleperf, unable to automatically attribute module usage");
            return PERIOD_MS;
        }
        Map<Integer, String> targets = pickTargets();
        if (targets.isEmpty()) {
            // Hosts are idle: module usage treated as 0, decaying old data gradually
            decayAll();
            latest = result(null, Collections.emptyList(), "Injected processes are all idle");
            return IDLE_RECHECK_MS;
        }
        Profiler.Window w;
        try {
            w = Profiler.sample(targets, WINDOW_SEC, FREQ);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("simpleperf")) broken = true;
            latest = error(msg);
            return PERIOD_MS;
        }
        double wallNs = Math.max(1, w.wallNs);
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Long> e : w.moduleNs.entrySet()) {
            double cpu = e.getValue() * 100.0 / wallNs; // Single-core percentage
            Double old = emaCpu.get(e.getKey());
            emaCpu.put(e.getKey(), old == null ? cpu : old * (1 - EMA) + cpu * EMA);
            seen.add(e.getKey());
        }
        for (Map.Entry<String, Double> e : emaCpu.entrySet()) {
            if (!seen.contains(e.getKey())) e.setValue(e.getValue() * (1 - EMA));
        }
        latest = result(w, new ArrayList<>(targets.values()), null);
        return PERIOD_MS;
    }

    /**
     * system_server + app processes scoped by LSPosed modules and consuming CPU, at most 4.
     * system_server is always a candidate: Zygisk modules (e.g. HMA-OSS) also inject into it, which is not visible in LSPosed scope.
     */
    private Map<Integer, String> pickTargets() {
        boolean wantSystem = true;
        Set<String> scoped = new HashSet<>();
        for (LspModules.Module m : LspModules.get().modules) {
            if (!m.enabled) continue;
            for (LspModules.Scope s : m.scopes) {
                if (!"system".equals(s.pkg) && !"android".equals(s.pkg)) scoped.add(s.pkg);
            }
        }
        List<Scanner.Proc> cands = new ArrayList<>();
        synchronized (scanner.lastProcs) {
            for (Scanner.Proc p : scanner.lastProcs) {
                if (p.cpu < MIN_HOST_CPU) continue;
                if (wantSystem && "system_server".equals(p.comm)) cands.add(p);
                else if (p.pkg != null && scoped.contains(p.pkg) && "app".equals(p.kind)) cands.add(p);
            }
        }
        Collections.sort(cands, (a, b) -> Double.compare(b.cpu, a.cpu));
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Scanner.Proc p : cands) {
            if (out.size() >= 4) break;
            out.put(p.pid, "system_server".equals(p.comm) ? "system_server" : shortProc(p));
        }
        return out;
    }

    private static String shortProc(Scanner.Proc p) {
        String n = p.cmdline == null || p.cmdline.isEmpty() ? p.comm : p.cmdline.split(" ")[0];
        return n;
    }

    private void decayAll() {
        for (Map.Entry<String, Double> e : emaCpu.entrySet()) e.setValue(e.getValue() * (1 - EMA));
    }

    private JSONObject result(Profiler.Window w, List<String> targets, String idle) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("time", SystemClock.elapsedRealtime());
        o.put("period", PERIOD_MS / 1000);
        if (idle != null) o.put("idle", idle);
        o.put("targets", new JSONArray(targets));
        JSONObject mods = new JSONObject();
        for (Map.Entry<String, Double> e : emaCpu.entrySet()) {
            if (e.getValue() < 0.05 && (w == null || !w.moduleNs.containsKey(e.getKey()))) continue;
            JSONObject m = new JSONObject();
            m.put("cpu", round(e.getValue()));
            if (w != null) {
                double wallNs = Math.max(1, w.wallNs);
                JSONObject procs = new JSONObject();
                Map<String, Long> pm = w.moduleProcNs.get(e.getKey());
                if (pm != null) for (Map.Entry<String, Long> pe : pm.entrySet()) {
                    procs.put(pe.getKey(), round(pe.getValue() * 100.0 / wallNs));
                }
                m.put("procs", procs);
                JSONArray methods = new JSONArray();
                Map<String, Long> mm = w.moduleMethodNs.get(e.getKey());
                if (mm != null) {
                    List<Map.Entry<String, Long>> l = new ArrayList<>(mm.entrySet());
                    Collections.sort(l, (a, b) -> Long.compare(b.getValue(), a.getValue()));
                    for (int i = 0; i < Math.min(5, l.size()); i++) {
                        methods.put(new JSONObject().put("name", l.get(i).getKey())
                                .put("cpu", round(l.get(i).getValue() * 100.0 / wallNs)));
                    }
                }
                m.put("methods", methods);
            }
            mods.put(e.getKey(), m);
        }
        o.put("modules", mods);
        if (w != null) {
            double wallNs = Math.max(1, w.wallNs);
            o.put("samples", w.samples);
            o.put("bridge", round(w.bridgeNs * 100.0 / wallNs));
            o.put("ambiguous", round(w.ambiguousNs * 100.0 / wallNs));
            o.put("javaFrac", w.samples == 0 ? 0 : round(w.javaSamples * 100.0 / w.samples));
        }
        return o;
    }

    private static JSONObject error(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg);
            o.put("time", SystemClock.elapsedRealtime());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
