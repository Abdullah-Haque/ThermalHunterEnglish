package com.thermalhunter.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 新 libxposed API 入口（LSPosed 2.x）。LSPosed 2.x 把 assets/xposed_init + manifest
 * meta-data 的 legacy 声明标记为「已弃用功能」，本类 + META-INF/xposed/ 声明文件让
 * 模块被识别为新式模块，警告消除；同时保留 legacy 声明与 HookEntry 以兼容旧框架。
 * hook 实现完全复用 HookEntry.install（XposedBridge/XposedHelpers 在 LSPosed 2.x
 * 运行时仍被完整支持，逻辑零改动）。
 *
 * 注意：system_server 在新 API 里不会触发 onPackageLoaded（系统包由
 * onSystemServerStarting 单独回调，且包名标识为 "system" 而非 "android"），
 * 故必须同时覆盖两个回调。
 */
public final class ThermalHunterModule extends XposedModule {

    private static final String TAG = "ThermalHunter";

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            HookEntry.install("android", param.getClassLoader());
        } catch (Throwable t) {
            Log.e(TAG, "system-server install failed", t);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            // 新式路径下系统包标识为 "system"；旧式为 "android"——统一交给 install 判断
            String pkg = param.getPackageName();
            if ("system".equals(pkg)) pkg = "android";
            HookEntry.install(pkg, param.getDefaultClassLoader());
        } catch (Throwable t) {
            Log.e(TAG, "new-api entry install failed", t);
        }
    }
}
