package com.thermalhunter.core;

import android.content.pm.PackageManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku (ADB / Wireless Debugging) mode: starts process as shell (uid 2000) using Shizuku service.
 * Shell permissions are less than root: cannot read /data/adb or LSPosed config DB, and cannot kill other apps' processes.
 */
public final class ShizukuShell {
    private ShizukuShell() {}

    public static final int REQUEST_CODE = 0x5A1;

    /** Reason why Shizuku service is unavailable; returns null when available. */
    public static String unavailableReason() {
        try {
            if (!Shizuku.pingBinder()) {
                return "Shizuku is not running: Please install Shizuku and start the service via Wireless Debugging";
            }
            if (Shizuku.isPreV11()) return "Shizuku version is too old, please update to v11 or later";
            return null;
        } catch (Throwable e) {
            return "Shizuku unavailable: " + e;
        }
    }

    public static boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** User chose "Deny and don't ask again"; permission can only be granted manually in Shizuku Manager. */
    public static boolean permanentlyDenied() {
        try {
            return Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Show Shizuku permission dialog (called on main thread). */
    public static void request() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Launch process as shell. Shizuku API 13 made newProcess private (recommends UserService),
     * but here only a stdin/stdout pipe is needed; reusing the same line protocol as su is simplest, hence invoked via reflection.
     */
    static Process newProcess(String... cmd) throws IOException {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, cmd, null, null);
        } catch (InvocationTargetException e) {
            throw new IOException(String.valueOf(e.getCause()), e.getCause());
        } catch (Throwable e) {
            throw new IOException("Shizuku newProcess unavailable: " + e, e);
        }
    }
}
