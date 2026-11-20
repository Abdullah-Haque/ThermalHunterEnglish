package com.thermalhunter.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts raw snapshot from root side into "suspect entities" and scores them.
 * CPU takes the average of the last WINDOW samples (~15 seconds) to avoid misjudging transient spikes;
 * wakelock / alarm data comes from LSPosed hook data, calculated over ~2-minute increments.
 */
public final class Analyzer {

    public static final int APP = 0, NATIVE = 1, KERNEL = 2, KSU = 3, LSP = 4;
    public static final int OK = 0, WARN = 1, BAD = 2;

    // ---- Decision thresholds (CPU is single-core percentage, matching top) ----
    static final int WINDOW = 5;
    static final double CPU_HIGH = 35;        // Any process sustained above this value
    static final double CPU_BG = 10;          // Background (adj>=200) process
    static final double CPU_CACHED = 3;       // Cached process (adj>=900) should consume almost no CPU
    static final double CPU_NATIVE = 20;      // Non-system-critical native process
    static final double CPU_KSU = 5;          // KSU module processes total
    static final double CPU_LSP_SELF = 8;     // LSP module's own process
    static final double LSP_MEASURED_BAD = 5;  // Auto attribution: module code measured CPU (single-core %)
    static final double LSP_MEASURED_WARN = 2;
    static final double CPU_KERNEL_WARN = 30; // Kernel thread (warning only, cannot be killed)
    static final double WL_FRAC_BAD = 0.5;    // Background app wakelock duration ratio
    static final double ALARM_PER_MIN_BAD = 6;

    public static final class Entity {
        public int type;
        public String key, title, sub = "";
        public String pkg = "";
        public int user;
        public String ksuId, lspPkg;
        public final List<Integer> pids = new ArrayList<>();
        public final List<String> pidNames = new ArrayList<>();
        public double cpuNow, cpuAvg;
        public long rssKb;
        public int minAdj = 1001;
        public boolean foreground, isProtected, enabled = true, isSelf;
        public double wlFrac = -1, alarmsPerMin = -1;
        public long wlHeldTotalMs, alarmsTotal;
        public String wlTag;
        public long powerMw = -1;
        public double heat;
        public int level = OK;
        public final List<String> reasons = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> tags = new ArrayList<>();
        public JSONObject raw;
        public final List<String> scopes = new ArrayList<>();
        /** Total CPU of regular app (host) processes in scope, excluding system_server; this is total host consumption, not the module's own overhead. */
        public double scopeCpu;
        public int scopeRunning;
        /** system_server CPU when injecting system framework; shared by all such modules, cannot be split to individual modules. -1 indicates not applicable. */
        public double systemCpu = -1;
        public boolean injectsSystem;
        /** Module code CPU measured by auto attribution (single-core %); -1 indicates no measured data. */
        public double measuredCpu = -1;
        /** Measured details: {cpu, procs:{process:cpu}, methods:[{name,cpu}]}. */
        public JSONObject measured;
    }

    public static final class Result {
        public JSONObject snap;
        public final List<Entity> heat = new ArrayList<>();   // App/process/kernel thread
        public final List<Entity> ksu = new ArrayList<>();
        public final List<Entity> lsp = new ArrayList<>();
        public final List<Entity> wake = new ArrayList<>();   // Apps with wakelock/alarm data
        public double maxTemp = -999, batteryTemp = -999, skinTemp = -999;
        public String maxTempZone = "";
        public long powerMw = -1;
        public boolean discharging;
        public boolean hookOk;
        public long hookAgeSec = -1;
        public int badCount;
        /** Auto attribution status returned by root process (may be null). */
        public JSONObject prof;
    }

    private final ArrayDeque<Map<String, Double>> cpuHistory = new ArrayDeque<>();
    private final ArrayDeque<Object[]> hookHistory = new ArrayDeque<>(); // {elapsedMs, pkgs JSONObject}

    public Result update(JSONObject s) {
        Result r = new Result();
        r.snap = s;
        int selfPid = s.optInt("selfPid", -1);

        // ---------------- Temperature and Power
        JSONArray th = s.optJSONArray("thermal");
        if (th != null) {
            for (int i = 0; i < th.length(); i++) {
                JSONObject z = th.optJSONObject(i);
                String type = z.optString("type").toLowerCase();
                double t = z.optDouble("temp");
                if (isSocZone(type) && t > r.maxTemp) {
                    r.maxTemp = t;
                    r.maxTempZone = z.optString("type");
                }
                if ((type.contains("shell") || type.contains("skin") || type.contains("board") || type.contains("ap_ntc"))
                        && t > r.skinTemp) r.skinTemp = t;
            }
        }
        JSONObject bat = s.optJSONObject("battery");
        if (bat != null) {
            r.batteryTemp = bat.optDouble("tempC", -999);
            r.discharging = "Discharging".equalsIgnoreCase(bat.optString("status"))
                    || "Not charging".equalsIgnoreCase(bat.optString("status"));
            r.powerMw = bat.optLong("powerMw", -1);
        }

        // ---------------- Process -> Entity
        Map<String, Entity> heat = new LinkedHashMap<>();
        Map<String, Double> cpuNow = new HashMap<>();
        Map<String, List<Entity>> byPkg = new HashMap<>();
        JSONArray procs = s.optJSONArray("procs");
        Entity systemServer = null;
        if (procs != null) {
            for (int i = 0; i < procs.length(); i++) {
                JSONObject p = procs.optJSONObject(i);
                String kind = p.optString("kind");
                int pid = p.optInt("pid");
                int uid = p.optInt("uid");
                String pkg = p.optString("pkg");
                String name = p.optString("name");
                String key;
                int type;
                if ("app".equals(kind) && !pkg.isEmpty()) {
                    key = "app:" + pkg + ":" + (uid / 100000);
                    type = APP;
                } else if ("kernel".equals(kind)) {
                    key = "k:" + pid;
                    type = KERNEL;
                } else {
                    key = "n:" + pid + ":" + name;
                    type = NATIVE;
                }
                Entity e = heat.get(key);
                if (e == null) {
                    e = new Entity();
                    e.type = type;
                    e.key = key;
                    e.pkg = pkg;
                    e.user = uid / 100000;
                    e.title = type == APP ? pkg : name;
                    e.ksuId = p.has("ksu") ? p.optString("ksu") : null;
                    e.raw = p;
                    heat.put(key, e);
                    if (type == APP) {
                        List<Entity> l = byPkg.get(pkg);
                        if (l == null) byPkg.put(pkg, l = new ArrayList<>());
                        l.add(e);
                    }
                }
                e.pids.add(pid);
                e.pidNames.add(type == APP ? p.optString("cmd").split(" ")[0] : name);
                e.cpuNow += p.optDouble("cpu");
                e.rssKb += p.optLong("rss");
                e.minAdj = Math.min(e.minAdj, p.optInt("adj"));
                if (pid == selfPid || Protect.isProtectedProcName(p.optString("cmd")) || Protect.isProtectedProcName(name)
                        || (type == APP && Protect.PKGS.contains(pkg))) {
                    e.isProtected = true;
                }
                if (pid == selfPid || "com.thermalhunter".equals(pkg)) e.isSelf = true;
                if ("system_server".equals(name)) systemServer = e;
            }
        }
        for (Entity e : heat.values()) cpuNow.put(e.key, e.cpuNow);
        cpuHistory.addLast(cpuNow);
        while (cpuHistory.size() > WINDOW) cpuHistory.removeFirst();
        double totalCpu = 0;
        for (Entity e : heat.values()) {
            double sum = 0;
            for (Map<String, Double> h : cpuHistory) {
                Double v = h.get(e.key);
                if (v != null) sum += v;
            }
            e.cpuAvg = sum / cpuHistory.size();
            e.foreground = e.minAdj == 0;
            totalCpu += e.cpuAvg;
        }

        // ---------------- LSPosed hook data (wakelock/alarms)
        JSONObject hook = s.optJSONObject("hook");
        Map<String, double[]> wakeRates = new HashMap<>(); // pkg -> {wlFrac, alarmsPerMin}
        JSONObject hookPkgs = null;
        if (hook != null && hook.optBoolean("present")) {
            r.hookAgeSec = hook.optLong("ageSec", -1);
            r.hookOk = r.hookAgeSec >= 0 && r.hookAgeSec < 90;
            hookPkgs = hook.optJSONObject("pkgs");
            long el = hook.optLong("elapsed");
            if (hookPkgs != null && (hookHistory.isEmpty() || (long) hookHistory.peekLast()[0] != el)) {
                hookHistory.addLast(new Object[]{el, hookPkgs});
            }
            while (hookHistory.size() > 2 && el - (long) hookHistory.peekFirst()[0] > 150_000) hookHistory.removeFirst();
            if (hookHistory.size() >= 2) {
                Object[] first = hookHistory.peekFirst();
                Object[] last = hookHistory.peekLast();
                double dt = (long) last[0] - (long) first[0];
                if (dt >= 14_000) {
                    JSONObject a = (JSONObject) first[1], b = (JSONObject) last[1];
                    for (Iterator<String> it = b.keys(); it.hasNext(); ) {
                        String pkg = it.next();
                        JSONObject nb = b.optJSONObject(pkg), na = a.optJSONObject(pkg);
                        long held = nb.optLong("wlHeldMs") - (na == null ? 0 : na.optLong("wlHeldMs"));
                        long al = nb.optLong("alarms") - (na == null ? 0 : na.optLong("alarms"));
                        wakeRates.put(pkg, new double[]{Math.min(1.0, held / dt), al * 60_000.0 / dt});
                    }
                }
            }
        }

        // Merge hook data into app entities; create entities for apps without running processes but still holding locks
        if (hookPkgs != null) {
            for (Iterator<String> it = hookPkgs.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject o = hookPkgs.optJSONObject(pkg);
                // Hook stats are aggregated by package name and do not distinguish users: prefer main user entity, else take any running user entity,
                // otherwise cloned / work profile (user>0) apps will fail to associate wake data
                Entity e = null;
                List<Entity> users = byPkg.get(pkg);
                if (users != null) {
                    for (Entity u : users) if (u.user == 0) { e = u; break; }
                    if (e == null) e = users.get(0);
                }
                double[] rate = wakeRates.get(pkg);
                boolean significant = rate != null && (rate[0] >= 0.2 || rate[1] >= 2);
                if (e == null && significant) {
                    e = new Entity();
                    e.type = APP;
                    e.key = "app:" + pkg + ":0";
                    e.pkg = pkg;
                    e.title = pkg;
                    e.isProtected = Protect.PKGS.contains(pkg);
                    heat.put(e.key, e);
                }
                Entity w = e != null ? e : new Entity();
                if (e == null) {
                    w.type = APP;
                    w.key = "wake:" + pkg;
                    w.pkg = pkg;
                    w.title = pkg;
                    w.isProtected = Protect.PKGS.contains(pkg);
                }
                w.wlHeldTotalMs = o.optLong("wlHeldMs");
                w.alarmsTotal = o.optLong("alarms");
                w.wlTag = o.optString("tag", null);
                if (rate != null) {
                    w.wlFrac = rate[0];
                    w.alarmsPerMin = rate[1];
                }
                if (w.wlHeldTotalMs > 60_000 || w.alarmsTotal > 20 || significant) r.wake.add(w);
            }
            Collections.sort(r.wake, (x, y) -> Long.compare(y.wlHeldTotalMs, x.wlHeldTotalMs));
        }

        // ---------------- Rule Evaluation
        for (Entity e : heat.values()) {
            if (r.discharging && r.powerMw > 0 && totalCpu > 0) {
                e.powerMw = Math.round(r.powerMw * 0.7 * e.cpuAvg / totalCpu); // ~70% of power consumption attributed to CPU load share
            }
            String c = fmt(e.cpuAvg);
            switch (e.type) {
                case APP:
                    if (e.cpuAvg >= CPU_HIGH) bad(e, "Sustained high CPU " + c + "%");
                    else if (e.minAdj >= 900 && e.cpuAvg >= CPU_CACHED) bad(e, "In cached state (background freeze level) but still consuming CPU " + c + "%");
                    else if (e.minAdj >= 200 && e.cpuAvg >= CPU_BG) bad(e, "High background CPU " + c + "%");
                    if (r.discharging && !e.foreground && e.wlFrac >= WL_FRAC_BAD) bad(e, "Holds background wakelock for " + Math.round(e.wlFrac * 100) + "% of time (prevents deep sleep)");
                    if (e.alarmsPerMin >= ALARM_PER_MIN_BAD) bad(e, "Frequently wakes up system " + fmt(e.alarmsPerMin) + " times/min");
                    break;
                case NATIVE:
                    if (e.ksuId != null) {
                        e.tags.add("KSU module: " + e.ksuId);
                        if (e.cpuAvg >= CPU_KSU) bad(e, "KSU module processes consume CPU " + c + "%");
                    } else if (e.cpuAvg >= CPU_NATIVE) {
                        bad(e, "Native process sustained high CPU " + c + "%");
                    }
                    break;
                case KERNEL:
                    if (e.cpuAvg >= CPU_KERNEL_WARN) warn(e, "High kernel thread load " + c + "% (usually caused by drivers/modules/IO, cannot be killed directly)");
                    break;
            }
            if (e.foreground && e.level == BAD) e.tags.add("Foreground");
            e.heat = e.cpuAvg + Math.max(0, e.wlFrac) * 20 + Math.min(30, Math.max(0, e.alarmsPerMin)) * 0.5
                    + (e.level == BAD ? 1000 : 0);
        }
        r.heat.addAll(heat.values());
        r.heat.removeIf(e -> e.type == KERNEL && e.cpuAvg < 1 && e.level == OK);
        Collections.sort(r.heat, (x, y) -> Double.compare(y.heat, x.heat));

        // ---------------- Auto attribution (measured results from root process simpleperf sampling)
        JSONObject prof = s.optJSONObject("prof");
        r.prof = prof;
        boolean profOk = prof != null && prof.optBoolean("ok");
        JSONObject profMods = profOk ? prof.optJSONObject("modules") : null;

        // ---------------- KSU Modules
        double maxT = Math.max(r.maxTemp, r.skinTemp);
        JSONArray ksu = s.optJSONArray("ksu");
        if (ksu != null) {
            for (int i = 0; i < ksu.length(); i++) {
                JSONObject m = ksu.optJSONObject(i);
                Entity e = new Entity();
                e.type = KSU;
                e.ksuId = m.optString("id");
                e.key = "ksu:" + e.ksuId;
                e.title = m.optString("name");
                e.sub = m.optString("id") + "  " + m.optString("version") + "  " + m.optString("author");
                e.enabled = m.optBoolean("enabled");
                e.raw = m;
                // Use app-side CPU historical average to avoid transient spikes
                for (Entity h : heat.values()) {
                    if (e.ksuId.equals(h.ksuId)) {
                        e.cpuAvg += h.cpuAvg;
                        e.cpuNow += h.cpuNow;
                        e.pids.addAll(h.pids);
                        e.pidNames.addAll(h.pidNames);
                        e.rssKb += h.rssKb;
                        if (h.powerMw > 0) e.powerMw = Math.max(0, e.powerMw) + h.powerMw;
                    }
                }
                JSONArray tags = m.optJSONArray("tags");
                if (tags != null) for (int t = 0; t < tags.length(); t++) e.tags.add(tags.optString(t));
                if (!e.enabled) e.tags.add(0, "Disabled");
                if (m.optBoolean("remove")) e.tags.add(0, "Pending removal");
                if (e.enabled && e.cpuAvg >= CPU_KSU) bad(e, "Module processes total CPU " + fmt(e.cpuAvg) + "%");
                // Java code injected into other processes via Zygisk etc. (e.g. HMA-OSS), measured by auto attribution
                e.measured = profMods == null ? null : profMods.optJSONObject("ksu:" + e.ksuId);
                if (e.measured != null) {
                    e.measuredCpu = e.measured.optDouble("cpu", 0);
                    String where = topProc(e.measured);
                    if (e.enabled && e.measuredCpu >= LSP_MEASURED_BAD) {
                        bad(e, "Injected code measured consuming " + fmt(e.measuredCpu) + "% single-core" + where);
                    } else if (e.enabled && e.measuredCpu >= LSP_MEASURED_WARN) {
                        warn(e, "Injected code measured consuming " + fmt(e.measuredCpu) + "% single-core" + where);
                    }
                }
                if (e.enabled && maxT >= 45 && (e.tags.contains("Replaces thermal config") || e.tags.contains("Script manipulates thermal"))) {
                    warn(e, "Current temperature is " + fmt(maxT) + "°C, and this module modifies thermal management, which may be causing heating");
                }
                if (e.enabled && e.tags.contains("Holds kernel wakelock")) warn(e, "Script writes to /sys/power/wake_lock, which may prevent deep sleep");
                e.heat = e.cpuAvg + Math.max(0, e.measuredCpu) + (e.level == BAD ? 1000 : e.level == WARN ? 100 : 0) + (e.enabled ? 1 : 0);
                r.ksu.add(e);
            }
            Collections.sort(r.ksu, (x, y) -> Double.compare(y.heat, x.heat));
        }

        // ---------------- LSPosed Modules
        JSONObject lsp = s.optJSONObject("lsp");
        if (lsp != null) {
            JSONArray mods = lsp.optJSONArray("modules");
            if (mods != null) {
                for (int i = 0; i < mods.length(); i++) {
                    JSONObject m = mods.optJSONObject(i);
                    Entity e = new Entity();
                    e.type = LSP;
                    e.lspPkg = m.optString("pkg");
                    e.pkg = e.lspPkg;
                    e.key = "lsp:" + e.lspPkg;
                    e.title = e.lspPkg;
                    e.enabled = m.optBoolean("enabled");
                    e.raw = m;
                    e.isProtected = "com.thermalhunter".equals(e.lspPkg);
                    e.isSelf = e.isProtected;
                    List<Entity> own = byPkg.get(e.lspPkg);
                    if (own != null) for (Entity o : own) {
                        e.cpuAvg += o.cpuAvg;
                        e.pids.addAll(o.pids);
                        e.pidNames.addAll(o.pidNames);
                        e.minAdj = Math.min(e.minAdj, o.minAdj);
                    }
                    boolean injectsSystem = false;
                    List<String> badScoped = new ArrayList<>();
                    JSONArray sc = m.optJSONArray("scope");
                    if (sc != null) for (int k = 0; k < sc.length(); k++) {
                        JSONObject so = sc.optJSONObject(k);
                        String sp = so.optString("pkg");
                        e.scopes.add(sp + (so.optInt("user") != 0 ? " (User " + so.optInt("user") + ")" : ""));
                        if ("system".equals(sp) || "android".equals(sp)) {
                            // system_server is shared by all modules injecting the system framework; its CPU cannot be attributed to a single module
                            injectsSystem = true;
                            if (systemServer != null) {
                                e.systemCpu = systemServer.cpuAvg;
                                e.scopeRunning++;
                            }
                            continue;
                        }
                        List<Entity> running = byPkg.get(sp);
                        if (running != null) for (Entity se : running) {
                            if (se.user != so.optInt("user")) continue;
                            e.scopeCpu += se.cpuAvg;
                            e.scopeRunning++;
                            if (se.level == BAD) badScoped.add(sp);
                        }
                    }
                    e.injectsSystem = injectsSystem;
                    if (profOk) {
                        // With measured data: modules not appearing in results are measured as 0 (host idle or module code not executing)
                        e.measured = profMods == null ? null : profMods.optJSONObject(e.lspPkg);
                        e.measuredCpu = e.measured == null ? 0 : e.measured.optDouble("cpu", 0);
                    }
                    e.sub = "Scope: " + e.scopes.size() + ", Running: " + e.scopeRunning;
                    if (!e.enabled) e.tags.add("Disabled");
                    if (injectsSystem) e.tags.add("Injects System Framework");
                    if (e.scopes.size() >= 30) e.tags.add("Scope too large");
                    if (e.enabled && !e.isSelf) {
                        if (e.cpuAvg >= CPU_LSP_SELF && e.minAdj > 0) bad(e, "Module background process CPU " + fmt(e.cpuAvg) + "%");
                        if (e.measuredCpu >= 0) {
                            // Measured: CPU attributed to this module based on call stack
                            String where = topProc(e.measured);
                            if (e.measuredCpu >= LSP_MEASURED_BAD) {
                                bad(e, "Module code measured CPU " + fmt(e.measuredCpu) + "% single-core" + where);
                            } else if (e.measuredCpu >= LSP_MEASURED_WARN) {
                                warn(e, "Module code measured CPU " + fmt(e.measuredCpu) + "% single-core" + where);
                            }
                        } else if (!badScoped.isEmpty()) {
                            warn(e, "Anomalous apps in scope: " + join(badScoped) + " (the host itself may be the heat source; disable this module to compare)");
                        }
                        if (e.measuredCpu < 0 && injectsSystem && systemServer != null && systemServer.cpuAvg >= 15) {
                            // Warning only, not marked suspicious: cannot determine which module caused it
                            e.warnings.add("system_server CPU " + fmt(systemServer.cpuAvg) + "%, shared by all modules injecting the system framework; "
                                    + "cannot distinguish individual module overhead. If suspicious, disable one by one and reboot to compare");
                        }
                    }
                    e.heat = (e.measuredCpu >= 0 ? e.measuredCpu * 10 : e.scopeCpu * 0.3) + e.cpuAvg
                            + (e.level == BAD ? 1000 : e.level == WARN ? 100 : 0) + (e.enabled ? 1 : 0);
                    r.lsp.add(e);
                }
                Collections.sort(r.lsp, (x, y) -> Double.compare(y.heat, x.heat));
            }
        }

        for (Entity e : r.heat) if (e.level == BAD && !e.isProtected && !e.isSelf) r.badCount++;
        for (Entity e : r.ksu) if (e.level == BAD) r.badCount++;
        for (Entity e : r.lsp) if (e.level == BAD) r.badCount++;
        return r;
    }

    /** One-tap clean targets: anomalous, unprotected, not self; leaves foreground apps untouched by default. */
    public static List<Entity> cleanTargets(Result r, boolean includeForeground) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : r.heat) {
            if (e.level != BAD || e.isProtected || e.isSelf || e.type == KERNEL) continue;
            if (e.foreground && !includeForeground) continue;
            if (e.pids.isEmpty() && e.type != APP) continue;
            out.add(e);
        }
        for (Entity e : r.ksu) if (e.level == BAD && !e.pids.isEmpty()) out.add(e);
        for (Entity e : r.lsp) if (e.level == BAD && !e.isProtected) out.add(e);
        return out;
    }

    /** Host process consuming the most in measured details, e.g. " (mainly in system_server)". */
    private static String topProc(JSONObject measured) {
        JSONObject procs = measured == null ? null : measured.optJSONObject("procs");
        if (procs == null) return "";
        String best = null;
        double v = -1;
        for (Iterator<String> it = procs.keys(); it.hasNext(); ) {
            String k = it.next();
            if (procs.optDouble(k) > v) {
                v = procs.optDouble(k);
                best = k;
            }
        }
        return best == null ? "" : " (mainly in " + best + ")";
    }

    static boolean isSocZone(String t) {
        return t.contains("cpu") || t.contains("soc") || t.contains("mtktscpu") || t.contains("gpu")
                || t.contains("apu") || t.contains("tsens") || t.contains("core");
    }

    private static void bad(Entity e, String reason) {
        e.reasons.add(reason);
        e.level = BAD;
    }

    private static void warn(Entity e, String w) {
        e.warnings.add(w);
        if (e.level < WARN) e.level = WARN;
    }

    public static String fmt(double v) {
        return v >= 10 ? String.valueOf(Math.round(v)) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < 4; i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        if (l.size() > 4) sb.append(" and ").append(l.size()).append(" others");
        return sb.toString();
    }
}
}
