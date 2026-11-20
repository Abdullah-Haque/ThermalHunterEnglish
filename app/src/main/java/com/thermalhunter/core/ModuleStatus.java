package com.thermalhunter.core;

/** When activated by LSPosed, HookEntry modifies isActive() to return true. */
public final class ModuleStatus {
    private ModuleStatus() {}

    public static boolean isActive() {
        // Prevent compiler from inlining constant
        return Boolean.parseBoolean(System.getProperty("thermalhunter.never", "false"));
    }
}
