package com.thermalhunter.root;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans /data/adb/modules (common directory structure for KernelSU / Magisk / APatch).
 * Results cached for 30s; process attribution is determined by Scanner based on exe / cmdline / overlaid files / parent process chains.
 */
final class KsuModules {
    static final String MODULES_DIR = "/data/adb/modules";

    static final class Module {
        String id, name = "", version = "", author = "", description = "";
        boolean disabled, remove, update, skipMount;
        boolean hasService, hasPostFs, hasBootCompleted, hasZygisk, hasSystemProp, hasWebroot, hasSepolicy;
        final Set<String> tags = new LinkedHashSet<>();
    }

    private static List<Module> cache = new ArrayList<>();
    /** Module files overlaid into system partitions (/system/bin/xxx) -> module id */
    private static Map<String, String> overlay = new HashMap<>();
    private static long cacheTime;

    static synchronized List<Module> get() {
        long now = System.currentTimeMillis();
        if (now - cacheTime > 30_000 || cache.isEmpty()) {
            rescan();
            cacheTime = now;
        }
        return cache;
    }

    static synchronized Map<String, String> overlay() {
        get();
        return overlay;
    }

    static synchronized void invalidate() {
        cacheTime = 0;
    }

    static String rootImpl() {
        if (Sys.exists("/data/adb/ksud") || Sys.exists("/data/adb/ksu")) return "KernelSU";
        if (Sys.exists("/data/adb/ap") || Sys.exists("/data/adb/apd")) return "APatch";
        if (Sys.exists("/data/adb/magisk")) return "Magisk";
        return "unknown";
    }

    private static void rescan() {
        List<Module> list = new ArrayList<>();
        Map<String, String> ov = new HashMap<>();
        for (String id : Sys.list(MODULES_DIR)) {
            String base = MODULES_DIR + "/" + id;
            if (!new File(base).isDirectory()) continue;
            Module m = new Module();
            m.id = id;
            String prop = Sys.read(base + "/module.prop");
            if (prop != null) {
                for (String line : prop.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    switch (k) {
                        case "name": m.name = v; break;
                        case "version": m.version = v; break;
                        case "author": m.author = v; break;
                        case "description": m.description = v; break;
                    }
                }
            }
            m.disabled = Sys.exists(base + "/disable");
            m.remove = Sys.exists(base + "/remove");
            m.update = Sys.exists(base + "/update");
            m.skipMount = Sys.exists(base + "/skip_mount");
            m.hasService = Sys.exists(base + "/service.sh");
            m.hasPostFs = Sys.exists(base + "/post-fs-data.sh");
            m.hasBootCompleted = Sys.exists(base + "/boot-completed.sh");
            m.hasZygisk = new File(base + "/zygisk").isDirectory();
            m.hasSystemProp = Sys.exists(base + "/system.prop");
            m.hasWebroot = new File(base + "/webroot").isDirectory();
            m.hasSepolicy = Sys.exists(base + "/sepolicy.rule");

            // Overlay file map + thermal config replacement detection
            for (String part : new String[]{"system", "vendor", "product", "system_ext", "odm"}) {
                File dir = new File(base, part);
                if (!dir.isDirectory()) continue;
                String mountPrefix = part.equals("system") ? "/system" : "/" + part;
                for (String rel : Sys.walk(dir, 3000, 8)) {
                    String target = mountPrefix + rel;
                    // Module system/vendor/xx actually mounted to /vendor/xx
                    if (target.startsWith("/system/vendor/")) target = target.substring(7);
                    if (target.startsWith("/system/product/")) target = target.substring(7);
                    if (target.startsWith("/system/system_ext/")) target = target.substring(7);
                    if (!m.disabled && !m.skipMount) ov.put(target, id);
                    String low = rel.toLowerCase();
                    if (low.contains("thermal") || low.contains("thermal-engine") || low.contains("mtk_thermal")
                            || low.endsWith(".mtc") || low.contains("/thermal/")) {
                        m.tags.add("Replaces thermal config");
                    }
                    if (low.contains("powerhal") || low.contains("perfmgr") || low.contains("powerscntbl")
                            || low.contains("perf_config") || low.contains("fpsgo")) {
                        m.tags.add("Modifies performance/scheduler config");
                    }
                }
            }
            // Script keyword heuristics
            StringBuilder scripts = new StringBuilder();
            for (String s : new String[]{"service.sh", "post-fs-data.sh", "boot-completed.sh", "post-mount.sh", "system.prop"}) {
                String c = Sys.readAll(base + "/" + s, 256 * 1024);
                if (c != null) scripts.append(c).append('\n');
            }
            String sc = scripts.toString();
            if (sc.contains("thermal") || sc.contains("/sys/class/thermal") || sc.contains("thermal_core")) m.tags.add("Script manipulates thermal");
            if (sc.contains("scaling_min_freq") || sc.contains("scaling_governor") || sc.contains("cpu_boost")
                    || sc.contains("ged_kpi") || sc.contains("/proc/ppm") || sc.contains("cpuset") || sc.contains("uclamp")) {
                m.tags.add("Script modifies scheduler/frequency");
            }
            if ((sc.contains("while true") || sc.contains("while :") || sc.contains("while [ 1 ]") || sc.contains("until false"))
                    && sc.contains("sleep")) {
                m.tags.add("Persistent looping script");
            }
            if (sc.contains("/sys/power/wake_lock")) m.tags.add("Holds kernel wakelock");
            if (m.hasZygisk) m.tags.add("Zygisk injected into all apps");
            list.add(m);
        }
        cache = list;
        overlay = ov;
    }

    static Module find(String id) {
        for (Module m : get()) if (m.id.equals(id)) return m;
        return null;
    }
}
