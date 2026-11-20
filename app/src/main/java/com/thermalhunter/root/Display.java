package com.thermalhunter.root;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Screen refresh rate.
 * Some ROMs (such as vivo) ignore AOSP's min/peak_refresh_rate and enforce their own per-app throttling policies.
 * Therefore, we directly lock the panel mode via SurfaceFlinger's transaction 1035—bypassing system per-app throttling
 * and applying uniformly across all screens.
 * Note: this override is typically reset by system policies after screen off/on, reapplied as needed.
 */
final class Display {
    private Display() {}

    /** Currently locked SurfaceFlinger mode index; -1 indicates unlocked. Maintained by set(), periodically reapplied by hold(). */
    private static volatile int lockedIdx = -1;

    // DisplayMode{id=0, width=1260, height=2800, ... peakRefreshRate=144.00002 ...}
    private static final Pattern MODE = Pattern.compile(
            "DisplayMode\\{id=(\\d+), width=(\\d+), height=(\\d+),[^}]*?peakRefreshRate=([0-9.]+)");

    /** SurfaceFlinger modes under current resolution: rounded refresh rate -> mode index. */
    private static Map<Integer, Integer> sfModes() {
        String dump = String.valueOf(Sys.execMax(1 << 20, "dumpsys", "display")[1]);
        // Find panel's maximum resolution (current physical resolution)
        long bestRes = 0;
        Matcher m = MODE.matcher(dump);
        while (m.find()) {
            long res = Long.parseLong(m.group(2)) * Long.parseLong(m.group(3));
            if (res > bestRes) bestRes = res;
        }
        Map<Integer, Integer> map = new LinkedHashMap<>();
        m = MODE.matcher(dump);
        while (m.find()) {
            long res = Long.parseLong(m.group(2)) * Long.parseLong(m.group(3));
            if (res != bestRes) continue;
            int fps = Math.round(Float.parseFloat(m.group(4)));
            map.put(fps, Integer.parseInt(m.group(1)));
        }
        return map;
    }

    static JSONObject info() throws Exception {
        Map<Integer, Integer> modes = sfModes();
        List<Integer> rates = new ArrayList<>(modes.keySet());
        Collections.sort(rates);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("rates", new JSONArray(rates));
        o.put("current", currentFps());
        return o;
    }

    /** Locks a specific refresh rate (effective across all apps). fps<=0 returns control to system (unlocks). */
    static JSONObject set(int fps) throws Exception {
        JSONObject o = new JSONObject();
        if (fps <= 0) {
            lockedIdx = -1;
            // Return to system: clear AOSP overrides; SurfaceFlinger lock will automatically release after next screen off/on
            Sys.exec("settings", "delete", "system", "min_refresh_rate");
            Sys.exec("settings", "delete", "system", "peak_refresh_rate");
            o.put("ok", true);
            o.put("msg", "Returned control to system (lock releases after screen off/on)");
            o.put("current", currentFps());
            return o;
        }
        Map<Integer, Integer> modes = sfModes();
        Integer idx = modes.get(fps);
        if (idx == null) {
            o.put("ok", false);
            o.put("error", "This display does not support " + fps + "Hz");
            return o;
        }
        // AOSP hint + SurfaceFlinger enforcement
        Sys.exec("settings", "put", "system", "min_refresh_rate", String.valueOf(fps));
        Sys.exec("settings", "put", "system", "peak_refresh_rate", String.valueOf(fps));
        lockedIdx = idx;
        Object[] r = Sys.exec("service", "call", "SurfaceFlinger", "1035", "i32", String.valueOf(idx));
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        int got = currentFps();
        boolean ok = (int) r[0] == 0 && (got == fps || got < 0);
        o.put("ok", ok);
        if (ok) o.put("msg", "Locked to " + fps + "Hz (applies to all apps; maintained while ThermalHunter is running, released to system upon exit)");
        else o.put("error", "Setting did not take effect (currently " + got + "Hz); you may need to disable Smart Switch refresh rate in system settings");
        o.put("current", got);
        return o;
    }

    private static final Pattern DM_MODE = Pattern.compile("\\{id=(\\d+), width=\\d+, height=\\d+, fps=([0-9.]+)");

    /** Currently locked panel mode refresh rate (rounded). Reads mActiveModeId rather than renderFrameRate, which fluctuates with screen content. */
    /** Reapplies locked refresh rate (piggybacked on SNAPSHOT every 3s; no-op when unlocked, just a cheap binder call). */
    static void hold() {
        int idx = lockedIdx;
        if (idx < 0) return;
        try {
            Sys.exec("service", "call", "SurfaceFlinger", "1035", "i32", String.valueOf(idx));
        } catch (Throwable ignored) {
        }
    }

    private static int currentFps() {
        String dump = String.valueOf(Sys.execMax(1 << 20, "dumpsys", "display")[1]);
        Matcher a = Pattern.compile("mActiveModeId=(\\d+)").matcher(dump);
        if (!a.find()) return -1;
        String active = a.group(1);
        Matcher m = DM_MODE.matcher(dump);
        while (m.find()) {
            if (m.group(1).equals(active)) return Math.round(Float.parseFloat(m.group(2)));
        }
        return -1;
    }
}
