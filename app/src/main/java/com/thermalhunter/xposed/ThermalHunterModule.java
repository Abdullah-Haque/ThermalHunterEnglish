package com.thermalhunter.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Modern libxposed API entry point (LSPosed 2.x). LSPosed 2.x marks legacy declarations
 * in assets/xposed_init + manifest meta-data as "deprecated features". This class + META-INF/xposed/
 * declaration files identify the module as modern, eliminating warnings; legacy declarations and HookEntry
 * are preserved for compatibility with older frameworks.
 * The hook implementation completely reuses HookEntry.install (XposedBridge/XposedHelpers remain fully supported at runtime in LSPosed 2.x, zero logic changes).
 *
 * Note: system_server does not trigger onPackageLoaded in the new API (system package is called back separately via onSystemServerStarting,
 * with package identifier "system" rather than "android"), so both callbacks must be overridden.
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
            // System package identified as "system" under new path; legacy is "android" -- unified in install
            String pkg = param.getPackageName();
            if ("system".equals(pkg)) pkg = "android";
            HookEntry.install(pkg, param.getDefaultClassLoader());
        } catch (Throwable t) {
            Log.e(TAG, "new-api entry install failed", t);
        }
    }
}
