package com.thermalhunter.ui;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.SystemClock;

import com.thermalhunter.R;
import com.thermalhunter.core.DrainStats;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Background battery drain anomaly monitoring.
 *
 * Checks approximately once per hour using non-wakeful inexact alarm (ELAPSED_REALTIME + setInexactRepeating):
 * Triggers only when the phone is already awake, never waking the phone just to check. Each check reads
 * batterystats once via su (system's own continuous stats, no persistent sampling required by this tool),
 * and notifies when severe anomalies are detected; each anomaly is alerted only once per charging cycle.
 *
 * Also responsible for history recording: when batterystats is reset (unplugged after full charge starting a new cycle),
 * archives the previously saved report as a "discharge cycle".
 */
public final class DrainWatcher extends BroadcastReceiver {

    static final String ACTION_CHECK = "com.thermalhunter.action.DRAIN_CHECK";
    static final String PREF_ENABLED = "drain_watch";
    private static final String CHANNEL = "drain_alert";
    private static final int NOTIFY_ID = 2;
    private static final int MAX_HISTORY = 20;
    private static final long HOUR = 3_600_000L;

    // ------------------------------------------------------------------ Scheduling

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(PREF_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_ENABLED, on).apply();
        schedule(c);
    }

    /** Register/unregister hourly check based on switch. Safe to call repeatedly (same PendingIntent replaced). */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 0,
                new Intent(c, DrainWatcher.class).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!enabled(c)) return;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR, AlarmManager.INTERVAL_HOUR, pi);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        Context c = ctx.getApplicationContext();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            schedule(c);
            return;
        }
        if (!ACTION_CHECK.equals(a) || !enabled(c)) return;
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                check(c);
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        }, "drain-check").start();
    }

    private static void check(Context c) throws Exception {
        JSONObject rep = readViaSu();
        if (rep == null || !rep.optBoolean("ok")) return;
        DrainStats.analyze(rep, c.getPackageName());
        record(c, rep);
        if (!isCharging(c)) notifyIssues(c, rep);
    }

    /** No persistent session in background; read directly with one-off su; with timeout to avoid hanging broadcast. */
    private static JSONObject readViaSu() throws Exception {
        Process p = new ProcessBuilder("su", "-c", "dumpsys batterystats -c").redirectErrorStream(true).start();
        p.getOutputStream().close();
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(40_000);
                p.destroyForcibly();
            } catch (InterruptedException ignored) {
            }
        });
        killer.setDaemon(true);
        killer.start();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            return DrainStats.parse(in);
        } finally {
            killer.interrupt();
            p.destroy();
        }
    }

    private static boolean isCharging(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return false;
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
    }

    // ------------------------------------------------------------------ Alerts

    private static void notifyIssues(Context c, JSONObject rep) {
        long cycle = rep.optLong("startClock");
        SharedPreferences p = prefs(c);
        Set<String> done = p.getLong("drain_notified_cycle", -1) == cycle
                ? new HashSet<>(p.getStringSet("drain_notified", new HashSet<>())) : new HashSet<>();
        List<String> lines = new ArrayList<>();
        JSONArray issues = rep.optJSONArray("issues");
        for (int i = 0; issues != null && i < issues.length(); i++) {
            JSONObject o = issues.optJSONObject(i);
            if (o.optInt("level") != DrainStats.BAD) continue;
            if (done.add(o.optString("key"))) lines.add(o.optString("msg"));
        }
        JSONArray apps = rep.optJSONArray("apps");
        for (int i = 0; apps != null && i < apps.length(); i++) {
            JSONObject o = apps.optJSONObject(i);
            if (o.optInt("level") != DrainStats.BAD) continue;
            if (!done.add("uid:" + o.optInt("uid"))) continue;
            JSONArray r = o.optJSONArray("reasons");
            lines.add(label(c, o) + ": " + (r != null && r.length() > 0 ? r.optString(0) : "Background battery drain anomaly"));
        }
        p.edit().putLong("drain_notified_cycle", cycle).putStringSet("drain_notified", done).apply();
        if (lines.isEmpty()) return;

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Battery Drain Alert", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 2,
                new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_DRAIN)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = String.join("\n", lines);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(lines.size() == 1 ? "Battery drain anomaly detected" : lines.size() + " battery drain anomalies detected")
                .setContentText(lines.get(0))
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIFY_ID, n);
        } catch (SecurityException ignored) { // Notification permission not granted
        }
    }

    // ------------------------------------------------------------------ History

    /**
     * Save latest report; if batterystats has started a new cycle, archive previous report to history.
     * Also called each time UI reads report. Thread-safe (file I/O serialized).
     */
    static synchronized void record(Context c, JSONObject rep) {
        try {
            JSONObject last = readJson(new File(c.getFilesDir(), "drain_last.json"));
            if (last != null && isNewCycle(last, rep) && last.optLong("onBatteryMs") >= HOUR) {
                JSONArray hist = history(c);
                long start = last.optLong("startClock");
                boolean dup = false;
                for (int i = 0; i < hist.length(); i++) {
                    if (Math.abs(hist.optJSONObject(i).optLong("start") - start) < 30 * 60_000L) dup = true;
                }
                if (!dup) {
                    JSONArray out = new JSONArray();
                    out.put(summary(c, last));
                    for (int i = 0; i < hist.length() && out.length() < MAX_HISTORY; i++) out.put(hist.get(i));
                    writeJson(new File(c.getFilesDir(), "drain_history.json"), out.toString());
                }
            }
            writeJson(new File(c.getFilesDir(), "drain_last.json"), rep.toString());
        } catch (Throwable ignored) {
        }
    }

    /** Stats reset: discharge duration went backwards, or cycle start differs significantly. */
    private static boolean isNewCycle(JSONObject last, JSONObject rep) {
        return rep.optLong("onBatteryMs") < last.optLong("onBatteryMs") - 60_000L
                || Math.abs(rep.optLong("startClock") - last.optLong("startClock")) > 30 * 60_000L;
    }

    static JSONArray history(Context c) {
        try {
            String s = readText(new File(c.getFilesDir(), "drain_history.json"));
            if (s != null) return new JSONArray(s);
        } catch (Throwable ignored) {
        }
        return new JSONArray();
    }

    /** Most recently saved full report (UI displays it before connection is established). */
    static JSONObject lastReport(Context c) {
        return readJson(new File(c.getFilesDir(), "drain_last.json"));
    }

    private static JSONObject summary(Context c, JSONObject r) throws Exception {
        JSONObject s = new JSONObject();
        s.put("start", r.optLong("startClock"));
        s.put("end", r.optLong("t"));
        s.put("onBatteryMs", r.optLong("onBatteryMs"));
        s.put("screenOnMs", r.optLong("screenOnMs"));
        s.put("screenOffMs", r.optLong("screenOffMs"));
        s.put("dischargePct", r.optLong("dischargePct"));
        s.put("standbyRate", r.optDouble("standbyRate", -1));
        s.put("activeRate", r.optDouble("activeRate", -1));
        s.put("awakeRatio", r.optDouble("awakeRatio", -1));
        s.put("badApps", r.optInt("badApps"));
        JSONArray top = new JSONArray();
        JSONArray apps = r.optJSONArray("apps");
        for (int i = 0; apps != null && i < apps.length() && top.length() < 3; i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a.optInt("uid") % 100000 < 10000) continue;
            top.put(new JSONObject().put("name", label(c, a)).put("mah", a.optDouble("mah")));
        }
        s.put("top", top);
        return s;
    }

    // ------------------------------------------------------------------ Names

    /** uid → readable name: normal apps use app name (shared uid adds " etc."), system uid uses fixed name. */
    static String label(Context c, JSONObject app) {
        int uid = app.optInt("uid");
        int appId = uid % 100000;
        if (appId < 10000) {
            switch (appId) {
                case 0: return "Kernel & Root Processes";
                case 1000: return "Android System";
                case 1001: return "Phone Services";
                case 1002: return "Bluetooth";
                case 1010: return "Wi-Fi";
                case 1013: return "Media Services";
                case 1021: return "Location Services";
                case 1027: return "NFC";
                case 1041: return "Audio Services";
                case 1047: return "Camera Services";
                case 1066: return "Stats Services";
                case 2000: return "Shell";
                default: return "System Process (uid " + appId + ")";
            }
        }
        JSONArray pk = app.optJSONArray("pkgs");
        if (pk == null || pk.length() == 0) return "Uninstalled app (uid " + uid + ")";
        String first = pk.optString(0);
        String name = first;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(first, 0);
            name = pm.getApplicationLabel(ai).toString();
        } catch (Throwable ignored) {
        }
        return pk.length() > 1 ? name + " etc." : name;
    }

    // ------------------------------------------------------------------ Files

    private static JSONObject readJson(File f) {
        try {
            String s = readText(f);
            return s == null ? null : new JSONObject(s);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String readText(File f) throws Exception {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static void writeJson(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("rename failed");
    }
}
