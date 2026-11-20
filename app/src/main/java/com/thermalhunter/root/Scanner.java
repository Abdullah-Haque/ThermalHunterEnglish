package com.thermalhunter.root;

import android.os.SystemClock;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects one system snapshot. CPU usage calculated as delta between two snapshots (single-core percentage, matching top). */
final class Scanner {

    static final class Proc {
        int pid, ppid, uid, threads;
        long startTime, ticks, rssKb;
        String comm, cmdline, exe, pkg, kind, ksu;
        int adj;
        double cpu;
    }

    /** Process static info cache (pid+starttime as key to avoid readlink every time). */
    private static final class Static {
        long startTime;
        String cmdline, exe;
        String cwd; // Lazy-loaded, needed only for native processes
        int uid;
    }

    // Kernel wakeup sources dir has hundreds of entries, each requiring 4 file reads; reduce refresh frequency
    private static final long WAKEUP_REFRESH_MS = 30_000;
    private JSONArray wakeupCache;
    private long wakeupTime;
    private final long[] statBuf = new long[6];

    private final int ncpu = Math.max(1, Runtime.getRuntime().availableProcessors());
    private final long pageKb;
    private final Map<Integer, Static> staticCache = new HashMap<>();
    private final Map<Integer, long[]> prevTicks = new HashMap<>(); // pid -> {startTime, ticks}
    private long prevTotal = -1, prevIdle = -1;
    private final Map<String, long[]> prevWakeup = new HashMap<>(); // name -> {totalMs, count}

    private Map<Integer, List<String>> appIdToPkgs = new HashMap<>();
    private Set<String> allPkgs = new HashSet<>();
    private long pkgListTime;

    final List<Proc> lastProcs = new ArrayList<>();
    /** Completed scan rounds; first round has no previous ticks, CPU is all 0. */
    volatile int scans;
    private JSONObject device;

    Scanner() {
        long ps = 4096;
        try {
            ps = Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        } catch (Throwable ignored) {
        }
        pageKb = ps / 1024;
    }

    // ------------------------------------------------------------------ packages

    private void loadPackages() {
        long now = SystemClock.elapsedRealtime();
        if (now - pkgListTime < 60_000 && !allPkgs.isEmpty()) return;
        pkgListTime = now;
        String s = Sys.readAll("/data/system/packages.list", 4 << 20);
        if (s == null) s = pmPackageList(); // Shell identity (Shizuku mode) cannot read packages.list
        if (s == null) return;
        Map<Integer, List<String>> map = new HashMap<>();
        Set<String> all = new HashSet<>();
        for (String line : s.split("\n")) {
            String[] f = line.split(" ");
            if (f.length < 2) continue;
            try {
                int appId = Integer.parseInt(f[1]);
                List<String> l = map.get(appId);
                if (l == null) map.put(appId, l = new ArrayList<>());
                l.add(f[0]);
                all.add(f[0]);
            } catch (NumberFormatException ignored) {
            }
        }
        appIdToPkgs = map;
        allPkgs = all;
    }

    /** Generates same "pkgName appId" format as packages.list using pm. */
    private static String pmPackageList() {
        Object[] r = Sys.execMax(4 << 20, "pm", "list", "packages", "-U");
        if ((int) r[0] != 0) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : String.valueOf(r[1]).split("\n")) {
            line = line.trim();
            int u = line.indexOf(" uid:");
            if (!line.startsWith("package:") || u < 0) continue;
            String uid = line.substring(u + 5).split("[,\\s]")[0];
            try {
                sb.append(line, 8, u).append(' ').append(Integer.parseInt(uid) % 100000).append('\n');
            } catch (NumberFormatException ignored) {
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ snapshot

    JSONObject snapshot() throws Exception {
        loadPackages();
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("t", SystemClock.elapsedRealtime());
        out.put("ncpu", ncpu);
        out.put("mode", Os.getuid() == 0 ? "root" : "shell");
        out.put("device", device());

        // Total CPU
        long[] cpu = readCpuTotal();
        long dTotal = 0, dIdle = 0;
        if (cpu != null) {
            if (prevTotal >= 0) {
                dTotal = cpu[0] - prevTotal;
                dIdle = cpu[1] - prevIdle;
            }
            prevTotal = cpu[0];
            prevIdle = cpu[1];
        }
        double totalUsage = dTotal > 0 ? (dTotal - dIdle) * 100.0 / dTotal : 0;

        List<Proc> procs = readProcs(dTotal);
        attributeKsu(procs);
        currentProcsForAgg = procs;

        JSONObject cpuObj = new JSONObject();
        cpuObj.put("usage", round1(totalUsage));
        cpuObj.put("policies", cpuPolicies());
        out.put("cpu", cpuObj);
        out.put("gpu", gpu());
        out.put("battery", battery());
        out.put("thermal", thermal());
        out.put("procs", procsJson(procs));
        out.put("rootImpl", KsuModules.rootImpl());
        out.put("ksu", ksuJson());
        out.put("lsp", lspJson());
        out.put("hook", hookJson());
        out.put("wakeups", kernelWakeups());
        out.put("selfPid", Os.getpid());
        synchronized (lastProcs) {
            lastProcs.clear();
            lastProcs.addAll(procs);
        }
        scans++;
        return out;
    }

    private JSONObject device() throws Exception {
        if (device != null) return device;
        JSONObject d = new JSONObject();
        d.put("model", Sys.prop("ro.product.marketname").isEmpty() ? Sys.prop("ro.product.model") : Sys.prop("ro.product.marketname"));
        d.put("modelId", Sys.prop("ro.product.model"));
        d.put("brand", Sys.prop("ro.product.brand"));
        d.put("soc", firstNonEmpty(Sys.prop("ro.soc.model"), Sys.prop("ro.board.platform"), Sys.prop("ro.hardware")));
        d.put("android", Sys.prop("ro.build.version.release"));
        d.put("rom", firstNonEmpty(Sys.prop("ro.vivo.os.build.display.id"), Sys.prop("ro.build.display.id")));
        String kv = Sys.readTrim("/proc/sys/kernel/osrelease");
        if (kv == null || kv.isEmpty()) kv = System.getProperty("os.version"); // Shell identity cannot read /proc/sys
        d.put("kernel", kv == null ? "" : kv);
        device = d;
        return d;
    }

    private static String firstNonEmpty(String... s) {
        for (String x : s) if (x != null && !x.isEmpty()) return x;
        return "";
    }

    private static long[] readCpuTotal() {
        String s = Sys.read("/proc/stat");
        if (s == null) return null;
        int nl = s.indexOf('\n');
        String[] f = s.substring(0, nl > 0 ? nl : s.length()).trim().split("\\s+");
        long total = 0, idle = 0;
        for (int i = 1; i < f.length && i <= 8; i++) {
            long v = Long.parseLong(f[i]);
            total += v;
            if (i == 4 || i == 5) idle += v; // idle + iowait
        }
        return new long[]{total, idle};
    }

    private List<Proc> readProcs(long dTotal) {
        List<Proc> list = new ArrayList<>();
        Set<Integer> alive = new HashSet<>();
        for (String name : Sys.list("/proc")) {
            char c0 = name.charAt(0);
            if (c0 < '0' || c0 > '9') continue;
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            String stat = Sys.read("/proc/" + pid + "/stat");
            if (stat == null) continue;
            int lp = stat.indexOf('('), rp = stat.lastIndexOf(')');
            if (lp < 0 || rp < 0) continue;
            Proc p = new Proc();
            p.pid = pid;
            p.comm = stat.substring(lp + 1, rp);
            long[] f = statBuf;
            if (!parseStat(stat, rp + 2, f)) continue;
            p.ppid = (int) f[0];
            p.ticks = f[1] + f[2];
            p.threads = (int) f[3];
            p.startTime = f[4];
            p.rssKb = f[5] * pageKb;
            alive.add(pid);
            long[] prev = prevTicks.get(pid);
            if (prev != null && prev[0] == p.startTime && dTotal > 0) {
                // Single-core percentage: proc tick / (total tick / ncpu)
                p.cpu = (p.ticks - prev[1]) * 100.0 * ncpu / dTotal;
            }
            prevTicks.put(pid, new long[]{p.startTime, p.ticks});

            boolean kernel = pid == 2 || p.ppid == 2;
            if (kernel) {
                p.kind = "kernel";
                p.cmdline = p.comm;
                p.exe = "";
                p.pkg = "";
                p.uid = 0;
            } else {
                Static st = staticCache.get(pid);
                if (st == null || st.startTime != p.startTime) {
                    st = new Static();
                    st.startTime = p.startTime;
                    String cl = Sys.read("/proc/" + pid + "/cmdline");
                    st.cmdline = cl == null ? "" : cl.replace('\0', ' ').trim();
                    String exe = Sys.readlink("/proc/" + pid + "/exe");
                    st.exe = exe == null ? "" : exe;
                    try {
                        st.uid = Os.stat("/proc/" + pid).st_uid;
                    } catch (Throwable e) {
                        st.uid = -1;
                    }
                    // App process cmdline may still be zygote right after fork, do not cache yet
                    if (!st.cmdline.startsWith("zygote") && !st.cmdline.startsWith("<pre-initialized>")) {
                        staticCache.put(pid, st);
                    }
                }
                p.cmdline = st.cmdline.isEmpty() ? p.comm : st.cmdline;
                p.exe = st.exe;
                p.uid = st.uid;
                resolvePackage(p);
            }
            // Only app processes use adj (foreground / background / cached evaluation); other processes skip to save hundreds of file reads
            p.adj = "app".equals(p.kind) ? (int) Sys.readLong("/proc/" + pid + "/oom_score_adj", 0) : 0;
            list.add(p);
        }
        prevTicks.keySet().retainAll(alive);
        staticCache.keySet().retainAll(alive);
        return list;
    }

    private void resolvePackage(Proc p) {
        String first = p.cmdline;
        int sp = first.indexOf(' ');
        if (sp > 0) first = first.substring(0, sp);
        String base = first;
        int colon = base.indexOf(':');
        if (colon > 0) base = base.substring(0, colon);
        int appId = p.uid % 100000;
        if (allPkgs.contains(base)) {
            p.pkg = base;
            p.kind = "app";
            return;
        }
        if (appId >= 10000 && appId < 20000) {
            List<String> l = appIdToPkgs.get(appId);
            p.pkg = (l == null || l.isEmpty()) ? "" : l.get(0);
            p.kind = "app";
            return;
        }
        if (appId >= 90000 && appId < 100000) { // isolated
            p.pkg = allPkgs.contains(base) ? base : "";
            p.kind = "app";
            return;
        }
        p.pkg = "";
        p.kind = "native";
    }

    // ------------------------------------------------------------------ KSU Attribution

    /** Process working directory, cached for process lifetime (rarely changes). */
    private String cwd(int pid) {
        Static st = staticCache.get(pid);
        if (st == null) return Sys.readlink("/proc/" + pid + "/cwd");
        if (st.cwd == null) {
            String c = Sys.readlink("/proc/" + pid + "/cwd");
            st.cwd = c == null ? "" : c;
        }
        return st.cwd;
    }

    /**
     * Parses fields after ')' in /proc/pid/stat (state is field 0), avoids split to reduce temp objects.
     * out: {ppid(1), utime(11), stime(12), threads(17), starttime(19), rss(21)}
     */
    private static boolean parseStat(String s, int from, long[] out) {
        int field = 0, n = s.length(), i = from;
        while (i < n && field <= 21) {
            int j = i;
            while (j < n && s.charAt(j) != ' ') j++;
            int slot;
            switch (field) {
                case 1: slot = 0; break;
                case 11: slot = 1; break;
                case 12: slot = 2; break;
                case 17: slot = 3; break;
                case 19: slot = 4; break;
                case 21: slot = 5; break;
                default: slot = -1;
            }
            if (slot >= 0) {
                int k = i;
                boolean neg = k < j && s.charAt(k) == '-';
                if (neg) k++;
                if (k == j) return false;
                long v = 0;
                for (; k < j; k++) {
                    char c = s.charAt(k);
                    if (c < '0' || c > '9') return false;
                    v = v * 10 + (c - '0');
                }
                out[slot] = neg ? -v : v;
            }
            field++;
            i = j + 1;
        }
        return field > 21;
    }

    private void attributeKsu(List<Proc> procs) {
        Map<String, String> overlay = KsuModules.overlay();
        Map<Integer, Proc> byPid = new HashMap<>();
        for (Proc p : procs) byPid.put(p.pid, p);
        String prefix = KsuModules.MODULES_DIR + "/";
        for (Proc p : procs) {
            if (!"native".equals(p.kind)) continue;
            String id = moduleFromPath(p.exe, prefix);
            if (id == null) id = moduleFromPath(p.cmdline, prefix);
            if (id == null) id = overlay.get(p.exe);
            if (id == null) id = moduleFromPath(cwd(p.pid), prefix);
            p.ksu = id;
        }
        // Inherit parent process attribution (busybox/sleep/child processes spawned by module service.sh)
        for (Proc p : procs) {
            if (!"native".equals(p.kind) || p.ksu != null) continue;
            Proc cur = byPid.get(p.ppid);
            int guard = 0;
            while (cur != null && guard++ < 16 && cur.pid > 1) {
                if (cur.ksu != null) {
                    p.ksu = cur.ksu;
                    break;
                }
                cur = byPid.get(cur.ppid);
            }
        }
    }

    private static String moduleFromPath(String s, String prefix) {
        if (s == null) return null;
        int i = s.indexOf(prefix);
        if (i < 0) {
            // KernelSU also mounts modules to /data/adb/modules_update or /debug_ramdisk etc.; handles common paths here
            return null;
        }
        int start = i + prefix.length();
        int end = start;
        while (end < s.length() && s.charAt(end) != '/' && s.charAt(end) != ' ') end++;
        return end > start ? s.substring(start, end) : null;
    }

    // ------------------------------------------------------------------ JSON Output

    private JSONArray procsJson(List<Proc> procs) throws Exception {
        // Output only meaningful processes to control data size
        List<Proc> sorted = new ArrayList<>(procs);
        Collections.sort(sorted, (a, b) -> Long.compare(b.rssKb, a.rssKb));
        Set<Integer> topMem = new HashSet<>();
        for (int i = 0; i < Math.min(40, sorted.size()); i++) topMem.add(sorted.get(i).pid);
        Set<String> lspPkgs = new HashSet<>();
        LspModules.Result lr = LspModules.get();
        for (LspModules.Module m : lr.modules) lspPkgs.add(m.pkg);

        JSONArray arr = new JSONArray();
        for (Proc p : procs) {
            boolean keep = p.cpu >= 0.2 || p.ksu != null || topMem.contains(p.pid)
                    || (p.pkg != null && lspPkgs.contains(p.pkg)) || "system_server".equals(p.comm);
            if ("kernel".equals(p.kind)) keep = p.cpu >= 0.5;
            if (!keep) continue;
            JSONObject o = new JSONObject();
            o.put("pid", p.pid);
            o.put("ppid", p.ppid);
            o.put("uid", p.uid);
            o.put("name", shortName(p));
            o.put("cmd", p.cmdline.length() > 200 ? p.cmdline.substring(0, 200) : p.cmdline);
            o.put("exe", p.exe);
            o.put("pkg", p.pkg);
            o.put("kind", p.kind);
            o.put("cpu", round1(p.cpu));
            o.put("rss", p.rssKb);
            o.put("adj", p.adj);
            o.put("thr", p.threads);
            if (p.ksu != null) o.put("ksu", p.ksu);
            arr.put(o);
        }
        return arr;
    }

    private static String shortName(Proc p) {
        if ("kernel".equals(p.kind)) return p.comm;
        String c = p.cmdline;
        int sp = c.indexOf(' ');
        String first = sp > 0 ? c.substring(0, sp) : c;
        if ("app".equals(p.kind)) return first;
        // Script interpreter: display script name
        if ((first.endsWith("/sh") || first.equals("sh") || first.endsWith("busybox")) && sp > 0) return c.length() > 80 ? c.substring(0, 80) : c;
        int slash = first.lastIndexOf('/');
        return slash >= 0 ? first.substring(slash + 1) : first;
    }

    private JSONArray ksuJson() throws Exception {
        Map<String, double[]> agg = new HashMap<>(); // id -> {cpu, count, rss}
        Map<String, JSONArray> pids = new HashMap<>();
        for (Proc p : currentProcsForAgg) {
            if (p.ksu == null) continue;
            double[] a = agg.get(p.ksu);
            if (a == null) agg.put(p.ksu, a = new double[3]);
            a[0] += p.cpu;
            a[1] += 1;
            a[2] += p.rssKb;
            JSONArray l = pids.get(p.ksu);
            if (l == null) pids.put(p.ksu, l = new JSONArray());
            l.put(p.pid);
        }
        JSONArray arr = new JSONArray();
        for (KsuModules.Module m : KsuModules.get()) {
            JSONObject o = new JSONObject();
            o.put("id", m.id);
            o.put("name", m.name.isEmpty() ? m.id : m.name);
            o.put("version", m.version);
            o.put("author", m.author);
            o.put("desc", m.description);
            o.put("enabled", !m.disabled);
            o.put("remove", m.remove);
            o.put("service", m.hasService);
            o.put("postfs", m.hasPostFs);
            o.put("zygisk", m.hasZygisk);
            o.put("webroot", m.hasWebroot);
            o.put("sysprop", m.hasSystemProp);
            o.put("tags", new JSONArray(m.tags));
            double[] a = agg.get(m.id);
            o.put("cpu", a == null ? 0 : round1(a[0]));
            o.put("nproc", a == null ? 0 : (int) a[1]);
            o.put("rss", a == null ? 0 : (long) a[2]);
            o.put("pids", pids.containsKey(m.id) ? pids.get(m.id) : new JSONArray());
            arr.put(o);
        }
        return arr;
    }

    /** Process list of current snapshot, used by ksuJson aggregation. */
    private List<Proc> currentProcsForAgg = Collections.emptyList();

    private JSONObject lspJson() throws Exception {
        LspModules.Result r = LspModules.get();
        JSONObject o = new JSONObject();
        o.put("available", r.available);
        if (r.error != null) o.put("error", r.error);
        JSONArray arr = new JSONArray();
        for (LspModules.Module m : r.modules) {
            JSONObject mo = new JSONObject();
            mo.put("pkg", m.pkg);
            mo.put("enabled", m.enabled);
            JSONArray sc = new JSONArray();
            for (LspModules.Scope s : m.scopes) {
                JSONObject so = new JSONObject();
                so.put("pkg", s.pkg);
                so.put("user", s.user);
                sc.put(so);
            }
            mo.put("scope", sc);
            arr.put(mo);
        }
        o.put("modules", arr);
        return o;
    }

    private JSONObject hookJson() throws Exception {
        JSONObject o = new JSONObject();
        File f = new File("/data/system/thermalhunter/stats.json");
        if (!f.exists()) {
            o.put("present", false);
            return o;
        }
        String s = Sys.readAll(f.getAbsolutePath(), 2 << 20);
        if (s == null) {
            o.put("present", false);
            return o;
        }
        JSONObject j = new JSONObject(s);
        o.put("present", true);
        long now = SystemClock.elapsedRealtime();
        o.put("ageSec", (now - j.optLong("elapsed")) / 1000);
        o.put("elapsed", j.optLong("elapsed"));
        o.put("since", j.optLong("since"));
        o.put("pkgs", j.optJSONObject("pkgs"));
        return o;
    }

    // ------------------------------------------------------------------ Hardware Status

    private JSONArray cpuPolicies() throws Exception {
        JSONArray arr = new JSONArray();
        String base = "/sys/devices/system/cpu/cpufreq";
        String[] names = Sys.list(base);
        java.util.Arrays.sort(names);
        for (String n : names) {
            if (!n.startsWith("policy")) continue;
            String d = base + "/" + n;
            JSONObject o = new JSONObject();
            o.put("name", n);
            o.put("cpus", String.valueOf(Sys.readTrim(d + "/related_cpus")));
            o.put("cur", Sys.readLong(d + "/scaling_cur_freq", 0) / 1000);
            o.put("max", Sys.readLong(d + "/cpuinfo_max_freq", 0) / 1000);
            o.put("limit", Sys.readLong(d + "/scaling_max_freq", 0) / 1000);
            o.put("gov", String.valueOf(Sys.readTrim(d + "/scaling_governor")));
            arr.put(o);
        }
        return arr;
    }

    private JSONObject gpu() throws Exception {
        JSONObject o = new JSONObject();
        // MediaTek (Dimensity) GED
        String util = Sys.readTrim("/sys/kernel/ged/hal/gpu_utilization");
        if (util != null) o.put("util", Sys.firstNumber(util, -1));
        String freq = Sys.readTrim("/sys/kernel/ged/hal/current_freqency");
        if (freq != null) {
            long best = 0;
            for (String t : freq.split("\\s+")) {
                try {
                    best = Math.max(best, Long.parseLong(t));
                } catch (NumberFormatException ignored) {
                }
            }
            if (best > 0) o.put("mhz", best > 100000 ? best / 1000 : best);
        }
        // Generic devfreq (Mali)
        if (!o.has("mhz")) {
            for (String n : Sys.list("/sys/class/devfreq")) {
                String low = n.toLowerCase();
                if (low.contains("mali") || low.contains("gpu") || low.contains("kgsl")) {
                    long hz = Sys.readLong("/sys/class/devfreq/" + n + "/cur_freq", 0);
                    if (hz > 0) o.put("mhz", hz / 1_000_000);
                    break;
                }
            }
        }
        // Qualcomm
        if (!o.has("util")) {
            String b = Sys.readTrim("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
            if (b != null) o.put("util", Sys.firstNumber(b, -1));
        }
        return o;
    }

    private JSONObject battery() throws Exception {
        JSONObject o = new JSONObject();
        String d = "/sys/class/power_supply/battery";
        o.put("level", Sys.readLong(d + "/capacity", -1));
        String st = Sys.readTrim(d + "/status");
        o.put("status", st == null ? "" : st);
        long cur = Sys.readLong(d + "/current_now", 0);
        long volt = Sys.readLong(d + "/voltage_now", 0);
        // Adaptive units: µA/mA, µV/mV
        double mv = volt > 100000 ? volt / 1000.0 : volt;
        boolean discharging = "Discharging".equalsIgnoreCase(st) || "Not charging".equalsIgnoreCase(st);
        double ma = currentMa(cur, mv, discharging);
        o.put("currentMa", Math.round(ma));
        o.put("voltageMv", Math.round(mv));
        o.put("powerMw", Math.round(Math.abs(ma) * mv / 1000.0));
        long temp = Sys.readLong(d + "/temp", Long.MIN_VALUE);
        if (temp != Long.MIN_VALUE) o.put("tempC", temp / 10.0);
        return o;
    }

    /**
     * Interprets current_now raw reading as mA. Standard unit is µA; a few drivers report mA.
     * During discharge, power cannot reach tens of watts: interpreting as mA exceeding 30W must be µA
     * (fixes low µA currents of 1~20mA erroneously read as mA under old 20000 threshold);
     * During charging, high currents can occur; keeps conservative magnitude threshold.
     */
    private static double currentMa(long cur, double mv, boolean discharging) {
        if (Math.abs(cur) > 20000) return cur / 1000.0;
        if (discharging && Math.abs(cur) * mv / 1000.0 > 30000) return cur / 1000.0;
        return cur;
    }

    private JSONArray thermal() throws Exception {
        JSONArray arr = new JSONArray();
        String base = "/sys/class/thermal";
        for (String n : Sys.list(base)) {
            if (!n.startsWith("thermal_zone")) continue;
            String type = Sys.readTrim(base + "/" + n + "/type");
            long t = Sys.readLong(base + "/" + n + "/temp", Long.MIN_VALUE);
            if (type == null || t == Long.MIN_VALUE) continue;
            double c = Math.abs(t) > 1000 ? t / 1000.0 : t;
            if (!validTemp(c)) continue;
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("temp", round1(c));
            arr.put(o);
        }
        if (arr.length() == 0) thermalFromService(arr);
        return arr;
    }

    /** Filter invalid readings: disabled/inactive sensors on platforms like MediaTek often report placeholder values like 125°C or 127°C. */
    private static boolean validTemp(double c) {
        return c > -30 && c < 120;
    }

    private static final java.util.regex.Pattern THERMAL_RE = java.util.regex.Pattern.compile(
            "Temperature\\{mValue=([-0-9.]+), mType=(-?\\d+), mName=([^,}]+)");

    /**
     * Shell identity (Shizuku mode) is blocked by SELinux from reading /sys/class/thermal;
     * read temperature reported by thermal HAL from thermalservice instead. Type prefix helps Analyzer distinguish SoC / device.
     */
    private void thermalFromService(JSONArray arr) throws Exception {
        Object[] r = Sys.execMax(256 << 10, "dumpsys", "thermalservice");
        if ((int) r[0] != 0) return;
        java.util.regex.Matcher m = THERMAL_RE.matcher(String.valueOf(r[1]));
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String name = m.group(3).trim();
            if (!seen.add(name)) continue; // First occurrence is "current temperature" section, subsequent cached sections are skipped
            double t;
            int type;
            try {
                t = Double.parseDouble(m.group(1));
                type = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!validTemp(t)) continue;
            // Temperature.TYPE_CPU=0, GPU=1, BATTERY=2, SKIN=3
            String prefix = type == 0 ? "cpu" : type == 1 ? "gpu" : type == 2 ? "battery" : type == 3 ? "skin" : "";
            JSONObject o = new JSONObject();
            o.put("type", prefix.isEmpty() ? name : prefix + ":" + name);
            o.put("temp", round1(t));
            arr.put(o);
        }
    }

    private JSONArray kernelWakeups() throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (wakeupCache != null && now - wakeupTime < WAKEUP_REFRESH_MS) return wakeupCache;
        wakeupTime = now;
        wakeupCache = scanWakeups();
        return wakeupCache;
    }

    private JSONArray scanWakeups() throws Exception {
        JSONArray arr = new JSONArray();
        String base = "/sys/class/wakeup";
        List<Object[]> rows = new ArrayList<>();
        for (String n : Sys.list(base)) {
            String d = base + "/" + n;
            String name = Sys.readTrim(d + "/name");
            if (name == null) continue;
            long total = Sys.readLong(d + "/total_time_ms", 0);
            long count = Sys.readLong(d + "/active_count", 0);
            long active = Sys.readLong(d + "/active_time_ms", 0);
            long[] prev = prevWakeup.get(name);
            long dt = prev == null ? 0 : total - prev[0];
            long dc = prev == null ? 0 : count - prev[1];
            prevWakeup.put(name, new long[]{total, count});
            if (dt > 0 || active > 0) rows.add(new Object[]{name, dt + active, dc, active, total});
        }
        Collections.sort(rows, (a, b) -> Long.compare((long) b[1], (long) a[1]));
        for (int i = 0; i < Math.min(15, rows.size()); i++) {
            Object[] r = rows.get(i);
            JSONObject o = new JSONObject();
            o.put("name", r[0]);
            o.put("dMs", r[1]);
            o.put("dCount", r[2]);
            o.put("activeMs", r[3]);
            o.put("totalMs", r[4]);
            arr.put(o);
        }
        // Kernel wakelocks held by user-space via /sys/power/wake_lock (common in module scripts)
        String wl = Sys.readTrim("/sys/power/wake_lock");
        if (wl != null && !wl.isEmpty()) {
            for (String n : wl.split("\\s+")) {
                JSONObject o = new JSONObject();
                o.put("name", n);
                o.put("user", true);
                arr.put(o);
            }
        }
        return arr;
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
