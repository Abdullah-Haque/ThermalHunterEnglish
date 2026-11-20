package com.thermalhunter.root;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Uses system built-in simpleperf to sample target processes by CPU time (cpu-clock),
 * and attributes CPU to specific injection sources based on call stacks:
 * LSPosed modules (key: package name), KernelSU / Zygisk modules with Java code (key: "ksu:moduleId", e.g. HMA-OSS),
 * LSPosed framework itself (key: FRAMEWORK, counted as "framework bridge").
 *
 * Attribution rules (verified on physical devices):
 * 1. Only consider non-system partition frames (code in /system, /apex, etc. is never counted as modules to prevent false positives);
 * 2. Prefer "strong evidence": frames whose class name is unobfuscated and belongs to only one source, picking the one closest to stack top;
 *    Obfuscated short class names (e.g. tk.a, mb) frequently collide across modules or even OEM system code, serving only as weak evidence;
 * 3. When no strong evidence exists, intersect weak evidence layer by layer; if multiple candidates remain, resolve using statistical co-occurrence:
 *    if an obfuscated class always co-occurs with a certain source in strong-evidence samples, attribute it to that source;
 *    only unresolvable cases are counted as "ambiguous".
 * Each sample's event_count is the CPU nanoseconds it represents, so the result directly equals CPU time consumed by each module.
 *
 * Limitation: Indirect overhead from hooks (e.g. hooked methods can no longer be inlined) cannot be tracked,
 * slightly underestimating module impact.
 */
final class Profiler {
    private Profiler() {}

    static final String SIMPLEPERF = "/system/bin/simpleperf";
    /** Source key for LSPosed framework code (grouped into "Framework Bridge"). */
    static final String FRAMEWORK = "framework";
    private static final String DATA = "/data/local/tmp/thermalhunter_perf.data";
    private static final String TEXT = "/data/local/tmp/thermalhunter_perf.txt";
    private static final String[] SYSTEM_PREFIXES = {"/system/", "/apex/", "/vendor/", "/product/", "/system_ext/", "/odm/"};
    private static final String[] BRIDGE_MARKS = {"de.robv.android.xposed.", "io.github.libxposed.", "org.lsposed.",
            "LSPHooker", "lsplant", "LSPlant"};

    /** apk path + last modified time -> class name set, avoiding re-parsing every time. */
    private static final Map<String, Set<String>> DEX_CACHE = new HashMap<>();

    /** Result of one sampling window. All values are CPU nanoseconds. */
    static final class Window {
        long wallNs, samples, javaSamples, totalNs, bridgeNs, ambiguousNs;
        final Map<String, Long> moduleNs = new HashMap<>();
        final Map<String, Map<String, Long>> moduleProcNs = new HashMap<>();   // module -> proc name -> ns
        final Map<String, Map<String, Long>> moduleMethodNs = new HashMap<>(); // module -> method -> ns
        /** Obfuscated class -> strong-evidence sources co-occurring on stack -> ns (used for second-pass disambiguation). */
        final Map<String, Map<String, Long>> learned = new HashMap<>();
        /** Samples ambiguous on first pass; disambiguation attempted after parsing finishes. */
        final List<Pending> pending = new ArrayList<>();
    }

    private static final class Pending {
        Set<String> cand;
        List<String> weak;
        String proc, hit;
        long ns;
    }

    static boolean available() {
        return new File(SIMPLEPERF).exists();
    }

    /**
     * Samples a window.
     * @param targets pid -> process name
     */
    static Window sample(Map<Integer, String> targets, int seconds, int freq) throws Exception {
        if (!available()) throw new Exception("System lacks built-in simpleperf (" + SIMPLEPERF + ")");
        StringBuilder pids = new StringBuilder();
        for (int pid : targets.keySet()) pids.append(pids.length() > 0 ? "," : "").append(pid);
        new File(DATA).delete();
        new File(TEXT).delete();
        long w0 = System.nanoTime();
        Object[] r = Sys.exec(SIMPLEPERF, "record", "-p", pids.toString(), "-e", "cpu-clock",
                "-f", String.valueOf(freq), "--call-graph", "dwarf,16384",
                "--duration", String.valueOf(seconds), "-o", DATA);
        if ((int) r[0] != 0 || !new File(DATA).exists()) {
            // Older versions may not support custom stack size, fall back to default DWARF call graph
            r = Sys.exec(SIMPLEPERF, "record", "-p", pids.toString(), "-e", "cpu-clock",
                    "-f", String.valueOf(freq), "-g", "--duration", String.valueOf(seconds), "-o", DATA);
        }
        long w1 = System.nanoTime();
        if ((int) r[0] != 0 || !new File(DATA).exists()) {
            throw new Exception("simpleperf sampling failed: " + tail(String.valueOf(r[1])));
        }
        // Thread -> parent process (report-sample only gives thread id)
        Map<Integer, String> tidToProc = new HashMap<>();
        for (Map.Entry<Integer, String> t : targets.entrySet()) {
            for (String tid : Sys.list("/proc/" + t.getKey() + "/task")) {
                try {
                    tidToProc.put(Integer.parseInt(tid), t.getValue());
                } catch (NumberFormatException ignored) {
                }
            }
            tidToProc.put(t.getKey(), t.getValue());
        }
        try {
            Object[] rep = Sys.execToFile(new File(TEXT), SIMPLEPERF, "report-sample", "-i", DATA, "--show-callchain");
            if ((int) rep[0] != 0 || !new File(TEXT).exists()) {
                throw new Exception("simpleperf parse failed: " + tail(String.valueOf(rep[1])));
            }
            Map<String, Set<String>> classToModules = classMap();
            Window w = new Window();
            w.wallNs = w1 - w0;
            parse(new File(TEXT), w, classToModules, tidToProc);
            return w;
        } finally {
            new File(DATA).delete();
            new File(TEXT).delete();
        }
    }

    /** "Class name -> source key" map for all injection sources. */
    private static Map<String, Set<String>> classMap() {
        Map<String, Set<String>> map = new HashMap<>();
        for (LspModules.Module m : LspModules.get().modules) {
            addClasses(map, moduleClasses(resolveApk(m.pkg, m.apkPath)), m.pkg);
        }
        // Java code included in KernelSU modules (Zygisk injection, e.g. HMA-OSS; LSPosed framework itself)
        for (String id : Sys.list(KsuModules.MODULES_DIR)) {
            String key = id.toLowerCase().contains("lsposed") ? FRAMEWORK : "ksu:" + id;
            for (String f : payloads(KsuModules.MODULES_DIR + "/" + id)) addClasses(map, moduleClasses(f), key);
        }
        return map;
    }

    private static void addClasses(Map<String, Set<String>> map, Set<String> classes, String key) {
        for (String cls : classes) {
            Set<String> s = map.get(cls);
            if (s == null) map.put(cls, s = new HashSet<>(2));
            s.add(key);
        }
    }

    /** .dex / .jar / .apk in module directory (within 2 levels, excluding system/ overlaid to system partitions). */
    private static List<String> payloads(String dir) {
        List<String> out = new ArrayList<>();
        for (String rel : Sys.walk(new File(dir), 200, 1)) {
            if (rel.startsWith("/system/") || rel.startsWith("/vendor/") || rel.startsWith("/product/")) continue;
            if (rel.endsWith(".dex") || rel.endsWith(".jar") || rel.endsWith(".apk")) out.add(dir + rel);
        }
        return out;
    }

    /** APK path in LSPosed database may become invalid after module update; searches under /data/app by package name. */
    static String resolveApk(String pkg, String dbPath) {
        if (dbPath != null && new File(dbPath).exists()) return dbPath;
        for (String a : Sys.list("/data/app")) {
            String base = "/data/app/" + a;
            if (a.startsWith(pkg + "-")) {
                if (new File(base + "/base.apk").exists()) return base + "/base.apk";
                continue;
            }
            for (String b : Sys.list(base)) {
                if (b.startsWith(pkg + "-") && new File(base + "/" + b + "/base.apk").exists()) {
                    return base + "/" + b + "/base.apk";
                }
            }
        }
        return dbPath;
    }

    private static void parse(File text, Window w, Map<String, Set<String>> classToModules,
                              Map<Integer, String> tidToProc) throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(text), 1 << 16)) {
            List<String> syms = new ArrayList<>(), files = new ArrayList<>();
            int tid = -1;
            long count = 0;
            boolean in = false;
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.equals("sample:")) {
                    if (in) add(w, syms, files, tidToProc.get(tid), count, classToModules);
                    syms.clear();
                    files.clear();
                    tid = -1;
                    count = 0;
                    in = true;
                } else if (t.startsWith("event_count:")) {
                    count = parseLong(t.substring(12));
                } else if (t.startsWith("thread_id:")) {
                    tid = (int) parseLong(t.substring(10));
                } else if (t.startsWith("file:")) {
                    files.add(t.substring(5).trim());
                } else if (t.startsWith("symbol:")) {
                    syms.add(t.substring(7).trim());
                }
            }
            if (in) add(w, syms, files, tidToProc.get(tid), count, classToModules);
        }
        resolvePending(w);
    }

    /** Second pass: disambiguate samples using learned "obfuscated class -> source" associations from strong evidence samples. */
    private static void resolvePending(Window w) {
        for (Pending p : w.pending) {
            String best = null;
            long bestScore = 0;
            boolean tie = false;
            for (String m : p.cand) {
                long score = 0;
                for (String cls : p.weak) {
                    Map<String, Long> lm = w.learned.get(cls);
                    Long v = lm == null ? null : lm.get(m);
                    if (v != null) score += v;
                }
                if (score > bestScore) {
                    best = m;
                    bestScore = score;
                    tie = false;
                } else if (score == bestScore && score > 0) {
                    tie = true;
                }
            }
            if (best != null && !tie) {
                credit(w, best, p.proc, p.hit, p.ns);
            } else {
                w.ambiguousNs += p.ns;
            }
        }
        w.pending.clear();
    }

    private static void credit(Window w, String m, String proc, String hit, long ns) {
        if (FRAMEWORK.equals(m)) {
            w.bridgeNs += ns;
            return;
        }
        inc(w.moduleNs, m, ns);
        inc(sub(w.moduleProcNs, m), proc, ns);
        inc(sub(w.moduleMethodNs, m), hit, ns);
    }

    private static void add(Window w, List<String> syms, List<String> files, String proc, long ns,
                            Map<String, Set<String>> classToModules) {
        if (ns <= 0) ns = 1;
        if (proc == null) proc = "?";
        w.samples++;
        w.totalNs += ns;
        Set<String> cand = null;
        String hit = null, strong = null;
        List<String> weak = new ArrayList<>();
        boolean java = false, bridgeNearLeaf = false;
        for (int i = 0; i < syms.size(); i++) {
            String sym = syms.get(i);
            String file = i < files.size() ? files.get(i) : "";
            String cls = classOf(sym);
            if (cls != null) java = true;
            if (cand == null && strong == null && i < 3 && isBridge(sym)) bridgeNearLeaf = true;
            if (cls == null || isSystemFile(file)) continue;
            Set<String> mods = classToModules.get(cls);
            if (mods == null) continue;
            if (hit == null) hit = methodOf(sym); // Injected code method closest to top of stack
            if (mods.size() == 1 && !obfuscated(cls)) {
                // Strong evidence: unobfuscated and unique, take closest to stack top (continue scan to collect weak evidence for learning)
                if (strong == null) strong = mods.iterator().next();
                continue;
            }
            weak.add(cls);
            if (cand == null) {
                cand = new HashSet<>(mods);
            } else {
                Set<String> inter = new HashSet<>(cand);
                inter.retainAll(mods);
                if (!inter.isEmpty()) cand = inter;
            }
        }
        if (java) w.javaSamples++;
        if (strong != null) {
            // Learning: these obfuscated classes co-occurred on stack with this source
            for (String cls : weak) inc(sub(w.learned, cls), strong, ns);
            credit(w, strong, proc, hit, ns);
        } else if (cand != null && cand.size() == 1) {
            credit(w, cand.iterator().next(), proc, hit, ns);
        } else if (cand != null) {
            Pending p = new Pending();
            p.cand = cand;
            p.weak = weak;
            p.proc = proc;
            p.hit = hit;
            p.ns = ns;
            w.pending.add(p);
        } else if (bridgeNearLeaf) {
            w.bridgeNs += ns;
        }
    }

    private static Map<String, Long> sub(Map<String, Map<String, Long>> m, String k) {
        Map<String, Long> s = m.get(k);
        if (s == null) m.put(k, s = new HashMap<>());
        return s;
    }

    /** Obfuscated class name: top-level class name and all package segments are <= 3 characters, e.g. tk.a, mb, fo$Transformer. */
    static boolean obfuscated(String cls) {
        String top = cls;
        int d = top.indexOf('$');
        if (d > 0) top = top.substring(0, d);
        for (String seg : top.split("\\.")) if (seg.length() > 3) return false;
        return true;
    }

    private static boolean isBridge(String sym) {
        for (String b : BRIDGE_MARKS) if (sym.contains(b)) return true;
        return false;
    }

    private static boolean isSystemFile(String f) {
        if (f.contains("kallsyms") || f.endsWith(".so")) return true;
        for (String p : SYSTEM_PREFIXES) if (f.startsWith(p)) return true;
        return false;
    }

    /** "void com.foo.Bar.baz(int)" / "com.foo.Bar.baz" -> "com.foo.Bar.baz"; returns null if not like a Java method. */
    static String methodOf(String sym) {
        int p = sym.indexOf('(');
        String s = p >= 0 ? sym.substring(0, p) : sym;
        int sp = s.lastIndexOf(' ');
        if (sp >= 0) s = s.substring(sp + 1);
        return s.indexOf('.') > 0 ? s : null;
    }

    static String classOf(String sym) {
        String m = methodOf(sym);
        if (m == null) return null;
        int dot = m.lastIndexOf('.');
        return dot > 0 ? m.substring(0, dot) : null;
    }

    // ------------------------------------------------------------------ dex class names

    /** Reads class names from all classesN.dex in APK (Java format, e.g. com.foo.Bar$1). */
    static Set<String> moduleClasses(String apk) {
        if (apk == null || apk.isEmpty()) return Collections.emptySet();
        File f = new File(apk);
        String key = apk + "@" + f.lastModified();
        synchronized (DEX_CACHE) {
            Set<String> cached = DEX_CACHE.get(key);
            if (cached != null) return cached;
        }
        Set<String> out = new HashSet<>();
        if (apk.endsWith(".dex")) {
            try (InputStream in = new java.io.FileInputStream(f)) {
                parseDex(readAll(in), out);
            } catch (Throwable ignored) {
            }
            synchronized (DEX_CACHE) {
                DEX_CACHE.put(key, out);
            }
            return out;
        }
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().matches("classes\\d*\\.dex")) continue;
                try (InputStream in = z.getInputStream(e)) {
                    parseDex(readAll(in), out);
                }
            }
        } catch (Throwable ignored) {
        }
        synchronized (DEX_CACHE) {
            DEX_CACHE.put(key, out);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toByteArray();
    }

    /** Only parses header -> class_defs -> type_ids -> string_ids, extracting descriptor for each class. */
    private static void parseDex(byte[] d, Set<String> out) {
        if (d.length < 0x70 || d[0] != 'd' || d[1] != 'e' || d[2] != 'x') return;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsOff = b.getInt(0x3C);
        int typeIdsOff = b.getInt(0x44);
        int classDefsSize = b.getInt(0x60), classDefsOff = b.getInt(0x64);
        for (int i = 0; i < classDefsSize; i++) {
            try {
                int typeIdx = b.getInt(classDefsOff + i * 32);
                int strIdx = b.getInt(typeIdsOff + typeIdx * 4);
                int off = b.getInt(stringIdsOff + strIdx * 4);
                while ((d[off] & 0x80) != 0) off++; // Skip uleb128 (UTF-16 length)
                off++;
                int end = off;
                while (end < d.length && d[end] != 0) end++;
                String desc = new String(d, off, end - off, StandardCharsets.UTF_8);
                if (desc.length() > 2 && desc.charAt(0) == 'L' && desc.endsWith(";")) {
                    out.add(desc.substring(1, desc.length() - 1).replace('/', '.'));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ Utilities

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void inc(Map<String, Long> m, String k, long v) {
        Long old = m.get(k);
        m.put(k, old == null ? v : old + v);
    }

    static String tail(String s) {
        s = s.trim();
        return s.length() > 400 ? "…" + s.substring(s.length() - 400) : s;
    }
}
