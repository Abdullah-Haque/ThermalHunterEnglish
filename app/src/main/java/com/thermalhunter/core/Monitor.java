package com.thermalhunter.core;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/**
 * Global sampler: main UI and floating overlay share the same background session (root or Shizuku) and Analyzer.
 * Samples only when subscribers exist (UI visible / floating window active and screen on);
 * closes background process after 60s without subscribers, so this tool itself does not continuously drain battery in background.
 */
public final class Monitor {

    public interface Listener {
        /** New analysis result (main thread). */
        void onUpdate(Analyzer.Result r);

        /** Connection / collection status (main thread). error=false indicates in-progress message. */
        void onStatus(String msg, boolean error);

        /** Whether fast refresh is needed (3s for main UI; relaxed to 5s for floating window only to save power). */
        default boolean wantsFast() {
            return true;
        }
    }

    public interface Done {
        void onDone(List<String> msgs, boolean allOk);
    }

    public interface Reply {
        void onReply(JSONObject r);
    }

    // Work mode: auto = try root first, fall back to Shizuku; root = root only; shizuku = Shizuku only (ADB)
    public static final String[] MODES = {"auto", "root", "shizuku"};

    private static final long INTERVAL_MS = 3000, SLOW_INTERVAL_MS = 5000, RETRY_MS = 5000, IDLE_CLOSE_MS = 60_000;
    private static Monitor instance;

    public static synchronized Monitor get(Context ctx) {
        if (instance == null) instance = new Monitor(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final RootSession root = new RootSession();
    private final Analyzer analyzer = new Analyzer();
    private final Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Analyzer.Result last;
    private volatile boolean rootFailed;   // root failed under auto mode; do not repeatedly request
    private volatile boolean shizukuAsked; // Shizuku permission dialog already prompted
    private final Runnable tickR = this::tick;
    private final Runnable idleClose = () -> {
        if (listeners.isEmpty()) root.close();
    };

    private Monitor(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("monitor");
        t.start();
        bg = new Handler(t.getLooper());
        // Reconnect immediately when Shizuku becomes ready or permission result returns, without waiting for retry interval
        Shizuku.addBinderReceivedListenerSticky(this::kick);
        Shizuku.addRequestPermissionResultListener((code, res) -> {
            if (code == ShizukuShell.REQUEST_CODE) kick();
        });
    }

    /** Called on main thread. Starts sampling when first subscriber joins. */
    public void addListener(Listener l) {
        if (listeners.contains(l)) return;
        boolean first = listeners.isEmpty();
        listeners.add(l);
        Analyzer.Result r = last;
        if (r != null) l.onUpdate(r);
        if (first) {
            shizukuAsked = false;
            bg.removeCallbacks(idleClose);
            kick();
        }
    }

    /** Called on main thread. Stops sampling when last subscriber leaves; closes background process after idling. */
    public void removeListener(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) {
            bg.removeCallbacks(tickR);
            bg.removeCallbacks(idleClose);
            bg.postDelayed(idleClose, IDLE_CLOSE_MS);
        }
    }

    public Analyzer.Result last() {
        return last;
    }

    /** Trigger a sample immediately. */
    public void kick() {
        if (listeners.isEmpty()) return;
        bg.removeCallbacks(tickR);
        bg.post(tickR);
    }

    /** Called after switching work mode: closes current session and reconnects according to new mode. */
    public void reconnect() {
        bg.post(() -> {
            rootFailed = false;
            shizukuAsked = false;
            root.close();
        });
        kick();
    }

    public boolean autoProfEnabled() {
        return prefs.getBoolean("auto_prof", true);
    }

    /** Toggle LSP module auto attribution (continuous low-frequency sampling). */
    public void setAutoProf(boolean on) {
        prefs.edit().putBoolean("auto_prof", on).apply();
        bg.post(() -> {
            if (root.isAlive()) root.safeCall("AUTOPROF " + (on ? 1 : 0));
        });
    }

    /** Execute commands sequentially, results returned to main thread. */
    public void runAll(List<String> cmds, Done done) {
        bg.post(() -> {
            List<String> msgs = new ArrayList<>();
            boolean allOk = true;
            for (String cmd : cmds) {
                JSONObject r = root.safeCall(cmd);
                boolean ok = r.optBoolean("ok");
                allOk &= ok;
                msgs.add(ok ? "✓ " + r.optString("msg") : "✗ " + r.optString("error"));
            }
            final boolean fOk = allOk;
            ui.post(() -> done.onDone(msgs, fOk));
            kick();
        });
    }

    /** Execute a command and return raw JSON result to main thread (used for time-consuming ops like deep analysis; real-time sampling is paused during execution). */
    public void call(String cmd, Reply reply) {
        bg.post(() -> {
            JSONObject r;
            String err = root.isAlive() ? null : connect();
            if (err != null) {
                r = new JSONObject();
                try {
                    r.put("ok", false);
                    r.put("error", err);
                } catch (Exception ignored) {
                }
            } else {
                r = root.safeCall(cmd);
            }
            final JSONObject fr = r;
            ui.post(() -> reply.onReply(fr));
            kick();
        });
    }

    private void status(String msg, boolean error) {
        ui.post(() -> {
            for (Listener l : listeners) l.onStatus(msg, error);
        });
    }

    private void tick() {
        if (listeners.isEmpty()) return;
        if (!root.isAlive()) {
            String err = connect();
            if (err != null) {
                status(err, true);
                bg.postDelayed(tickR, RETRY_MS);
                return;
            }
            // New session: sync LSP module auto attribution toggle (ignored if root side rejects in Shizuku mode)
            root.safeCall("AUTOPROF " + (autoProfEnabled() ? 1 : 0));
            // Re-establish refresh rate lock (lockedIdx was lost after previous session exited)
            int lockFps = prefs.getInt("lock_fps", 0);
            if (lockFps > 0) root.safeCall("REFRESH_SET " + lockFps);
        }
        JSONObject snap = root.safeCall("SNAPSHOT");
        if (snap.optBoolean("ok")) {
            fillBattery(snap);
            Analyzer.Result r = analyzer.update(snap);
            last = r;
            ui.post(() -> {
                for (Listener l : listeners) l.onUpdate(r);
            });
        } else {
            status("Collection failed: " + snap.optString("error"), true);
        }
        if (!listeners.isEmpty()) bg.postDelayed(tickR, interval());
    }

    private long interval() {
        for (Listener l : listeners) if (l.wantsFast()) return INTERVAL_MS;
        return SLOW_INTERVAL_MS;
    }

    /** Establishes background session based on work mode; returns null on success, user-facing error message on failure. Runs on bg thread. */
    private String connect() {
        String mode = prefs.getString("work_mode", "auto");
        String rootErr = null;
        if (mode.equals("root") || (mode.equals("auto") && !rootFailed)) {
            status("Acquiring root permission...", false);
            if (root.start(app, false)) return null;
            rootErr = root.lastError();
            if (mode.equals("root")) {
                return "Root not acquired: " + rootErr + "\nPlease grant Superuser permission to ThermalHunter in KernelSU Manager, then reopen.";
            }
            rootFailed = true;
        } else if (mode.equals("auto")) {
            rootErr = "Not granted";
        }
        String why = ShizukuShell.unavailableReason();
        if (why == null && !ShizukuShell.granted()) {
            if (ShizukuShell.permanentlyDenied()) {
                why = "Shizuku permission was denied: Please allow ThermalHunter in Shizuku → Authorized Applications";
            } else {
                if (!shizukuAsked) {
                    shizukuAsked = true;
                    ui.post(ShizukuShell::request);
                }
                why = "Please grant permission to ThermalHunter in the Shizuku dialog";
            }
        }
        if (why == null) {
            status("Starting via Shizuku...", false);
            if (root.start(app, true)) return null;
            why = "Shizuku failed to start collection process: " + root.lastError();
        }
        if (rootErr != null) return "Root not acquired: " + rootErr + "\n" + why;
        return why;
    }

    /**
     * Shell identity (Shizuku mode) cannot read /sys/class/power_supply;
     * use app's own BatteryManager to supply battery level, current, voltage, temperature, and charging status.
     */
    private void fillBattery(JSONObject snap) {
        JSONObject b = snap.optJSONObject("battery");
        if (b != null && b.optLong("level", -1) >= 0 && !b.optString("status").isEmpty()) return;
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent i = Build.VERSION.SDK_INT >= 33
                    ? app.registerReceiver(null, f, Context.RECEIVER_EXPORTED)
                    : app.registerReceiver(null, f);
            BatteryManager bm = app.getSystemService(BatteryManager.class);
            JSONObject o = new JSONObject();
            int level = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i == null ? 100 : i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            o.put("level", level >= 0 ? level * 100 / Math.max(1, scale)
                    : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            int st = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            o.put("status", st == BatteryManager.BATTERY_STATUS_CHARGING ? "Charging"
                    : st == BatteryManager.BATTERY_STATUS_DISCHARGING ? "Discharging"
                    : st == BatteryManager.BATTERY_STATUS_NOT_CHARGING ? "Not charging"
                    : st == BatteryManager.BATTERY_STATUS_FULL ? "Full" : "");
            int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW); // Typically µA
            int mv = i == null ? 0 : i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            boolean discharging = st == BatteryManager.BATTERY_STATUS_DISCHARGING
                    || st == BatteryManager.BATTERY_STATUS_NOT_CHARGING;
            double ma = cur == Integer.MIN_VALUE ? 0 : currentMa(cur, mv, discharging);
            o.put("currentMa", Math.round(ma));
            o.put("voltageMv", mv);
            o.put("powerMw", Math.round(Math.abs(ma) * mv / 1000.0));
            int t = i == null ? Integer.MIN_VALUE : i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) o.put("tempC", t / 10.0);
            snap.put("battery", o);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Interprets raw current reading as mA (same rule set as root-side Scanner.currentMa).
     * During discharge, if power interpreted as mA exceeds 30W, it must be µA; keeps conservative magnitude threshold during charging.
     */
    private static double currentMa(long cur, double mv, boolean discharging) {
        if (Math.abs(cur) > 20000) return cur / 1000.0;
        if (discharging && Math.abs(cur) * mv / 1000.0 > 30000) return cur / 1000.0;
        return cur;
    }
}
