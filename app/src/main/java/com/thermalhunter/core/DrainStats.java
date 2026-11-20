package com.thermalhunter.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Long-term battery drain stats: parses system batterystats (cumulative data since last full charge) and identifies anomalies.
 *
 * Short-term power readings only reflect the immediate moment and cannot detect apps that "constantly drain battery in the background";
 * the system continuously records each uid's power consumption (mAh), wakelocks, wakeup alarms, location, CPU time,
 * as well as screen-off drain and deep sleep status. Reading it does not require this tool to run sampling daemons in the background,
 * thereby consuming no additional power.
 *
 * Data source: {@code dumpsys batterystats -c} (checkin format, comma-separated, stable field order).
 * Note that {@code --checkin} must NOT be used: it reads and deletes the previous cycle file saved by the system for Google checkin,
 * returning old cycle data at that point.
 *
 * Shared between root side (RootMain DRAIN command) and App side (background periodic checks).
 */
public final class DrainStats {

    public static final int OK = 0, WARN = 1, BAD = 2;

    private static final long MIN = 60_000L, HOUR = 3_600_000L;

    /** batterystats checkin tag -> display name for battery components. */
    private static final Map<String, String> COMPONENTS = new HashMap<>();

    static {
        String[] kv = {
                "scrn", "Screen", "cpu", "CPU", "blue", "Bluetooth", "camera", "Camera", "cam", "Camera",
                "audio", "Audio", "video", "Video", "flashlight", "Flashlight", "flash", "Flashlight",
                "cell", "Mobile Network", "radio", "Mobile Network", "sensors", "Sensors", "gnss", "Location",
                "gps", "Location", "wifi", "Wi-Fi", "memory", "Memory", "phone", "Phone Calls", "ambi", "Ambient Display",
                "idle", "Standby", "wakelock", "Wakelock", "user", "Other Users", "unacc", "Unaccounted", "over", "Over-counted",
        };
        for (int i = 0; i < kv.length; i += 2) COMPONENTS.put(kv[i], kv[i + 1]);
    }

    private static final class Uid {
        final int uid;
        double mah, screenMah;
        long cpuMs, wlMs, bgWlMs, alarms, topMs, fgsMs, fgMs, bgMs, cachedMs, gpsMs;
        final Map<String, Long> wakelocks = new HashMap<>();
        final Map<String, Long> alarmTags = new HashMap<>();

        Uid(int uid) {
            this.uid = uid;
        }
    }

    private DrainStats() {
    }

    // ------------------------------------------------------------------ Parsing

    /** Stream-parses output of {@code dumpsys batterystats -c}, skipping large history lines. */
    public static JSONObject parse(BufferedReader in) throws IOException, JSONException {
        Map<Integer, Uid> uids = new HashMap<>();
        Map<Integer, List<String>> pkgs = new HashMap<>();
        Map<String, Double> components = new HashMap<>();
        List<Object[]> kernelWl = new ArrayList<>(), wakeReasons = new ArrayList<>();
        long[] bt = null, dc = null;
        double capacity = 0, consumed = 0;
        long screenOnMs = -1;
        boolean any = false;

        String line;
        while ((line = in.readLine()) != null) {
            // History lines "9,h,...", and other non-data lines
            if (line.length() < 8 || line.startsWith("9,h,") || !line.startsWith("9,")) continue;
            String[] a = line.split(",", -1);
            if (a.length < 5) continue;
            String cat = a[2], type = a[3];
            if (cat.equals("i")) {
                if (type.equals("uid") && a.length >= 6) {
                    int uid = toInt(a[4]);
                    List<String> l = pkgs.get(uid);
                    if (l == null) pkgs.put(uid, l = new ArrayList<>());
                    if (!l.contains(a[5])) l.add(a[5]);
                }
                continue;
            }
            if (!cat.equals("l")) continue;
            any = true;
            int uid = toInt(a[1]);
            switch (type) {
                case "bt":
                    bt = longs(a, 4, 9);
                    break;
                case "dc":
                    dc = longs(a, 4, 8);
                    break;
                case "m":
                    screenOnMs = toLong(a[4]);
                    break;
                case "pws":
                    if (a.length >= 6) {
                        capacity = toDouble(a[4]);
                        consumed = toDouble(a[5]);
                    }
                    break;
                case "pwi": {
                    if (a.length < 6) break;
                    double mah = toDouble(a[5]);
                    if (a[4].equals("uid")) {
                        Uid u = uid(uids, uid);
                        u.mah += mah;
                        if (a.length >= 8) u.screenMah += toDouble(a[7]);
                    } else if (uid == 0 && mah > 0) {
                        components.merge(a[4], mah, Double::sum);
                    }
                    break;
                }
                case "cpu":
                    if (a.length >= 6) {
                        Uid u = uid(uids, uid);
                        u.cpuMs += toLong(a[4]) + toLong(a[5]);
                    }
                    break;
                case "wl": {
                    // 9,uid,l,wl,<name>,<fMs>,f,<n>,..,<pMs>,p,<n>,..,<bpMs>,bp,<n>,..,<wMs>,w,..
                    long p = 0, bp = 0;
                    for (int i = 6; i < a.length; i++) {
                        if (a[i].equals("p")) p = toLong(a[i - 1]);
                        else if (a[i].equals("bp")) bp = toLong(a[i - 1]);
                    }
                    if (p <= 0 && bp <= 0) break;
                    Uid u = uid(uids, uid);
                    u.wlMs += p;
                    u.bgWlMs += bp;
                    u.wakelocks.merge(a[4], p, Long::sum);
                    break;
                }
                case "wua":
                    if (a.length >= 6) {
                        Uid u = uid(uids, uid);
                        long n = toLong(a[5]);
                        u.alarms += n;
                        if (n > 0) u.alarmTags.merge(a[4], n, Long::sum);
                    }
                    break;
                case "st":
                    // Process state durations: top, foreground service, foreground, background, top sleeping, heavy weight, cached
                    if (a.length >= 11) {
                        Uid u = uid(uids, uid);
                        u.topMs += toLong(a[4]);
                        u.fgsMs += toLong(a[5]);
                        u.fgMs += toLong(a[6]);
                        u.bgMs += toLong(a[7]);
                        u.cachedMs += toLong(a[10]);
                    }
                    break;
                case "sr":
                    // Sensor: handle -10000 is GPS; 6th field is cumulative duration
                    if (a.length >= 6 && a[4].equals("-10000")) uid(uids, uid).gpsMs += toLong(a[5]);
                    break;
                case "kwl":
                case "wr": {
                    Object[] nt = quotedNameTime(a);
                    if (nt != null && (long) nt[1] > 0) (type.equals("kwl") ? kernelWl : wakeReasons).add(nt);
                    break;
                }
                default:
                    break;
            }
        }

        JSONObject o = new JSONObject();
        if (!any || bt == null) {
            o.put("ok", false);
            o.put("error", "Cannot read batterystats data");
            return o;
        }
        o.put("ok", true);
        o.put("t", System.currentTimeMillis());
        long onBattery = bt[1];
        long screenOff = bt[6], screenOffAwake = bt[7];
        o.put("startClock", bt[5]);
        o.put("onBatteryMs", onBattery);
        o.put("awakeMs", bt[2]);
        o.put("screenOffMs", screenOff);
        o.put("screenOffAwakeMs", screenOffAwake);
        o.put("screenOnMs", screenOnMs >= 0 ? screenOnMs : Math.max(0, onBattery - screenOff));
        if (capacity <= 0 && bt.length > 8) capacity = bt[8];
        o.put("capacity", capacity);
        o.put("consumedMah", consumed);
        if (dc != null) {
            o.put("dischargePct", Math.max(dc[0], dc[1]));
            o.put("screenOnPct", dc[2]);
            o.put("screenOffPct", dc[3]);
            o.put("dischargeMah", dc[4]);
            o.put("screenOffMah", dc[5]);
        }

        // Component drain
        List<Map.Entry<String, Double>> comps = new ArrayList<>(components.entrySet());
        comps.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        JSONArray ca = new JSONArray();
        for (Map.Entry<String, Double> e : comps) {
            if (ca.length() >= 8) break;
            String name = COMPONENTS.get(e.getKey());
            ca.put(new JSONObject().put("name", name != null ? name : e.getKey()).put("mah", round1(e.getValue())));
        }
        o.put("components", ca);

        // Per-uid
        double uidTotal = 0;
        for (Uid u : uids.values()) uidTotal += Math.max(0, u.mah);
        o.put("uidMah", round1(uidTotal));
        List<Uid> list = new ArrayList<>(uids.values());
        list.sort((x, y) -> {
            int c = Double.compare(y.mah, x.mah);
            return c != 0 ? c : Long.compare(y.cpuMs + y.wlMs, x.cpuMs + x.wlMs);
        });
        JSONArray apps = new JSONArray();
        for (Uid u : list) {
            if (apps.length() >= 80) break;
            if (u.mah < 0.05 && u.wlMs < MIN && u.cpuMs < MIN && u.alarms < 10) continue;
            JSONObject j = new JSONObject();
            j.put("uid", u.uid);
            JSONArray pa = new JSONArray();
            List<String> pl = pkgs.get(u.uid);
            if (pl != null) for (int i = 0; i < pl.size() && i < 4; i++) pa.put(pl.get(i));
            j.put("pkgs", pa);
            j.put("mah", round1(u.mah));
            j.put("screenMah", round1(u.screenMah));
            j.put("cpuMs", u.cpuMs);
            j.put("wlMs", u.wlMs);
            j.put("bgWlMs", u.bgWlMs);
            j.put("alarms", u.alarms);
            j.put("topMs", u.topMs);
            j.put("fgsMs", u.fgsMs);
            j.put("fgMs", u.fgMs);
            j.put("bgMs", u.bgMs);
            j.put("cachedMs", u.cachedMs);
            j.put("gpsMs", u.gpsMs);
            j.put("wakelocks", topOf(u.wakelocks, 4, "ms"));
            j.put("alarmTags", topOf(u.alarmTags, 4, "n"));
            apps.put(j);
        }
        o.put("apps", apps);
        o.put("kernelWakelocks", topNameTime(kernelWl, 6));
        o.put("wakeReasons", topNameTime(wakeReasons, 6));
        analyze(o, null);
        return o;
    }

    // ------------------------------------------------------------------ Evaluation

    /**
     * Annotates anomalies on report: overall (standby drain rate, screen-off deep sleep) written to issues,
     * per-app written to level / reasons.
     * @param selfPkg Package name of this app (self is not evaluated); can be null
     */
    public static void analyze(JSONObject o, String selfPkg) throws JSONException {
        long onBattery = o.optLong("onBatteryMs");
        long screenOff = o.optLong("screenOffMs");
        long screenOn = o.optLong("screenOnMs");
        double cap = o.optDouble("capacity", 0);
        JSONArray issues = new JSONArray();

        // Standby (screen-off) drain rate: calculated via mAh / capacity preferentially, more accurate than integer percentages
        double offRate = rate(o.optDouble("screenOffMah", 0), o.optLong("screenOffPct", 0), cap, screenOff);
        double onRate = rate(o.optDouble("dischargeMah", 0) - o.optDouble("screenOffMah", 0),
                o.optLong("screenOnPct", 0), cap, screenOn);
        o.put("standbyRate", offRate);
        o.put("activeRate", onRate);
        double awake = screenOff > 0 ? o.optLong("screenOffAwakeMs") / (double) screenOff : -1;
        o.put("awakeRatio", awake);

        if (screenOff >= HOUR && offRate >= 0) {
            if (offRate >= 3) {
                issues.put(issue(BAD, "standby", String.format(Locale.US,
                        "Screen-off standby drain is %.1f%%/hr, significantly fast (normally under 1%%/hr)", offRate)));
            } else if (offRate >= 1.5) {
                issues.put(issue(WARN, "standby", String.format(Locale.US,
                        "Screen-off standby drain is %.1f%%/hr, fast (normally under 1%%/hr)", offRate)));
            }
        }
        if (screenOff >= HOUR && awake >= 0) {
            int pct = (int) Math.round(awake * 100);
            if (awake >= 0.4) {
                issues.put(issue(BAD, "awake", "CPU was unable to enter deep sleep for " + pct + "% of the time after screen-off, "
                        + "usually caused by app wakelocks or frequent wakeups"));
            } else if (awake >= 0.15) {
                issues.put(issue(WARN, "awake", "CPU was awake for " + pct + "% of the time after screen-off (ideally under 10%)"));
            }
        }

        double uidTotal = Math.max(1, o.optDouble("uidMah", 0));
        double hours = onBattery / (double) HOUR;
        JSONArray apps = o.optJSONArray("apps");
        int bad = 0, warn = 0;
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.getJSONObject(i);
                List<String> reasons = new ArrayList<>(), warns = new ArrayList<>();
                int uid = a.optInt("uid");
                JSONArray pk = a.optJSONArray("pkgs");
                boolean self = selfPkg != null && pk != null && pk.toString().contains("\"" + selfPkg + "\"");
                // Only evaluate regular apps; system uids (<10000) are displayed but not flagged as anomalous to avoid false positives
                if (uid % 100000 >= 10000 && !self && onBattery >= 30 * MIN) {
                    judgeApp(a, onBattery, hours, uidTotal, reasons, warns);
                }
                int level = !reasons.isEmpty() ? BAD : !warns.isEmpty() ? WARN : OK;
                if (level == BAD) bad++;
                else if (level == WARN) warn++;
                a.put("level", level);
                JSONArray r = new JSONArray();
                for (String s : reasons) r.put(s);
                a.put("reasons", r);
                JSONArray w = new JSONArray();
                for (String s : warns) w.put(s);
                a.put("warnings", w);
            }
        }
        o.put("badApps", bad);
        o.put("warnApps", warn);
        o.put("issues", issues);
    }

    private static void judgeApp(JSONObject a, long onBattery, double hours, double uidTotal,
                                 List<String> bad, List<String> warn) {
        long top = a.optLong("topMs");
        long fgs = a.optLong("fgsMs");
        double mah = a.optDouble("mah", 0);
        double share = mah / uidTotal;
        // Foreground services (music, navigation, recording, etc.) running in background are expected: max severity is WARN
        String fgsNote = fgs >= 30 * MIN ? " (Foreground service active for " + dur(fgs) + "; normal if playing music / navigating)" : "";

        // Background wakelock: prefer system-tracked background wakelock duration; if absent, minimal foreground time with long lock duration also counts
        long bgWl = a.optLong("bgWlMs");
        if (bgWl <= 0 && a.optLong("wlMs") > top * 2) bgWl = a.optLong("wlMs") - top;
        double wlFrac = bgWl / (double) onBattery;
        if (bgWl >= 20 * MIN && wlFrac >= 0.15) {
            if (fgs >= bgWl * 0.8) warn.add("Held background wakelock for " + dur(bgWl) + fgsNote);
            else bad.add("Held background wakelock for " + dur(bgWl) + " (" + Math.round(wlFrac * 100) + "% of discharge duration), preventing deep sleep");
        } else if (bgWl >= 10 * MIN && wlFrac >= 0.05) {
            warn.add("Held background wakelock for " + dur(bgWl) + " (" + Math.round(wlFrac * 100) + "% of discharge duration)");
        }

        long alarms = a.optLong("alarms");
        double perHour = hours > 0 ? alarms / hours : 0;
        if (alarms >= 30 && perHour >= 30) {
            bad.add(String.format(Locale.US, "Wakeup alarm %d times (%.0f times/hr), frequently waking phone from sleep", alarms, perHour));
        } else if (alarms >= 20 && perHour >= 10) {
            warn.add(String.format(Locale.US, "Wakeup alarm %d times (%.0f times/hr)", alarms, perHour));
        }

        long gps = a.optLong("gpsMs");
        if (gps >= 30 * MIN && gps > top * 2) {
            (gps >= 2 * HOUR ? bad : warn).add("Cumulative background location: " + dur(gps));
        }

        long cpu = a.optLong("cpuMs");
        if (cpu > top + fgs + 30 * MIN) {
            long bgCpu = cpu - top - fgs;
            (bgCpu >= 90 * MIN ? bad : warn).add("CPU ran for " + dur(cpu) + ", far exceeding foreground usage of " + dur(top) + "; mostly running in background");
        }

        if (top < 15 * MIN && mah >= 30 && share >= 0.08) {
            (fgsNote.isEmpty() ? bad : warn).add(String.format(Locale.US, "Barely used in foreground (%s), but consumed %.0f mAh (%d%% of total app drain)",
                    dur(top), mah, Math.round(share * 100)) + fgsNote);
        } else if (top < 15 * MIN && mah >= 15 && share >= 0.04) {
            warn.add(String.format(Locale.US, "Foreground usage only %s, background drain %.0f mAh (%d%%)",
                    dur(top), mah, Math.round(share * 100)));
        }
    }

    /** Drain percentage per hour; returns -1 when data is insufficient. */
    private static double rate(double mah, long pct, double cap, long ms) {
        if (ms < 10 * MIN) return -1;
        double h = ms / (double) HOUR;
        double p = cap > 0 && mah > 0 ? mah / cap * 100 : pct;
        if (p < 0) return -1;
        return Math.round(p / h * 100) / 100.0;
    }

    private static JSONObject issue(int level, String key, String msg) throws JSONException {
        return new JSONObject().put("level", level).put("key", key).put("msg", msg);
    }

    // ------------------------------------------------------------------ Utilities

    public static String dur(long ms) {
        long m = ms / MIN;
        if (m < 1) return (ms / 1000) + "s";
        if (m < 60) return m + "m";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private static Uid uid(Map<Integer, Uid> m, int uid) {
        Uid u = m.get(uid);
        if (u == null) m.put(uid, u = new Uid(uid));
        return u;
    }

    /** kwl / wr lines: name is quoted and may contain commas; followed by duration ms and count. */
    private static Object[] quotedNameTime(String[] a) {
        StringBuilder name = new StringBuilder();
        int i = 4;
        for (; i < a.length; i++) {
            if (name.length() > 0) name.append(',');
            name.append(a[i]);
            if (a[i].endsWith("\"") && name.length() > 1) break;
        }
        if (i + 2 >= a.length) return null;
        String n = name.toString();
        if (n.startsWith("\"")) n = n.substring(1);
        if (n.endsWith("\"")) n = n.substring(0, n.length() - 1);
        return new Object[]{n, toLong(a[i + 1]), toLong(a[i + 2])};
    }

    private static JSONArray topNameTime(List<Object[]> l, int max) throws JSONException {
        l.sort((x, y) -> Long.compare((long) y[1], (long) x[1]));
        JSONArray r = new JSONArray();
        for (int i = 0; i < l.size() && i < max; i++) {
            Object[] e = l.get(i);
            r.put(new JSONObject().put("name", e[0]).put("ms", e[1]).put("n", e[2]));
        }
        return r;
    }

    private static JSONArray topOf(Map<String, Long> m, int max, String key) throws JSONException {
        List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        l.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        JSONArray r = new JSONArray();
        for (int i = 0; i < l.size() && i < max; i++) {
            r.put(new JSONObject().put("name", l.get(i).getKey()).put(key, l.get(i).getValue()));
        }
        return r;
    }

    private static long[] longs(String[] a, int from, int minLen) {
        int n = Math.max(minLen, a.length - from);
        long[] r = new long[n];
        for (int i = 0; i < n && from + i < a.length; i++) r[i] = toLong(a[from + i]);
        return r;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static long toLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(s.trim());
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    private static double toDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
