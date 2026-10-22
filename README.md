# Turbo Cut

> Session-only "game mode" for rooted Android: scan for bloat, telemetry, loggers and vibration services, see exactly what's running, then stop it all with one tap. Everything resets on reboot.

Turbo Cut has a terminal-style UI. **SCAN** lists everything it can stop, with package IDs, PIDs and service states. **STOP ALL** stops it and prints a result for each item. Nothing is uninstalled or permanently disabled.

Built for a rooted Samsung Galaxy Tab S6 (One UI). Other devices should work but package and service names may differ. The scan only offers items that are actually present and running on your device.

## Features

- Terminal-style live output with colour-coded results (`[FOUND]`, `[ OK ]`, `[WARN]`, `[FAIL]`, `[DANGER]`)
- Verbose scan: package ID, process count, PIDs, process names, service state
- **STOP ALL** for normal items; separate two-tap **DANGER** button for core services (logd)
- Session-only: stops are runtime changes that reset on reboot
- Vibration settings are saved before changing and restored at boot (or with **RESTORE**)
- ROOT button to (re)request root; ROOT access via [libsu](https://github.com/topjohnwu/libsu)

## Requirements

- Rooted device (Magisk or KernelSU), Android 10+ (minSdk 29)
- Android Studio to build

## Build

1. Open the project folder in Android Studio and let Gradle sync (libsu is pulled from JitPack).
2. Build and install the debug APK.
3. Open the app and allow root in your root manager (or tap **ROOT**).
4. Tap **SCAN**, then **STOP ALL**.

## Buttons

| Button | What it does |
|---|---|
| ROOT | Closes any cached shell and asks your root manager for root again |
| SCAN | Detects what can be stopped and prints details |
| STOP ALL | Stops every non-danger item found by the scan |
| DANGER | Two taps (DANGER, then CONFIRM) to stop core services (logd) |
| RESTORE | Puts back vibration settings Turbo Cut changed |
| CLEAR | Clears the terminal |

## Targets

**Apps (force-stopped):** Samsung Diagnostic Monitor, Device Quality Agent, Customization Service, Bixby (Voice, Service, Vision), Samsung Free, Game Launcher, Tips, AR Zone, AR Emoji, DA Agent, Samsung Mobile Services, Quick Share, Smart Switch Agent, Facebook App Manager / Services / Installer, Link to Windows.

**Init services:** `statsd`, `traced`, `traced_probes`, `tombstoned`, `sec-vibrator-2-2`

**Danger (manual only):** `logd`

**Runtime tweaks:** kernel log level, `/dev/kmsg` writes, global log level, logcat buffers, vibration/haptic settings

Edit the lists at the top of `Engine.kt` (`PKGS`, `SVCS`, `DANGER`, `TWEAKS`) to add or remove targets.

## Commands executed

All commands run as root through libsu. `<pkg>`, `<svc>`, `<ns>` and `<key>` are filled in per item.

### Identity and root check
| Command | Purpose |
|---|---|
| `id` | Print the current user (confirms uid=0) |

### Detection (read-only)
| Command | Purpose |
|---|---|
| `pm list packages` | List installed packages |
| `pidof <pkg>` / `pidof <svc>` | Check whether a process is running |
| `ps -A -o PID,NAME \| grep -F <pkg>` | List the app's processes and sub-processes |
| `getprop init.svc.<svc>` | Read a service's init state |
| `getprop log.tag` | Read the global log level |
| `settings list system` / `secure` / `global` | Find vibration and haptic settings |
| `settings get <ns> <key>` | Read a setting's current value |
| `cut -f1 /proc/sys/kernel/printk` | Read the kernel console log level |
| `cat /proc/sys/kernel/printk_devkmsg` | Read whether userspace kernel-log writes are allowed |

### Stopping apps
| Command | Purpose |
|---|---|
| `am force-stop <pkg>` | Kill the app's processes (session only) |

### Stopping services
| Command | Purpose |
|---|---|
| `stop <svc>` | Stop an init service (`statsd`, `traced`, `traced_probes`, `tombstoned`, `sec-vibrator-2-2`, and `logd` via DANGER) |

### Vibration and haptics
| Command | Purpose |
|---|---|
| `settings put <ns> <key> 0` | Turn a vibration/haptic setting off (original value saved first) |
| `settings put <ns> <key> <original>` | Restore the saved value (RESTORE button and boot receiver) |

### Logging and kernel log
| Command | Purpose |
|---|---|
| `logcat -G 64K` | Shrink logcat buffers |
| `logcat -c` | Clear logcat buffers |
| `setprop log.tag S` | Suppress debug/verbose logging globally |
| `echo '0 0 0 0' > /proc/sys/kernel/printk` | Silence the kernel console log |
| `dmesg -c > /dev/null` | Clear the kernel ring buffer |
| `echo off > /proc/sys/kernel/printk_devkmsg` | Ignore userspace writes to `/dev/kmsg` |

## Everything resets on reboot

| Change | Resets how |
|---|---|
| Force-stopped apps, stopped services | Restart at boot |
| `log.tag`, `printk`, `printk_devkmsg`, logcat buffers | Runtime values reset at boot |
| Vibration settings (stored on disk) | Restored by the boot receiver or RESTORE |

To undo something without rebooting (defaults vary by device):

```sh
start <svc>                                   # restart a stopped service
setprop log.tag ""                            # clear global log level
echo '4 4 1 7' > /proc/sys/kernel/printk      # typical default console level
echo ratelimit > /proc/sys/kernel/printk_devkmsg
logcat -G 256K                                # logcat buffer size
```

## Warnings

- Stopping `logd` disables `logcat` and system logging until reboot.
- Stopping `sec-vibrator-2-2` disables vibration until reboot.
- Stopping Samsung Mobile Services can pause account sync.
- Apps may restart themselves after being force-stopped; Turbo Cut reports this as `[WARN] respawned`.
- Some kernels or SELinux policies block the `/proc/sys` writes; these show as `[FAIL]`.
- Use at your own risk. If something misbehaves, reboot.

## Project layout

```
app/src/main/java/com/example/turbo/
  Engine.kt         scan / stop / restore logic and target lists
  MainActivity.kt   terminal UI (Jetpack Compose)
  BootReceiver.kt   restores vibration settings at boot
```

## License

Add a license of your choice (for example MIT) before publishing.
