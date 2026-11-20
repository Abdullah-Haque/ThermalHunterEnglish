package com.thermalhunter.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Hardened app compatibility fix (app side; consistent with VPN hide, executes root operations via one-off su channel).
 *
 * Background: Some hardened apps (SecNeo/Bangcle family, e.g. digital RMB dcep) crash immediately upon launch on rooted devices.
 * Disassembly and runtime experiments confirm that hardened self-checks read the content of /sys/fs/selinux/enforce—
 * Reading 1 (Enforcing) and detecting root signals triggers anti-tamper suicide (deliberate jump to an illegal address);
 * Reading 0 (Permissive) treats it as a test environment and allows execution.
 *
 * Fix: Bind-mount a shadow file containing '0' over /sys/fs/selinux/enforce,
 * allowing hardened apps to read 0 without crashing. Actual SELinux status and access control remain completely unchanged
 * (tools like getenforce are merely misled by the shadow file into displaying Permissive). Takes effect immediately,
 * no reboot required; mount is lost after reboot/soft reboot and needs re-enabling; umount cleanly restores in one step.
 *
 * Must be executed with su -M (mount-master, global mount namespace): regular app processes each hold
 * an independent mount namespace; default su only mounts into the current app's namespace, invisible to other apps.
 */
final class CompatFix {

    static final String SRC = "/data/local/tmp/thermalhunter_compat_enforce";
    static final String TARGET = "/sys/fs/selinux/enforce";

    /** Read back in one go: line 1 enforce file content (real file lacks newline, must be wrapped in echo), line 2 whether shadow file exists (1/0). */
    private static final String QUERY = "echo \"$(cat " + TARGET + " 2>/dev/null)\"; [ -f " + SRC + " ] && echo 1 || echo 0";

    /** Status: active = fix active (shadow exists and content is 0); value = enforce file current content. */
    static final class Status {
        final boolean active;
        final String value;
        Status(boolean active, String value) {
            this.active = active;
            this.value = value;
        }
    }

    interface Reply {
        /** When ok=false, msg contains error explanation (e.g. root required); st may be null. */
        void onResult(boolean ok, String msg, Status st);
    }

    /** Query fix status. */
    static void query(Reply cb) {
        runAsync(() -> {
            try {
                Status st = readStatus();
                deliver(cb, true, st.active ? "Enabled" : "Disabled", st);
            } catch (Throwable e) {
                deliver(cb, false, "Root (KSU) permission required", null);
            }
        });
    }

    /** enable=true mounts shadow; false umounts to restore (shadow file preserved as next mount source). Delivers latest status upon completion. */
    static void set(boolean enable, Reply cb) {
        runAsync(() -> {
            try {
                if (enable) {
                    su("printf '0\\n' > " + SRC + " && chmod 644 " + SRC
                            + " && mount --bind " + SRC + " " + TARGET);
                    Status st = readStatus();
                    if (st.active) {
                        deliver(cb, true, "Enabled: Hardened apps will read enforce=0 (actual SELinux state unchanged)", st);
                    } else {
                        deliver(cb, false, "Mount ineffective (read back enforce=" + st.value + ", may be intercepted by kernel hook)", st);
                    }
                } else {
                    // Double umount as fallback for stacked mounts; preserve shadow file (16 bytes, used as mount source on next run)
                    su("umount " + TARGET + " 2>/dev/null; umount " + TARGET + " 2>/dev/null");
                    Status st = readStatus();
                    if (!st.active) {
                        deliver(cb, true, "Restored: enforce file returned to actual state", st);
                    } else {
                        deliver(cb, false, "umount ineffective, please try again", st);
                    }
                }
            } catch (Throwable e) {
                deliver(cb, false, "Operation failed: " + e.getMessage(), null);
            }
        });
    }

    /** Menu card description text. */
    static String desc(Status st) {
        if (st == null) return "Checking status…";
        return st.active ? "Enabled · Shadow active (tap to manage)" : "Disabled · Tap to enable";
    }

    /** Active check: shadow file exists and enforce reads back 0. */
    private static Status readStatus() throws Exception {
        String[] p = su(QUERY).trim().split("\n");
        String value = p.length > 0 ? p[0].trim() : "";
        boolean src = p.length > 1 && "1".equals(p[1].trim());
        return new Status(src && "0".equals(value), value);
    }

    private static void deliver(Reply cb, boolean ok, String msg, Status st) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onResult(ok, msg, st));
    }

    private static void runAsync(Runnable r) {
        new Thread(r, "compat-fix").start();
    }

    /** Same one-off su channel as syncVpnHideConfig, but must use -M into global mount namespace; merge stderr to avoid pipe blockage. */
    private static String su(String cmd) throws Exception {
        Process pr = new ProcessBuilder("su", "-M", "-c", cmd)
                .redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader rd = new BufferedReader(
                new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = rd.readLine()) != null) sb.append(l).append('\n');
        }
        if (!pr.waitFor(10, TimeUnit.SECONDS)) {
            pr.destroyForcibly();
            throw new IllegalStateException("su timed out");
        }
        return sb.toString();
    }
}
