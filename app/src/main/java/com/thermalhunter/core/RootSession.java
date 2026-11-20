package com.thermalhunter.core;

import android.content.Context;

import org.json.JSONObject;

import rikka.shizuku.ShizukuRemoteProcess;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Launches a daemon process (running RootMain in this APK via app_process) via su (root mode)
 * or Shizuku (ADB mode, shell identity). All subsequent collections/actions are performed over
 * a stdin/stdout line protocol, avoiding repeatedly forking shells.
 * All calls must run on a background thread.
 */
public final class RootSession {
    /** Maximum wait time for a single request (including startup handshake); aborts session and forcibly kills background process on timeout to avoid blocking monitor thread permanently. */
    private static final long CALL_TIMEOUT_MS = 30_000;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private volatile String lastError = "";
    private volatile boolean viaShizuku;

    public synchronized boolean isAlive() {
        if (process == null) return false;
        if (process instanceof ShizukuRemoteProcess) {
            try {
                return ((ShizukuRemoteProcess) process).alive();
            } catch (RuntimeException e) {
                return false; // Shizuku service disconnected
            }
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** Whether the current session was established via Shizuku (shell permissions). */
    public boolean isShizuku() {
        return viaShizuku;
    }

    public String lastError() {
        return lastError;
    }

    /** @param shizuku true to start as shell via Shizuku, false to start as root via su */
    public synchronized boolean start(Context ctx, boolean shizuku) {
        close();
        viaShizuku = shizuku;
        lastError = "";
        String apk = ctx.getApplicationInfo().sourceDir;
        try {
            process = shizuku ? ShizukuShell.newProcess("sh") : new ProcessBuilder("su").start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            final Process p = process;
            Thread err = new Thread(() -> {
                try (BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    String l;
                    while ((l = er.readLine()) != null) lastError = l;
                } catch (IOException ignored) {
                }
            }, "root-stderr");
            err.setDaemon(true);
            err.start();

            writer.write("export CLASSPATH='" + apk.replace("'", "") + "'\n");
            writer.write("exec /system/bin/app_process /system/bin --nice-name=thermalhunter_root "
                    + "com.thermalhunter.root.RootMain\n");
            writer.flush();
            long deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS;
            String line;
            while ((line = readLineTimeout(deadline)) != null) {
                if (line.equals("@@READY")) return true;
            }
            if (lastError.isEmpty()) lastError = shizuku ? "Background process did not start"
                    : "su was rejected or unavailable (please grant Superuser permission to this app in KernelSU Manager)";
            close();
            return false;
        } catch (IOException e) {
            lastError = (shizuku ? "Failed to start via Shizuku: " : "Cannot execute su: ") + e.getMessage();
            close();
            return false;
        }
    }

    public synchronized JSONObject call(String cmd) throws IOException {
        if (writer == null) throw new IOException("Root process not started");
        writer.write(cmd);
        writer.write('\n');
        writer.flush();
        long deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS;
        String line;
        while ((line = readLineTimeout(deadline)) != null) {
            if (line.startsWith("@@")) {
                try {
                    return new JSONObject(line.substring(2));
                } catch (Exception e) {
                    throw new IOException("bad response: " + e.getMessage());
                }
            }
        }
        close();
        throw new IOException("Root process has exited " + lastError);
    }

    /**
     * Reads a line with timeout. Uses polling instead of blocking read: when the background process freezes,
     * forcibly killing the parent might not produce EOF (child process may still hold stdout pipe),
     * so the calling thread must be able to exit on its own.
     * Returns null and aborts session on timeout; normal EOF also returns null, distinguished by lastError.
     */
    private String readLineTimeout(long deadline) throws IOException {
        while (true) {
            if (reader.ready()) return reader.readLine();
            // ready() is always false on EOF (EOF does not count as "ready"). If process has exited, call readLine
            // directly to retrieve EOF/residual data immediately, preserving actual error cause (e.g. su rejection) without idling until timeout.
            if (!isAlive()) return reader.readLine();
            if (System.currentTimeMillis() > deadline) {
                abortTimeout();
                return null;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Read interrupted", e);
            }
        }
    }

    /** Timeout abort: orphaned app_process exits automatically after close closes stdin; force-kills afterwards to ensure cleanup. */
    private void abortTimeout() {
        lastError = "Background process timed out after " + CALL_TIMEOUT_MS / 1000 + " seconds without response, forcibly terminated";
        Process p = process;
        close();
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Calls and wraps exceptions into {ok:false} */
    public JSONObject safeCall(String cmd) {
        try {
            return call(cmd);
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", false);
                o.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    public synchronized void close() {
        if (process == null) return;
        try {
            writer.write("EXIT\n");
            writer.flush();
        } catch (Exception ignored) {
        }
        try {
            writer.close();
        } catch (Exception ignored) {
        }
        try {
            process.destroy();
        } catch (Exception ignored) {
        }
        process = null;
        writer = null;
        reader = null;
    }
}
