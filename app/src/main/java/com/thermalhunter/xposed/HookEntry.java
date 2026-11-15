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
 * LSPosed 入口。
 * 作用域：
 *   - android (system_server)：统计每个应用的 PARTIAL 唤醒锁持有时长与唤醒型闹钟次数，
 *     周期性写入 /data/system/thermalhunter/stats.json，由 root 端读取。
 *   - com.thermalhunter：让 App 知道模块已被 LSPosed 激活。
 *
 * VPN / 代理隐身只在 system_server 层实现：按 /data/system/thermalhunter/vpnhide.json 名单，
 * 把交给目标应用的 NetworkCapabilities 抹掉 VPN 标志，并隐藏默认代理 / LinkProperties 里的 HttpProxy。
 * 用户只需在 App 里勾选一次，无需再到 LSPosed 里给每个应用加作用域。
 *
 * 原则：任何异常都吞掉，绝不能让 system_server 崩溃。
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
     * 安装入口（legacy 入口与新 libxposed 入口 ThermalHunterModule 共用）。
     * 新入口的存在只为消除 LSPosed 2.x 对 legacy 声明方式的「已弃用」警告；
     * hook 实现继续走久经实战验证的 XposedBridge/XposedHelpers（LSPosed 2.x 运行时仍完整支持）。
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
        // LSPosed 只会在 system_server 中以 "android" 回调
        if ("android".equals(packageName)) {
            Stats.start();
            hookWakeLocks(cl);
            hookAlarms(cl);
            hookConnectivityHide(cl);
            hookScreenshotUnlock(cl);
            return;
        }
        // 其余包（含 system_server 托管的 providers.settings / telecom 等）一律不装 hook：
        // VPN / 代理隐身统一由上面 system_server 层按名单完成，应用进程内不再注入。
        // 这样用户只需在 App 里勾选一次，无需逐个配置 LSPosed 作用域。
    }

    // ---------------------------------------------------------------- VPN 隐身

    private static final int TRANSPORT_VPN = 4;
    private static final int NET_CAPABILITY_NOT_VPN = 15;

    // ---------------------------------------------------------------- system_server 级 VPN 隐身

    /** 目标应用的 appId 集合（名单里包名解析出的 uid），周期性刷新。 */
    private static volatile java.util.Set<Integer> sVpnHideAppIds = java.util.Collections.emptySet();
    // 初值 -30s：开机头 30 秒 elapsedRealtime<30000，若初值 0 会误判「缓存未过期」而返回空名单，
    // 导致刚开机这段时间 system_server 端 VPN 隐身不生效。
    private static volatile long sVpnHideAt = -30_000L;

    /**
     * VPN 隐身总入口。Android 16 的 ConnectivityService 由 tethering mainline 模块的独立
     * ClassLoader 加载（service-connectivity.jar 不在 SYSTEMSERVERCLASSPATH），lpparam.classLoader
     * 找不到它；且开机早期 "connectivity" 服务可能尚未注册。故先尝试安装，失败则后台重试。
     */
    private static void hookConnectivityHide(ClassLoader cl) {
        if (installConnectivityHooks(cl)) return;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 24; i++) { // 每 5s 一次，最多 2 分钟
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

    /** 解析并安装 NC + 代理两组 hook；ConnectivityService 尚不可用时返回 false。 */
    private static boolean installConnectivityHooks(ClassLoader cl) {
        Class<?> cs = findConnectivityService(cl);
        if (cs == null) return false;
        hookConnectivityVpn(cs);
        hookProxyHide(cs);
        return true;
    }

    /**
     * 找 ConnectivityService 类：先用 system_server 的 ClassLoader；找不到再从
     * ServiceManager 里 "connectivity" binder 实例拿它真正的（mainline 模块）ClassLoader。
     */
    private static Class<?> findConnectivityService(ClassLoader cl) {
        Class<?> cs = XposedHelpers.findClassIfExists(
                "android.net.connectivity.com.android.server.ConnectivityService", cl);
        if (cs == null) cs = XposedHelpers.findClassIfExists("com.android.server.ConnectivityService", cl);
        if (cs != null) return cs;
        try {
            // ServiceManager 是 @hide，反射调用
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
     * 在 system_server 里 hook 「按调用方脱敏 NetworkCapabilities」的总闸：
     * 对隐身名单里的应用，把交给它的 NetworkCapabilities 克隆一份、抹掉 VPN 标志。
     * 拉取和回调两条路都经过此方法，native 调用方拿到的也是改过的 parcel。
     * 任何异常都吞掉，绝不能让 system_server 崩。
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
                                // 签名: (NetworkCapabilities, boolean includeLocationSensitiveInfo,
                                //        int callingPid, int callingUid, String pkg, String attributionTag)
                                // callingUid 是 args[3]（args[2] 是 pid）。回调路径也走这里，
                                // 此时 Binder.getCallingUid() 是 system_server 自己，必须用参数里的 uid。
                                Object uidArg = param.args[3];
                                if (!(uidArg instanceof Integer)) return;
                                int appId = ((Integer) uidArg) % 100000; // UserHandle.getAppId
                                if (!vpnHideAppIds().contains(appId)) return;
                                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(nc, "hasTransport", TRANSPORT_VPN))) {
                                    return;
                                }
                                // 克隆后改造，绝不动原对象
                                Object copy = nc.getClass().getConstructor(nc.getClass()).newInstance(nc);
                                XposedHelpers.callMethod(copy, "removeTransportType", TRANSPORT_VPN);
                                try {
                                    XposedHelpers.callMethod(copy, "addCapability", NET_CAPABILITY_NOT_VPN);
                                } catch (Throwable ignored) {
                                }
                                param.setResult(copy);
                            } catch (Throwable t) {
                                // 吞掉：绝不影响 system_server
                            }
                        }
                    });
            XposedBridge.log(TAG + "ConnectivityService VPN source-hide installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "CS hook failed: " + t);
        }
    }

    /**
     * VPN 的 LinkProperties 可能带 HttpProxy（VPN 应用设置的分流代理）。应用经
     * ConnectivityManager.getDefaultProxy() 拿到它——该 API 在本版本实际走 binder
     * getProxyForNetwork(Network)（getDefaultProxy 已不在 ConnectivityService 上）。
     * NetworkCapabilities 总闸拦不到这条路，故按调用方 uid 补一层：
     *   - getProxyForNetwork -> null
     *   - getLinkProperties / linkPropertiesRestrictedForCallerPermissions -> 克隆后抹掉 HttpProxy
     *     （后者是拉取 + NetworkCallback 回调两条路的共同出口，用参数里的 uid 区分）
     *   - 旧 API getNetworkInfo / getActiveNetworkInfo / getAllNetworkInfo 里的 VPN(17) -> WIFI(0)
     * binder 入口用 Binder.getCallingUid() 区分调用方；system_server 内部调用（uid 1000）
     * 不在名单里，不受影响。任何异常都吞掉。
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
        // LinkProperties 拉取路径（binder getLinkProperties）
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
                                // 克隆后改造，绝不动原对象
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
        // LinkProperties 回调路径：callCallbackForRequest 经此方法把 LP 交给 NetworkCallback，
        // 此时 Binder.getCallingUid() 是 system_server 自己，必须用参数里的 uid（args[2]）。
        // 签名: (LinkProperties, int callerPid, int callerUid)。拉取路径也会经过这里（幂等）。
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
        // 旧 API：NetworkInfo.getType() == TYPE_VPN(17) 也算 VPN，统一改成 WIFI(0)
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

    /** VPN 类型的 NetworkInfo 换成 WIFI，其余原样返回。 */
    private static android.net.NetworkInfo sanitizeNetworkInfo(android.net.NetworkInfo ni) {
        if (ni == null || ni.getType() != android.net.ConnectivityManager.TYPE_VPN) return ni;
        android.net.NetworkInfo fake = new android.net.NetworkInfo(
                android.net.ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");
        // getDetailedState/setDetailedState 是 @hide，走反射避免 hidden API 限制
        XposedHelpers.callMethod(fake, "setDetailedState",
                XposedHelpers.callMethod(ni, "getDetailedState"), null, ni.getExtraInfo());
        return fake;
    }

    /** 隐身名单：读 root 端写入的 /data/system/thermalhunter/vpnhide.json（字符串 JSON 数组）。
     *  该目录归 system 所有，system_server 可读。名单由 App 通过 root 写入，改动最迟 30 秒生效。 */
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

    /** 名单包名 → appId 集合；每 30 秒刷新一次。读 root 写入的名单 + /data/system/packages.list。 */
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

    // ---------------------------------------------------------------- 截图限制解除

    /** 截图解锁名单的 appId 集合（/data/system/thermalhunter/screenshot.json），30 秒缓存。 */
    private static volatile java.util.Set<Integer> sShotAppIds = java.util.Collections.emptySet();
    private static volatile long sShotAt = -30_000L;

    /**
     * system_server 层解除 FLAG_SECURE：WindowState.isSecureLocked() 是 surface 创建时
     * 打 SECURE 层标志与截屏/录屏安全校验的共同入口。对名单内应用的窗口，把「安全」
     * 判定改为 false（其余应用不受任何影响）。任何异常都吞掉。
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
            // 个别版本没有 isSecure，忽略
        }
    }

    /** 截图解锁名单：读 root 端写入的 /data/system/thermalhunter/screenshot.json（字符串 JSON 数组）。 */
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

    /** 名单包名 → appId 集合；每 30 秒刷新一次（与 vpnHideAppIds 相同机制）。 */
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
            // 按类型解析参数，兼容厂商/版本改动。
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
                                // displayId、flags 都在 tag 之前，flags 是 tag 前的最后一个 int
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
            // 进程死亡导致的释放
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
            long wlHeldMs;   // 已释放部分的累计时长
            long alarmWakeups;
            String lastTag;
        }

        private static final Object LOCK = new Object();
        private static final Map<IBinder, Held> HELD = new HashMap<>();
        private static final Map<String, PkgStat> PKGS = new HashMap<>();
        private static long startElapsed;
        private static boolean started;

        static void start() {
            // 同一进程可能被回调多次，只启动一次
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
                if (HELD.containsKey(lock)) return; // 更新已有锁，不重复计数
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
                // 清理死掉的 binder（防止漏掉释放导致时长无限增长）
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
