# ThermalHunter

An Android tool designed to identify the culprits behind phone heating and battery drain. Recommended: root (KernelSU) + LSPosed module; without root, basic features can also run with shell permissions via **Shizuku (Wireless Debugging / ADB)**. Initial target device is **iQOO Z10 Turbo+ (Dimensity, GKI 6.6 kernel)**, though other MediaTek / Qualcomm devices should theoretically work as well.

## Features

| Module | Description |
|---|---|
| Top Overview | SoC / Device / Battery temperature; discharge power (current × voltage); CPU cluster frequencies and throttling; GPU load and frequency (MediaTek GED / Mali devfreq / Qualcomm Adreno) |
| Thermal Ranking | Aggregate processes by app; native processes and kernel threads sorted by CPU (single-core %, 15s average), with estimated power consumption based on CPU share; flags anomalies such as high background load, cached processes still running, wakelocks, frequent alarms, etc. |
| KSU Modules | Scans `/data/adb/modules`, attributes processes to modules via exe, cmdline, working directory, overlaid system files, and parent process chains; identifies modules that **replace thermal configs, modify scheduler/frequency, run persistent looping scripts, hold kernel wakelocks, or inject via Zygisk** |
| LSP Modules | Reads LSPosed's `modules_config.db`: module scope, number of running scoped apps, scope CPU, whether injected into system framework, and anomalous apps within scope |
| Battery Drain Stats | Reads system batterystats (cumulative data since last full charge): battery drained, screen-on / screen-off drain rate per hour, screen-off deep sleep ratio; estimated mAh per app, foreground duration, background wakelocks, wakeup alarms, CPU time, location; kernel wakeup sources and wakelocks; flags anomalous apps with **long-term background drain** according to rules. Also available in Shizuku mode |
| Background Drain Monitor | (root) Checks batterystats roughly hourly using a non-wake-up alarm; sends a notification upon detecting severe anomalies (prolonged background wakelocks, frequent wakeups, excessive standby drain, etc.), alerting at most once per issue per charge cycle; automatically saves historical discharge cycles. Can be disabled in Settings |
| Wakelock Drain | Hooks `PowerManagerService` / `AlarmManagerService` in system_server, tracking each app's PARTIAL wakelock duration and wakeup alarm count; also displays kernel `/sys/class/wakeup` wakeup sources |
| One-Click Clean | Force-stops anomalous apps, kills anomalous processes and module processes; option in Settings to directly **disable** anomalous KSU / LSP modules |
| Individual Actions | Apps: Force stop / Disable (freeze) / Re-enable; Processes: Kill; KSU modules: Disable / Enable (prefers `ksud module disable`, falls back to writing `disable` flag file); LSP modules: Disable / Enable |
| Floating Window | Real-time overlay showing temperature, power consumption, and top 5 heat/CPU-consuming processes / apps above any screen; draggable, tap to expand / collapse; persistent notification with one-tap close, automatically pauses sampling when screen is off |
| Auto Module Attribution | (root) Low-frequency sampling every 30s using system simpleperf for CPU-consuming processes injected with modules (system_server, scoped apps), parses dex class names from module APKs, calculates actual CPU consumed by each LSPosed module based on call stacks to identify anomalies; only runs when UI or floating window is active, can be disabled in Settings |
| VPN Stealth | Makes VPN / proxy detection APIs (NetworkCapabilities, NetworkInterface) report "no VPN" within selected app processes; requires target apps to be included in LSPosed scope. Complements kernel-level vpnhide modules that cannot block framework API detection |
| Refresh Rate | (root/shell) Reads display-supported refresh rates, one-tap lock for global min/peak refresh rate; high refresh rate applies universally across all apps without being throttled per-app by the system |

## Working Modes

Settings → Working Mode: **Auto** (default, prefers root, falls back to Shizuku) / Root Only / Shizuku Only.

| Feature | Root Mode | Shizuku Mode (shell permission) |
|---|---|---|
| Temperature, power, CPU/GPU frequency, process ranking | ✅ | ✅ |
| Force stop / Disable (freeze) apps | ✅ | ✅ |
| Kill native processes / other app processes | ✅ | ❌ |
| KSU module, LSP module scanning and disabling | ✅ | ❌ |
| App wakelock / alarm stats (LSPosed hook) | ✅ | ❌ |

Shizuku mode usage: Install [Shizuku](https://shikuzu.rikka.app/), enable "Wireless Debugging" in Developer Options, pair and start service via Wireless Debugging in Shizuku; open this app and grant permission in the dialog. The service must be restarted in Shizuku after rebooting the phone.

## Architecture

```
App UI (MainActivity)  ──stdin/stdout line protocol──▶  Daemon process (runs RootMain in this APK via su or Shizuku + app_process)
   │                                                    ├─ Scanner: /proc, temperature, battery, cpufreq, GED, wakeup sources
   │                                                    ├─ KsuModules: module scanning + process attribution
   │                                                    ├─ LspModules: reads and writes LSPosed config DB using SQLite
   │                                                    └─ Actions: kill / am force-stop / pm disable / ksud (with protection list)
   ▼
LSPosed (Scope: System Framework) ── HookEntry hook system_server ──▶ /data/system/thermalhunter/stats.json ──▶ Read by root process
```

- Samples only when UI is visible (or floating window is active and screen is on); daemon process automatically exits after 60 seconds without subscribers. **This tool itself does not continuously drain battery in the background.**
- Long-term battery drain stats directly read system batterystats recorded by the system itself, without requiring this tool to run persistently; background monitoring only reads once an hour (when the phone is awake).
- Protection list (`core/Protect.java`): system_server, zygote, surfaceflinger, lmkd, ksud, lspd, System UI, vivo desktop and power management components, root / LSPosed managers, etc. These targets are forbidden from being killed or disabled to prevent crashes, reboots, or losing root.

## Build

Requires JDK 17 and Android SDK (platform 35, build-tools 35).

```bash
echo "sdk.dir=/your/android-sdk" > local.properties
./gradlew :app:assembleRelease        # Or open directly in Android Studio
# Output: app/build/outputs/apk/release/app-release.apk
```

## Usage

1. Install APK, **grant Superuser permissions to "ThermalHunter"** in KernelSU Manager.
2. Enable this module in LSPosed Manager, check **System Framework** and **ThermalHunter** in scope, then **reboot the phone**.
3. Open the App; items in red indicate anomalies. Tap to view details and perform actions.

## Detection Rules (`core/Analyzer.java`)

- Any app with continuous CPU ≥35%; background (adj≥200) ≥10%; cached process (adj≥900) ≥3%
- KSU module processes totaling ≥5%; other native processes ≥20%; kernel threads ≥30% (warning only, cannot be killed)
- Background app holding wakelock ≥50% of the time, or wakeup alarms ≥6 times/minute
- LSP module: flagged as anomalous if its own background process is ≥8%; marked as **suspicious** if anomalous apps exist in its scope, or if injected into system framework and system_server CPU is elevated

## Notes

- **Disabling an LSPosed module requires a reboot to take full effect**: lspd caches configs in memory, and this tool modifies the database directly.
- Disabling a KSU module immediately kills its processes, but its mounted files and `system.prop` modifications will only revert after a reboot.
- Power consumption of individual apps is an estimated value **allocated based on CPU share**; power estimation is not displayed during charging.
- Temperature sensor names for iQOO Z10 Turbo+ have not yet been confirmed on actual hardware. The App treats sensors with names containing `soc/cpu/mtktscpu/gpu/apu` as SoC temperature, and those containing `shell/skin/board/ap_ntc` as device temperature. If readings are inaccurate, send the output of `cat /sys/class/thermal/thermal_zone*/type` to adjust `Analyzer.isSocZone`.

### Signing

The release package uses a fixed signature. When building locally, place a `keystore.properties` in the project root (ignored by git):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=thermalhunter
keyPassword=...
```

Falls back to debug signature if this file is absent. GitHub Actions reads from repository Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
