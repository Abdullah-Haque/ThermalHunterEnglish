package com.thermalhunter.root;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes LSPosed module config DB at /data/adb/lspd/config/modules_config.db.
 * Typical table schema (official LSPosed):
 *   modules(mid, module_pkg_name, apk_path, enabled, ...)
 *   scope(mid, app_pkg_name, user_id)        -- System framework recorded as "system" in DB
 * Column names differ across branches (e.g. no mid column), so we inspect schema before querying.
 * Modern branches split enabled state into a separate table and record per user:
 *   modules(module_pkg_name, apk_path) + modules_state(module_pkg_name, user_id, enabled, ...)
 * After modifying the database directly, lspd's in-memory cache is not refreshed; a phone reboot is required to take full effect.
 */
final class LspModules {
    static final String[] DB_PATHS = {
            "/data/adb/lspd/config/modules_config.db",
    };

    static final class Scope {
        String pkg;
        int user;
    }

    static final class Module {
        String pkg;
        String apkPath;
        boolean enabled;
        final List<Scope> scopes = new ArrayList<>();
    }

    static final class Result {
        boolean available;
        String error;
        String dbPath;
        final List<Module> modules = new ArrayList<>();
    }

    private static Result cache;
    private static long cacheTime;

    static synchronized Result get() {
        long now = System.currentTimeMillis();
        if (cache == null || now - cacheTime > 20_000) {
            cache = load();
            cacheTime = now;
        }
        return cache;
    }

    static synchronized void invalidate() {
        cacheTime = 0;
    }

    static String dbPath() {
        for (String p : DB_PATHS) if (Sys.exists(p)) return p;
        return null;
    }

    private static Result load() {
        Result r = new Result();
        String path = dbPath();
        if (path == null) {
            r.error = Sys.exists("/data/adb/lspd") ? "Found /data/adb/lspd but modules_config.db is missing" : "LSPosed not installed";
            return r;
        }
        r.dbPath = path;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(path, null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Schema sc = schema(db);
            if (sc.error != null) {
                r.error = sc.error;
                return r;
            }
            Map<String, Module> byKey = new HashMap<>();
            String sql = "SELECT " + sc.mKey + ", " + sc.mPkg + ", " + (sc.mApk == null ? "''" : sc.mApk) + ", "
                    + (sc.mEnabled == null ? "1" : sc.mEnabled) + " FROM " + sc.modules;
            try (Cursor c = db.rawQuery(sql, null)) {
                while (c.moveToNext()) {
                    Module m = new Module();
                    m.pkg = c.getString(1);
                    m.apkPath = c.getString(2);
                    m.enabled = c.getInt(3) != 0;
                    byKey.put(c.getString(0), m);
                    r.modules.add(m);
                }
            }
            if (sc.state != null) {
                // Enabled state in separate table: prefer primary user (0), else enabled if any user is enabled; treated as disabled if no record exists
                Map<String, Boolean> user0 = new HashMap<>(), any = new HashMap<>();
                String q = "SELECT " + sc.stPkg + ", " + (sc.stUser == null ? "0" : sc.stUser) + ", " + sc.stEnabled
                        + " FROM " + sc.state;
                try (Cursor c = db.rawQuery(q, null)) {
                    while (c.moveToNext()) {
                        String pkg = c.getString(0);
                        boolean en = c.getInt(2) != 0;
                        if (c.getInt(1) == 0) user0.put(pkg, en);
                        any.put(pkg, en || Boolean.TRUE.equals(any.get(pkg)));
                    }
                }
                for (Module m : r.modules) {
                    Boolean v = user0.get(m.pkg);
                    if (v == null) v = any.get(m.pkg);
                    m.enabled = v != null && v;
                }
            }
            if (sc.scope != null) {
                String ssql = "SELECT " + sc.sKey + ", " + sc.sApp + ", " + (sc.sUser == null ? "0" : sc.sUser)
                        + " FROM " + sc.scope;
                try (Cursor c = db.rawQuery(ssql, null)) {
                    while (c.moveToNext()) {
                        Module m = byKey.get(c.getString(0));
                        if (m == null) continue;
                        Scope s = new Scope();
                        s.pkg = c.getString(1);
                        s.user = c.getInt(2);
                        m.scopes.add(s);
                    }
                }
            }
            r.available = true;
        } catch (Throwable e) {
            r.error = "Failed to read LSPosed database: " + e;
        } finally {
            if (db != null) db.close();
        }
        return r;
    }

    /** Enable/disable module. Returns null on success, otherwise error message. */
    static String setEnabled(String pkg, boolean enabled) {
        String path = dbPath();
        if (path == null) return "LSPosed database not found";
        String ctx = fileContext(path);
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(path, null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Schema sc = schema(db);
            if (sc.error != null) return sc.error;
            if (sc.mEnabled != null) {
                ContentValues cv = new ContentValues();
                cv.put(sc.mEnabled, enabled ? 1 : 0);
                int n = db.update(sc.modules, cv, sc.mPkg + " = ?", new String[]{pkg});
                if (n == 0) return "Module not found in database: " + pkg;
            } else if (sc.state != null) {
                // Per-user enabled state: update records for all users of this module; create a new record for primary user if none exists when enabling
                ContentValues cv = new ContentValues();
                cv.put(sc.stEnabled, enabled ? 1 : 0);
                int n = db.update(sc.state, cv, sc.stPkg + " = ?", new String[]{pkg});
                if (n == 0) {
                    if (!enabled) return null; // No record already means disabled
                    ContentValues ins = new ContentValues();
                    ins.put(sc.stPkg, pkg);
                    if (sc.stUser != null) ins.put(sc.stUser, 0);
                    ins.put(sc.stEnabled, 1);
                    if (db.insert(sc.state, null, ins) < 0) return "Failed to write LSPosed enabled state";
                }
            } else {
                return "Disabling modules is not supported on this LSPosed version (enabled column not found). Please report this info:\n" + describe(db);
            }
        } catch (Throwable e) {
            return "Failed to write LSPosed database: " + e;
        } finally {
            if (db != null) db.close();
            // Maintain same SELinux context as original database (lspd needs read/write access)
            if (ctx != null) {
                for (String suf : new String[]{"", "-wal", "-shm", "-journal"}) {
                    if (Sys.exists(path + suf)) Sys.exec("chcon", ctx, path + suf);
                }
            }
            invalidate();
        }
        return null;
    }

    /** Table/column names determined by actual schema. */
    private static final class Schema {
        String error;
        String modules, mKey, mPkg, mApk, mEnabled;
        String scope, sKey, sApp, sUser;
        String state, stPkg, stUser, stEnabled; // Independent enabled-state table (can be null)
    }

    private static List<String> tables(SQLiteDatabase db) {
        List<String> l = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            while (c.moveToNext()) l.add(c.getString(0));
        }
        return l;
    }

    private static List<String> columns(SQLiteDatabase db, String table) {
        List<String> l = new ArrayList<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int idx = c.getColumnIndex("name");
            while (c.moveToNext()) l.add(c.getString(idx).toLowerCase(Locale.ROOT));
        }
        return l;
    }

    /** List all tables and their columns, for reporting unsupported database structures. */
    private static String describe(SQLiteDatabase db) {
        StringBuilder sb = new StringBuilder();
        for (String t : tables(db)) {
            if (t.startsWith("sqlite_") || t.equals("android_metadata")) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t).append(columns(db, t));
        }
        return sb.toString();
    }

    private static String pick(List<String> have, String... want) {
        for (String w : want) if (have.contains(w)) return w;
        return null;
    }

    private static Schema schema(SQLiteDatabase db) {
        Schema s = new Schema();
        List<String> tables = tables(db);
        s.modules = pick(tables, "modules", "module");
        s.scope = pick(tables, "scope", "scopes", "module_scope");
        if (s.modules == null) {
            s.error = "Unrecognized LSPosed database: no modules table. Schema:\n" + describe(db);
            return s;
        }
        List<String> mc = columns(db, s.modules);
        s.mPkg = pick(mc, "module_pkg_name", "pkg_name", "package_name", "module_package_name", "pkg");
        s.mApk = pick(mc, "apk_path", "path", "module_apk_path");
        s.mEnabled = pick(mc, "enabled", "enable", "is_enabled");
        if (s.mPkg == null) {
            s.error = "Unrecognized LSPosed database. Schema:\n" + describe(db);
            return s;
        }
        String mId = pick(mc, "mid", "module_id", "id", "_id");
        if (s.scope != null) {
            List<String> sc = columns(db, s.scope);
            s.sApp = pick(sc, "app_pkg_name", "pkg_name", "package_name", "app_package_name", "app");
            s.sUser = pick(sc, "user_id", "userid", "user");
            String sId = pick(sc, "mid", "module_id");
            String sPkg = pick(sc, "module_pkg_name", "module_package_name", "module");
            if (s.sApp == null) {
                s.scope = null; // When scope table cannot be identified, only show module list
            } else if (sId != null) {
                // Scope associated by module id; if module table has no id column, use rowid (INTEGER PRIMARY KEY is alias for rowid)
                s.sKey = sId;
                s.mKey = mId != null ? mId : "rowid";
            } else if (sPkg != null) {
                s.sKey = sPkg;
                s.mKey = s.mPkg;
            } else {
                s.scope = null;
            }
        }
        if (s.mKey == null) s.mKey = mId != null ? mId : "rowid";
        if (s.mEnabled == null) {
            // Look in other tables for an enabled state table with "pkg_name + enabled", e.g. modules_state
            for (String t : tables) {
                if (t.equals(s.modules) || t.equals(s.scope)) continue;
                List<String> cols = columns(db, t);
                String en = pick(cols, "enabled", "enable", "is_enabled");
                String pk = pick(cols, "module_pkg_name", "module_package_name", "pkg_name", "package_name");
                if (en != null && pk != null) {
                    s.state = t;
                    s.stEnabled = en;
                    s.stPkg = pk;
                    s.stUser = pick(cols, "user_id", "userid", "user");
                    break;
                }
            }
        }
        return s;
    }

    private static String fileContext(String path) {
        Object[] r = Sys.exec("ls", "-Z", path);
        if ((int) r[0] != 0) return null;
        String out = ((String) r[1]).trim();
        String first = out.split("\\s+")[0];
        return first.startsWith("u:") ? first : null;
    }
}
