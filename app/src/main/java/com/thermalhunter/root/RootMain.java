package com.thermalhunter.root;

import android.system.Os;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for root daemon process, started by App via:
 *   su -c 'CLASSPATH=<apk> exec app_process /system/bin com.thermalhunter.root.RootMain'
 * Can also be started via Shizuku as shell (uid 2000), where module operations are disabled.
 * Protocol: stdin has one command per line; stdout outputs one JSON response per line prefixed with "@@".
 * Process automatically exits when stdin closes (App exits), preventing residual background battery drain.
 */
public final class RootMain {

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false);
        // Prevent framework code from printing noise to stdout and polluting protocol
        System.setOut(new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
            }
        }));
        Scanner scanner = new Scanner();
        Actions actions = new Actions(scanner);
        // Auto attribution only available under root (shell cannot sample other processes)
        autoProfiler = Os.getuid() == 0 ? new AutoProfiler(scanner) : null;
        if (autoProfiler != null) autoProfiler.start();
        out.println("@@READY");
        out.flush();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject resp;
                try {
                    resp = handle(line.split(" "), scanner, actions);
                } catch (Throwable e) {
                    resp = new JSONObject();
                    try {
                        resp.put("ok", false);
                        resp.put("error", String.valueOf(e));
                    } catch (Exception ignored) {
                    }
                }
                if (resp == null) break;
                out.println("@@" + resp);
                out.flush();
            }
        } catch (Throwable ignored) {
        }
        System.exit(0);
    }

    private static int intArg(String[] a, int i, int def) {
        try {
            return a.length > i ? Integer.parseInt(a[i]) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String strArg(String[] a, int i) {
        return a.length > i ? a[i] : "";
    }

    private static AutoProfiler autoProfiler;

    /** Read dumpsys batterystats -c and stream parse (output contains history and can be several MB, cannot be read whole). */
    private static JSONObject drain() throws Exception {
        Process p = new ProcessBuilder("dumpsys", "batterystats", "-c").redirectErrorStream(true).start();
        p.getOutputStream().close();
        JSONObject r;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            r = com.thermalhunter.core.DrainStats.parse(in);
        } finally {
            p.destroy();
        }
        return r;
    }

    /** Command prefixes that shell identity (Shizuku mode) lacks permission to execute. */
    private static boolean needsRoot(String cmd) {
        return cmd.startsWith("KSU_") || cmd.startsWith("LSP_") || cmd.equals("AUTOPROF");
    }

    private static JSONObject handle(String[] a, Scanner s, Actions act) throws Exception {
        if (Os.getuid() != 0 && needsRoot(a[0])) {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", "Not available in Shizuku mode (requires root)");
            return o;
        }
        switch (a[0]) {
            case "SNAPSHOT": {
                Display.hold();
                JSONObject snap = s.snapshot();
                JSONObject prof = autoProfiler == null ? null : autoProfiler.poll();
                if (prof != null) snap.put("prof", prof);
                return snap;
            }
            case "KILL":
                return act.kill(intArg(a, 1, -1), strArg(a, 2));
            case "FORCESTOP":
                return act.forceStop(strArg(a, 1), intArg(a, 2, 0));
            case "APP_DISABLE":
                return act.setAppEnabled(strArg(a, 1), intArg(a, 2, 0), false);
            case "APP_ENABLE":
                return act.setAppEnabled(strArg(a, 1), intArg(a, 2, 0), true);
            case "KSU_KILL":
                return act.killKsuProcs(strArg(a, 1));
            case "KSU_DISABLE":
                return act.setKsuEnabled(strArg(a, 1), false);
            case "KSU_ENABLE":
                return act.setKsuEnabled(strArg(a, 1), true);
            case "LSP_DISABLE":
                return act.setLspEnabled(strArg(a, 1), false);
            case "LSP_ENABLE":
                return act.setLspEnabled(strArg(a, 1), true);
            case "AUTOPROF": { // AUTOPROF <1|0>: toggle module auto attribution
                autoProfiler.setEnabled(intArg(a, 1, 1) == 1);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                return o;
            }
            case "DRAIN": // Long-term drain stats since last full charge (shell identity also has DUMP permission)
                return drain();
            case "REBOOT":
                return act.reboot();
            case "REFRESH_INFO":
                return Display.info();
            case "REFRESH_SET": // REFRESH_SET <fps>, 0=return to system
                return Display.set(intArg(a, 1, 0));
            case "PING": {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                return o;
            }
            case "EXIT":
                return null;
            default: {
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("error", "unknown command " + a[0]);
                return o;
            }
        }
    }
}
