package com.thermalhunter.xposed;

import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point.
 * Scope:
 *   - android (system_server): Tracks each app's PARTIAL wakelock duration and wakeup alarm count,
 *     periodically written to /data/system/thermalhunter/stats.json and read by root daemon.
 *   - com.thermalhunter: Notifies App that the module has been activated by LSPosed.
 *
 * VPN / proxy stealth implemented only at system_server layer: Based on /data/system/thermalhunter/vpnhide.json,
 * strips VPN flags from NetworkCapabilities delivered to target apps, and hides default proxy / HttpProxy in LinkProperties.
 * Users only need to check options in App once, without adding scopes in LSPosed for each app.
 *
 * Principle: Swallows all exceptions; never let system_server crash.
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "ThermalHunter: ";
    static final String STATS_DIR = "/data/system/thermalhunter";
    static final String STATS_FILE = STATS_DIR + "/stats.json";
    private static final int PARTIAL_WAKE_LOCK = 0x00000001;
    private static final int WAKE_LOCK_LEVEL_MASK = 0x0000ffff;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        install(lpparam.packageName, lpparam.classLoader);
    }

    /**
     * Installation entry point (shared by legacy entry and modern libxposed entry ThermalHunterModule).
     * The modern entry exists only to eliminate LSPosed 2.x deprecation warnings for legacy declarations;
     * hook implementation continues using battle-tested XposedBridge/XposedHelpers (fully supported at runtime in LSPosed 2.x).
     */
    static void install(String packageName, ClassLoader cl) {
        if ("com.thermalhunter".equals(packageName)) {
            try {
                XposedHelpers.findAndHookMethod("com.thermalhunter.core.ModuleStatus", cl,
                        "isActive", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {
                XposedBridge.log(TAG + "self hook failed: " + t);
            }
            return;
        }
        // LSPosed only callbacks with "android" in system_server
        if ("android".equals(packageName)) {
            Stats.start();
            hookWakeLocks(cl);
            hookAlarms(cl);
            hookConnectivityHide(cl);
            hookScreenshotUnlock(cl);
            return;
        }
        // All other packages (including providers.settings / telecom hosted by system_server) do not install hooks:
        // VPN / proxy stealth is handled globally at system_server level above based on list, avoiding in-app process injection.
        // Users only need to check options in App once without configuring per-app LSPosed scopes.
    }

    // ---------------------------------------------------------------- VPN Stealth

    private static final int TRANSPORT_VPN = 4;
    private static final int NET_CAPABILITY_NOT_VPN = 15;

    // ---------------------------------------------------------------- system_server Level VPN Stealth

    /** Target app appId set (uids resolved from package list), periodically refreshed. */
    private static volatile java.util.Set<Integer> sVpnHideAppIds = java.util.Collections.emptySet();
    // Initial value -30s: elapsedRealtime<30000 in first 30s of boot; if 0, "cache not expired" would be falsely assumed,
    // returning empty list and causing VPN stealth in system_server to fail during bootup.
    private static volatile long sVpnHideAt = -30_000L;

    /**
     * Main VPN stealth entry. In Android 16, ConnectivityService is loaded by tethering mainline module's
     * independent ClassLoader (service-connectivity.jar is not in SYSTEMSERVERCLASSPATH), which lpparam.classLoader
     * cannot find; and during early boot, "connectivity" service may not be registered yet. Attempts installation first,
     * retrying in background if unsuccessful.
     */
    private static void hookConnectivityHide(ClassLoader cl) {
        if (installConnectivityHooks(cl)) return;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 24; i++) { // Every 5s, up to 2 minutes
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (installConnectivityHooks(cl)) return;
            }
            XposedBridge.log(TAG + "ConnectivityService never appeared; VPN hide disabled");
        }, "th-vpnhide-retry");
        t.setDaemon(true);
        t.start();
    }

    /** Resolves and installs NC + proxy hooks; returns false if ConnectivityService is not yet available. */
    private static boolean installConnectivityHooks(ClassLoader cl) {
        Class<?> cs = findConnectivityService(cl);
        if (cs == null) return false;
        hookConnectivityVpn(cs);
        hookProxyHide(cs);
        return true;
    }

    /**
     * Finds ConnectivityService class: checks system_server's ClassLoader first;
     * if not found, retrieves its real (mainline module) ClassLoader from "connectivity" binder instance in ServiceManager.
     */
    private static Class<?> findConnectivityService(ClassLoader cl) {
        Class<?> cs = XposedHelpers.findClassIfExists(
                "android.net.connectivity.com.android.server.ConnectivityService", cl);
        if (cs == null) cs = XposedHelpers.findClassIfExists("com.android.server.ConnectivityService", cl);
        if (cs != null) return cs;
        try {
            // ServiceManager is @hide, invoked via reflection
            Object b = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "connectivity");
            if (b == null) return null;
            ClassLoader bcl = b.getClass().getClassLoader();
            cs = XposedHelpers.findClassIfExists(
                    "android.net.connectivity.com.android.server.ConnectivityService", bcl);
            if (cs == null) cs = XposedHelpers.findClassIfExists("com.android.server.ConnectivityService", bcl);
            if (cs == null) cs = b.getClass();
            return cs;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * In system_server, hooks the gateway for "sanitizing NetworkCapabilities per caller":
     * For apps in stealth list, clones NetworkCapabilities and strips VPN flags.
     * Both pull and callback paths traverse this method; native callers also receive the sanitized parcel.
     * Swallows all exceptions; never let system_server crash.
     */
    private static void hookConnectivityVpn(Class<?> cs) {
        try {
            XposedBridge.hookAllMethods(cs, "createWithLocationInfoSanitizedIfNecessaryWhenParceled",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object nc = param.getResult();
                                if (nc == null || param.args.length < 4) return;
                                // Signature: (NetworkCapabilities, boolean includeLocationSensitiveInfo,
                                //        int callingPid, int callingUid, String pkg, String attributionTag)
                                // callingUid is args[3] (args[2] is pid). Callback path also goes through here,
                                // where Binder.getCallingUid() is system_server itself, so the parameter uid must be used.
                                Object uidArg = param.args[3];
                                if (!(uidArg instanceof Integer)) return;
                                int appId = ((Integer) uidArg) % 100000; // UserHandle.getAppId
                                if (!vpnHideAppIds().contains(appId)) return;
                                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(nc, "hasTransport", TRANSPORT_VPN))) {
                                    return;
                                }
                                // Modify clone, never mutate original object
                                Object copy = nc.getClass().getConstructor(nc.getClass()).newInstance(nc);
                                XposedHelpers.callMethod(copy, "removeTransportType", TRANSPORT_VPN);
                                try {
                                    XposedHelpers.callMethod(copy, "addCapability", NET_CAPABILITY_NOT_VPN);
                                } catch (Throwable ignored) {
                                }
                                param.setResult(copy);
                            } catch (Throwable t) {
                                // Swallow: never affect system_server
                            }
                        }
                    });
            XposedBridge.log(TAG + "ConnectivityService VPN source-hide installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "CS hook failed: " + t);
        }
    }

    /**
     * LinkProperties of VPN may contain HttpProxy (proxy configured by VPN app). Apps retrieve it
     * via ConnectivityManager.getDefaultProxy() -- in this version, it actually goes through binder
     * getProxyForNetwork(Network) (getDefaultProxy is no longer on ConnectivityService).
     * The NetworkCapabilities gateway does not intercept this path, so we add hooks by caller uid:
     *   - getProxyForNetwork -> null
     *   - getLinkProperties / linkPropertiesRestrictedForCallerPermissions -> clones and removes HttpProxy
     *     (the latter is the common exit for pull + NetworkCallback paths, distinguished by uid parameter)
     *   - Legacy APIs getNetworkInfo / getActiveNetworkInfo / getAllNetworkInfo: VPN(17) -> WIFI(0)
     * Binder entry uses Binder.getCallingUid() to identify caller; internal system_server calls (uid 1000)
     * are not in list and remain unaffected. All exceptions are swallowed.
     */
    private static void hookProxyHide(Class<?> cs) {
        try {
            java.util.Set<XC_MethodHook.Unhook> uh = XposedBridge.hookAllMethods(cs, "getProxyForNetwork",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int appId = android.os.Binder.getCallingUid() % 100000;
                                if (vpnHideAppIds().contains(appId)) param.setResult(null);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + "proxy-hide getProxyForNetwork hooks=" + uh.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "proxy-hide getProxyForNetwork failed: " + t);
        }
        // LinkProperties pull path (binder getLinkProperties)
        try {
            java.util.Set<XC_MethodHook.Unhook> uh = XposedBridge.hookAllMethods(cs, "getLinkProperties",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object lp = param.getResult();
                                if (lp == null) return;
                                int appId = android.os.Binder.getCallingUid() % 100000;
                                if (!vpnHideAppIds().contains(appId)) return;
                                if (XposedHelpers.callMethod(lp, "getHttpProxy") == null) return;
                                // Modify clone, never mutate original object
                                Object copy = lp.getClass().getConstructor(lp.getClass()).newInstance(lp);
                                XposedHelpers.callMethod(copy, "setHttpProxy", new Object[]{null});
                                param.setResult(copy);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + "proxy-hide getLinkProperties hooks=" + uh.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "proxy-hide getLinkProperties failed: " + t);
        }
        // LinkProperties callback path: callCallbackForRequest delivers LP to NetworkCallback through this method,
        // where Binder.getCallingUid() is system_server itself, so uid in arguments (args[2]) must be used.
        // Signature: (LinkProperties, int callerPid, int callerUid). Pull path also passes through here (idempotent).
        try {
            java.util.Set<XC_MethodHook.Unhook> uh = XposedBridge.hookAllMethods(cs,
                    "linkPropertiesRestrictedForCallerPermissions",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object lp = param.getResult();
                                if (lp == null || param.args.length < 3) return;
                                Object uidArg = param.args[2];
                                if (!(uidArg instanceof Integer)) return;
                                int appId = ((Integer) uidArg) % 100000;
                                if (!vpnHideAppIds().contains(appId)) return;
                                if (XposedHelpers.callMethod(lp, "getHttpProxy") == null) return;
                                Object copy = lp.getClass().getConstructor(lp.getClass()).newInstance(lp);
                                XposedHelpers.callMethod(copy, "setHttpProxy", new Object[]{null});
                                param.setResult(copy);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + "proxy-hide linkPropertiesRestricted hooks=" + uh.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "proxy-hide linkPropertiesRestricted failed: " + t);
        }
        // Legacy APIs: NetworkInfo.getType() == TYPE_VPN(17) also counts as VPN, changed uniformly to WIFI(0)
        XC_MethodHook niHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    int appId = android.os.Binder.getCallingUid() % 100000;
                    if (!vpnHideAppIds().contains(appId)) return;
                    Object res = param.getResult();
                    if (res instanceof android.net.NetworkInfo[]) {
                        android.net.NetworkInfo[] arr = (android.net.NetworkInfo[]) res;
                        for (int i = 0; i < arr.length; i++) arr[i] = sanitizeNetworkInfo(arr[i]);
                    } else if (res instanceof android.net.NetworkInfo) {
                        param.setResult(sanitizeNetworkInfo((android.net.NetworkInfo) res));
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        for (String name : new String[]{"getNetworkInfo", "getActiveNetworkInfo", "getAllNetworkInfo"}) {
            try {
                java.util.Set<XC_MethodHook.Unhook> uh = XposedBridge.hookAllMethods(cs, name, niHook);
                XposedBridge.log(TAG + "proxy-hide " + name + " hooks=" + uh.size());
            } catch (Throwable t) {
                XposedBridge.log(TAG + "proxy-hide " + name + " failed: " + t);
            }
        }
    }

    /** Replaces VPN-type NetworkInfo with WIFI, returns others as-is. */
    private static android.net.NetworkInfo sanitizeNetworkInfo(android.net.NetworkInfo ni) {
        if (ni == null || ni.getType() != android.net.ConnectivityManager.TYPE_VPN) return ni;
        android.net.NetworkInfo fake = new android.net.NetworkInfo(
                android.net.ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");
        // getDetailedState/setDetailedState is @hide, invoked via reflection to bypass hidden API restrictions
        XposedHelpers.callMethod(fake, "setDetailedState",
                XposedHelpers.callMethod(ni, "getDetailedState"), null, ni.getExtraInfo());
        return fake;
    }

    /** Stealth list: reads /data/system/thermalhunter/vpnhide.json written by root daemon (JSON string array).
     *  Directory owned by system, readable by system_server. Written by App via root; changes take effect within 30s. */
    private static java.util.Set<String> vpnHidePkgs() {
        java.util.Set<String> pkgs = new java.util.HashSet<>();
        try {
            File f = new File(STATS_DIR, "vpnhide.json");
            if (!f.exists()) return pkgs;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "");
                if (!p.isEmpty()) pkgs.add(p);
            }
        } catch (Throwable ignored) {
        }
        return pkgs;
    }

    /** Package list -> appId set; refreshed every 30s. Reads root-written list + /data/system/packages.list. */
    private static java.util.Set<Integer> vpnHideAppIds() {
        long now = SystemClock.elapsedRealtime();
        if (now - sVpnHideAt < 30_000) return sVpnHideAppIds;
        sVpnHideAt = now;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        try {
            java.util.Set<String> pkgs = vpnHidePkgs();
            if (!pkgs.isEmpty()) {
                try (BufferedReader br = new BufferedReader(new FileReader("/data/system/packages.list"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        int sp = line.indexOf(' ');
                        if (sp <= 0) continue;
                        String pkg = line.substring(0, sp);
                        if (!pkgs.contains(pkg)) continue;
                        int sp2 = line.indexOf(' ', sp + 1);
                        if (sp2 <= sp) continue;
                        try {
                            ids.add(Integer.parseInt(line.substring(sp + 1, sp2)) % 100000);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sVpnHideAppIds = ids;
        return ids;
    }

    // ---------------------------------------------------------------- Screenshot Restriction Bypass

    /** AppId set for screenshot unlock list (/data/system/thermalhunter/screenshot.json), 30s cache. */
    private static volatile java.util.Set<Integer> sShotAppIds = java.util.Collections.emptySet();
    private static volatile long sShotAt = -30_000L;

    /**
     * Bypasses FLAG_SECURE at system_server layer: WindowState.isSecureLocked() is the common gateway for marking
     * SECURE layer flag upon surface creation and security checks for screenshot/screen recording.
     * For windows belonging to apps in the list, changes "secure" check to false (other apps unaffected). Swallows all exceptions.
     */
    private static void hookScreenshotUnlock(ClassLoader cl) {
        Class<?> ws = XposedHelpers.findClassIfExists("com.android.server.wm.WindowState", cl);
        if (ws == null) {
            XposedBridge.log(TAG + "screenshot-unlock: WindowState not found");
            return;
        }
        XC_MethodHook unlock = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!Boolean.TRUE.equals(param.getResult())) return;
                    Object win = param.thisObject;
                    Integer uid = null;
                    try {
                        uid = XposedHelpers.getIntField(win, "mUid");
                    } catch (Throwable ignored) {
                    }
                    if (uid == null) {
                        try {
                            uid = (Integer) XposedHelpers.callMethod(win, "getUid");
                        } catch (Throwable ignored) {
                        }
                    }
                    if (uid == null) return;
                    if (shotAppIds().contains(uid % 100000)) param.setResult(false);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            java.util.Set<XC_MethodHook.Unhook> uh = XposedBridge.hookAllMethods(ws, "isSecureLocked", unlock);
            XposedBridge.log(TAG + "screenshot-unlock isSecureLocked hooks=" + uh.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "screenshot-unlock isSecureLocked failed: " + t);
        }
        try {
            java.util.Set<XC_MethodHook.Unhook> uh = XposedBridge.hookAllMethods(ws, "isSecure", unlock);
            XposedBridge.log(TAG + "screenshot-unlock isSecure hooks=" + uh.size());
        } catch (Throwable t) {
            // Some versions lack isSecure, ignore
        }
    }

    /** Screenshot unlock list: reads /data/system/thermalhunter/screenshot.json written by root daemon (JSON string array). */
    private static java.util.Set<String> shotPkgs() {
        java.util.Set<String> pkgs = new java.util.HashSet<>();
        try {
            File f = new File(STATS_DIR, "screenshot.json");
            if (!f.exists()) return pkgs;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "");
                if (!p.isEmpty()) pkgs.add(p);
            }
        } catch (Throwable ignored) {
        }
        return pkgs;
    }

    /** Package list -> appId set; refreshed every 30s (same mechanism as vpnHideAppIds). */
    private static java.util.Set<Integer> shotAppIds() {
        long now = SystemClock.elapsedRealtime();
        if (now - sShotAt < 30_000) return sShotAppIds;
        sShotAt = now;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        try {
            java.util.Set<String> pkgs = shotPkgs();
            if (!pkgs.isEmpty()) {
                try (BufferedReader br = new BufferedReader(new FileReader("/data/system/packages.list"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        int sp = line.indexOf(' ');
                        if (sp <= 0) continue;
                        String pkg = line.substring(0, sp);
                        if (!pkgs.contains(pkg)) continue;
                        int sp2 = line.indexOf(' ', sp + 1);
                        if (sp2 <= sp) continue;
                        try {
                            ids.add(Integer.parseInt(line.substring(sp + 1, sp2)) % 100000);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sShotAppIds = ids;
        return ids;
    }

    // ---------------------------------------------------------------- wakelock

    private static void hookWakeLocks(ClassLoader cl) {
        try {
            Class<?> pms = XposedHelpers.findClass("com.android.server.power.PowerManagerService", cl);
            // Android 15: acquireWakeLockInternal(IBinder lock, int displayId, int flags, String tag,
            //     String packageName, WorkSource ws, String historyTag, int uid, int pid, IWakeLockCallback cb)
            // Parse arguments by type to maintain compatibility across vendor/version changes.
            XposedBridge.hookAllMethods(pms, "acquireWakeLockInternal", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.getThrowable() != null) return;
                        IBinder lock = null;
                        String tag = null, pkg = null;
                        int flags = -1;
                        for (Object a : param.args) {
                            if (a instanceof IBinder && lock == null) lock = (IBinder) a;
                            else if (a instanceof String) {
                                if (tag == null) tag = (String) a;
                                else if (pkg == null) pkg = (String) a;
                            } else if (a instanceof Integer && lock != null && tag == null) {
                                // displayId and flags are both before tag; flags is the last int before tag
                                flags = (Integer) a;
                            }
                        }
                        if (lock == null || pkg == null) return;
                        if (flags != -1 && (flags & WAKE_LOCK_LEVEL_MASK) != PARTIAL_WAKE_LOCK) return;
                        Stats.onAcquire(lock, pkg, tag);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.hookAllMethods(pms, "releaseWakeLockInternal", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        for (Object a : param.args) {
                            if (a instanceof IBinder) {
                                Stats.onRelease((IBinder) a);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            // Release triggered by process death
            XposedBridge.hookAllMethods(pms, "handleWakeLockDeath", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object wl = param.args[0];
                        Object lock = XposedHelpers.getObjectField(wl, "mLock");
                        if (lock instanceof IBinder) Stats.onRelease((IBinder) lock);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "wakelock hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "wakelock hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- alarm

    private static void hookAlarms(ClassLoader cl) {
        try {
            Class<?> ams = XposedHelpers.findClassIfExists("com.android.server.alarm.AlarmManagerService", cl);
            if (ams == null) ams = XposedHelpers.findClass("com.android.server.AlarmManagerService", cl);
            XposedBridge.hookAllMethods(ams, "deliverAlarmsLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object list = param.args[0];
                        if (!(list instanceof java.util.List)) return;
                        for (Object alarm : (java.util.List<?>) list) {
                            boolean wakeup;
                            try {
                                wakeup = XposedHelpers.getBooleanField(alarm, "wakeup");
                            } catch (Throwable e) {
                                wakeup = true;
                            }
                            if (!wakeup) continue;
                            String pkg = null;
                            try {
                                pkg = (String) XposedHelpers.getObjectField(alarm, "sourcePackage");
                            } catch (Throwable ignored) {
                            }
                            if (pkg == null) {
                                try {
                                    pkg = (String) XposedHelpers.getObjectField(alarm, "packageName");
                                } catch (Throwable ignored) {
                                }
                            }
                            if (pkg != null) Stats.onAlarm(pkg);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "alarm hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "alarm hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- stats store

    static final class Stats {
        static final class Held {
            final String pkg;
            final String tag;
            final long since;
            Held(String pkg, String tag, long since) { this.pkg = pkg; this.tag = tag; this.since = since; }
        }

        static final class PkgStat {
            long wlCount;
            long wlHeldMs;   // Cumulative duration of released portions
            long alarmWakeups;
            String lastTag;
        }

        private static final Object LOCK = new Object();
        private static final Map<IBinder, Held> HELD = new HashMap<>();
        private static final Map<String, PkgStat> PKGS = new HashMap<>();
        private static long startElapsed;
        private static boolean started;

        static void start() {
            // Same process may be called back multiple times; start only once
            synchronized (LOCK) {
                if (started) return;
                started = true;
                startElapsed = SystemClock.elapsedRealtime();
            }
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(15_000);
                        flush();
                    } catch (Throwable ignored) {
                    }
                }
            }, "ThermalHunterStats");
            t.setDaemon(true);
            t.start();
        }

        private static PkgStat get(String pkg) {
            PkgStat s = PKGS.get(pkg);
            if (s == null) {
                s = new PkgStat();
                PKGS.put(pkg, s);
            }
            return s;
        }

        static void onAcquire(IBinder lock, String pkg, String tag) {
            synchronized (LOCK) {
                if (HELD.containsKey(lock)) return; // Updating existing lock, do not double-count
                HELD.put(lock, new Held(pkg, tag, SystemClock.elapsedRealtime()));
                PkgStat s = get(pkg);
                s.wlCount++;
                s.lastTag = tag;
            }
        }

        static void onRelease(IBinder lock) {
            synchronized (LOCK) {
                Held h = HELD.remove(lock);
                if (h == null) return;
                get(h.pkg).wlHeldMs += SystemClock.elapsedRealtime() - h.since;
            }
        }

        static void onAlarm(String pkg) {
            synchronized (LOCK) {
                get(pkg).alarmWakeups++;
            }
        }

        static void flush() throws Exception {
            JSONObject root = new JSONObject();
            long now = SystemClock.elapsedRealtime();
            JSONObject pkgs = new JSONObject();
            synchronized (LOCK) {
                // Clean up dead binders (prevent missed releases causing duration to grow unbounded)
                Iterator<Map.Entry<IBinder, Held>> it = HELD.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<IBinder, Held> e = it.next();
                    if (!e.getKey().isBinderAlive()) {
                        get(e.getValue().pkg).wlHeldMs += now - e.getValue().since;
                        it.remove();
                    }
                }
                Map<String, long[]> active = new HashMap<>(); // pkg -> {activeCount, activeMs}
                Map<String, String> activeTag = new HashMap<>();
                for (Held h : HELD.values()) {
                    long[] a = active.get(h.pkg);
                    if (a == null) active.put(h.pkg, a = new long[2]);
                    a[0]++;
                    long dur = now - h.since;
                    if (dur > a[1]) activeTag.put(h.pkg, h.tag);
                    a[1] += dur;
                }
                for (Map.Entry<String, PkgStat> e : PKGS.entrySet()) {
                    PkgStat s = e.getValue();
                    long[] a = active.get(e.getKey());
                    JSONObject o = new JSONObject();
                    o.put("wlCount", s.wlCount);
                    o.put("wlHeldMs", s.wlHeldMs + (a == null ? 0 : a[1]));
                    o.put("wlActive", a == null ? 0 : a[0]);
                    o.put("alarms", s.alarmWakeups);
                    String tag = activeTag.get(e.getKey());
                    o.put("tag", tag != null ? tag : s.lastTag);
                    pkgs.put(e.getKey(), o);
                }
            }
            root.put("elapsed", now);
            root.put("since", startElapsed);
            root.put("pkgs", pkgs);

            File dir = new File(STATS_DIR);
            if (!dir.exists()) dir.mkdirs();
            File tmp = new File(STATS_DIR, "stats.json.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(new File(STATS_FILE));
        }
    }
}
