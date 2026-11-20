package com.thermalhunter.root;

import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** All destructive operations are centralized here with a protection list to prevent accidental kills causing freezes/reboots/loss of root. */
final class Actions {

    static final java.util.Set<String> PROTECTED_PROCS = com.thermalhunter.core.Protect.PROCS;
    static final java.util.Set<String> PROTECTED_PKGS = com.thermalhunter.core.Protect.PKGS;

    private final Scanner scanner;

    Actions(Scanner scanner) {
        this.scanner = scanner;
    }

    private static JSONObject ok(String msg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("msg", msg);
        return o;
    }

    private static JSONObject fail(String msg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", msg);
        return o;
    }

    private static String procName(int pid) {
        String cl = Sys.read("/proc/" + pid + "/cmdline");
        if (cl != null && !cl.isEmpty()) {
            String first = cl.split("\0")[0];
            return first;
        }
        String stat = Sys.read("/proc/" + pid + "/stat");
        if (stat == null) return null;
        int lp = stat.indexOf('('), rp = stat.lastIndexOf(')');
        return lp >= 0 && rp > lp ? stat.substring(lp + 1, rp) : "";
    }

    static boolean isProtectedProc(int pid) {
        if (pid <= 2 || pid == Os.getpid()) return true;
        String stat = Sys.read("/proc/" + pid + "/stat");
        if (stat != null) {
            int rp = stat.lastIndexOf(')');
            String[] f = stat.substring(rp + 2).split(" ");
            if ("2".equals(f[1])) return true; // Kernel thread
            int lp = stat.indexOf('(');
            if (PROTECTED_PROCS.contains(stat.substring(lp + 1, rp))) return true;
        }
        String name = procName(pid);
        if (name == null) return false;
        String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
        if (PROTECTED_PROCS.contains(base) || PROTECTED_PROCS.contains(name)) return true;
        int colon = name.indexOf(':');
        String pkg = colon > 0 ? name.substring(0, colon) : name;
        return PROTECTED_PKGS.contains(pkg);
    }

    /** KILL <pid> [expected proc name]: verifies proc name to prevent accidental kills from pid reuse. */
    JSONObject kill(int pid, String expect) throws Exception {
        String name = procName(pid);
        if (name == null) return ok("Process no longer exists");
        if (expect != null && !expect.isEmpty() && !name.startsWith(expect) && !name.endsWith(expect)
                && !expect.startsWith(name)) {
            return fail("pid " + pid + " changed to " + name + ", cancelled");
        }
        if (isProtectedProc(pid)) return fail(name + " is a protected critical system process");
        try {
            Os.kill(pid, 9);
            return ok("Killed " + name + " (" + pid + ")");
        } catch (Throwable e) {
            if (Os.getuid() != 0) return fail("Shizuku mode lacks permission to kill " + name + " (requires root)");
            return fail("kill failed: " + e);
        }
    }

    JSONObject forceStop(String pkg, int user) throws Exception {
        if (!Sys.isSafeToken(pkg)) return fail("Invalid package name");
        if (PROTECTED_PKGS.contains(pkg)) return fail(pkg + " is protected, cannot force-stop");
        Object[] r = Sys.exec("am", "force-stop", "--user", String.valueOf(user), pkg);
        return (int) r[0] == 0 ? ok("Force-stopped " + pkg) : fail("force-stop failed: " + r[1]);
    }

    JSONObject setAppEnabled(String pkg, int user, boolean enable) throws Exception {
        if (!Sys.isSafeToken(pkg)) return fail("Invalid package name");
        if (!enable && PROTECTED_PKGS.contains(pkg)) return fail(pkg + " is protected, cannot disable");
        Object[] r = enable
                ? Sys.exec("pm", "enable", "--user", String.valueOf(user), pkg)
                : Sys.exec("pm", "disable-user", "--user", String.valueOf(user), pkg);
        return (int) r[0] == 0 ? ok((enable ? "Enabled " : "Disabled (frozen) ") + pkg) : fail(String.valueOf(r[1]));
    }

    /** Kill all processes belonging to a given KSU module. */
    JSONObject killKsuProcs(String id) throws Exception {
        if (!Sys.isSafeToken(id)) return fail("Invalid module id");
        List<Integer> targets = new ArrayList<>();
        synchronized (scanner.lastProcs) {
            for (Scanner.Proc p : scanner.lastProcs) if (id.equals(p.ksu)) targets.add(p.pid);
        }
        int killed = 0;
        JSONArray skipped = new JSONArray();
        // SIGKILL parent process (script loop) and children together to prevent children from being respawned
        for (int pid : targets) {
            if (isProtectedProc(pid)) {
                skipped.put(pid);
                continue;
            }
            try {
                Os.kill(pid, 9);
                killed++;
            } catch (Throwable ignored) {
            }
        }
        JSONObject o = ok("Module " + id + ": killed " + killed + " processes");
        o.put("skipped", skipped);
        return o;
    }

    JSONObject setKsuEnabled(String id, boolean enable) throws Exception {
        if (!Sys.isSafeToken(id)) return fail("Invalid module id");
        String dir = KsuModules.MODULES_DIR + "/" + id;
        if (!new File(dir).isDirectory()) return fail("Module does not exist: " + id);
        String ksud = Sys.exists("/data/adb/ksud") ? "/data/adb/ksud" : null;
        boolean done = false;
        String detail = "";
        if (ksud != null) {
            Object[] r = Sys.exec(ksud, "module", enable ? "enable" : "disable", id);
            done = (int) r[0] == 0;
            detail = String.valueOf(r[1]);
        }
        if (!done) {
            File flag = new File(dir, "disable");
            try {
                if (enable) done = !flag.exists() || flag.delete();
                else done = flag.exists() || flag.createNewFile();
            } catch (Throwable e) {
                detail = String.valueOf(e);
            }
        }
        KsuModules.invalidate();
        if (!done) return fail("Operation failed: " + detail);
        if (!enable) {
            JSONObject k = killKsuProcs(id);
            return ok("Disabled module " + id + " (takes full effect after reboot); " + k.optString("msg"));
        }
        return ok("Enabled module " + id + " (takes effect after reboot)");
    }

    JSONObject setLspEnabled(String pkg, boolean enable) throws Exception {
        if (!Sys.isSafeToken(pkg)) return fail("Invalid package name");
        String err = LspModules.setEnabled(pkg, enable);
        if (err != null) return fail(err);
        String msg = (enable ? "Enabled in LSPosed: " : "Disabled in LSPosed: ") + pkg + " (takes effect after reboot)";
        if (!enable && !PROTECTED_PKGS.contains(pkg)) {
            // Also terminate module's own process (if running background services)
            Sys.exec("am", "force-stop", pkg);
        }
        return ok(msg);
    }

    JSONObject reboot() throws Exception {
        Sys.exec("svc", "power", "reboot", "thermalhunter");
        Object[] r = Sys.exec("reboot");
        return (int) r[0] == 0 ? ok("Rebooting") : fail(String.valueOf(r[1]));
    }
}
