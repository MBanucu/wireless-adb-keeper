# Boot

```bash
tools/reboot-verdict.sh check     # prove what survived the reboot
```

## Reboot and unlock

```bash
../phone-link-tools/reboot.sh                   # reboot, return after a new boot id
../phone-link-tools/unlock.sh --pin 1234        # clear the keyguard
../phone-link-tools/unlock.sh --check           # print the lock state only
PHONE_PIN=1234 ../phone-link-tools/unlock.sh    # or take the PIN from the env
```

To leave evidence across the reboot, even if the shell that starts it dies:

```bash
tools/reboot-verdict.sh record    # before the reboot
tools/reboot-verdict.sh check     # after it, once unlocked
```

`check` compares `boot_id`, the diagnostics snapshot and the journal's newest
`boot.received` section. To also prove what was reachable *before* the
unlock, start it while the phone boots (it samples 5555, the TLS port and
wlan0 at ~27/45/60/90 s):

```bash
tools/reboot-verdict.sh check --preunlock-wait 180
```

A pre-unlock sample that saw 5555 closed while Wi-Fi was up fails only with
`--require-preunlock-port` (off by default). See
[`reboot-verdict.md`](reboot-verdict.md).

Boot completion is waited for with **one** adb round trip: the polling loop runs on
 the phone (`while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 0.1; done`),
so the cost is 100 ms granularity rather than an adb call per tick. After that the
script wakes the screen, dismisses the keyguard and types the PIN, retrying until
`dumpsys user` reports `RUNNING_UNLOCKED`. Measured on this phone: adbd back at
t+44 s, `sys.boot_completed` at t+52 s, unlocked at t+55 s.

### Two independent boot gates (DokeOS)

On the XPLORE 2 a reboot has two independent gates. Passing one does not pass
the other, and neither is visible as "port closed" alone:

1. **DokeOS Auto Start Management** — **System Manager → App Management →
   Auto Start Management**, allow **Wireless ADB Keeper**. This decides whether
   the package may receive boot broadcasts at all. The manifest is necessary and
   already correct here — `RECEIVE_BOOT_COMPLETED` plus
   `android:directBootAware="true"` on `BootReceiver` itself, since DokeOS does
   not inherit it from `<application>` — but it is not sufficient. With the row
   off, DokeOS leaves the package `stopped=true` at boot and logs
   `BVPFWLogger: AppAutoStartupPolicy: prevent start receiver of package ...`.
   The app-targeted `LOCKED_BOOT_COMPLETED`/`BOOT_COMPLETED` then arrive only
   when the app is opened by hand: measured **+238 s** on the 2026-11-24 boot,
   with `process_started_after_boot_s=238`. With the row on, delivery was about
   **27 s** after boot, before the first unlock.
2. **Doze / battery exemption** — **Settings → Apps → Wireless ADB Keeper →
   Battery → Unrestricted** (or `adb shell cmd deviceidle whitelist
   +io.github.mbanucu.wirelessadbkeeper`). This keeps the monitor from being
   stopped after boot. It does not deliver the boot broadcast, so it cannot
   compensate for gate 1.

Verify each after a reboot with **no manual launch**. Gate 1 is the first line
below; gate 2 is a whitelist entry, not a boot event:

```bash
adb shell dumpsys deviceidle whitelist | grep io.github.mbanucu.wirelessadbkeeper
./keeper.sh diag | grep -E 'oem\.autostart|process_started_after_boot_s'
./keeper.sh diag | grep 'boot.received'   # the first line should say before unlock
./keeper.sh diag | grep 'pin.state'       # listening on 5555
```

`oem.autostart=allowed` means a `boot.received` arrived **before unlock**, which
DokeOS only does for a package it started at boot. `blocked` means the broadcast
arrived only **after unlock** and the process started at least 120 s after boot
— the measured gate-1 signature. `unknown` means the evidence is not enough
(no `boot.received` this boot, an early unlock, or no DokeOS manager); it is
never a claim that the row is on. The app does not read the vendor list; the
signature permission is not available to it. To read what the screen shows, run
`./keeper.sh autostart` (it reports `before=`/`after=` and switches the row on
if it was off), or `./autostart-allow.sh --check "Wireless ADB Keeper"` for a
read-only second opinion on an unlocked phone.

### Connect before unlock (host)

adbd answers wireless debugging while the phone is still locked. From the host, without
USB and without the app:

```bash
tools/preunlock-connect.sh                  # discover the TLS port and attach
tools/preunlock-connect.sh --tcpip          # ... then pin 5555 for later
tools/preunlock-connect.sh --host 192.168.77.232 --no-scan
```

It reads `_adb-tls-connect._tcp` with `avahi-browse`, falls back to adb-harness's
`adb-connect` and then the shared `scan_ports.py` range scan, proves each candidate with
a TCP connect, and attaches with the host's existing key. The last line is
`RESULT connected=<addr>:<port>` (see the table in
[preunlock-connect.md](preunlock-connect.md), which also has the systemd/udev
hook). No new key or secret: pair the host once, then it survives reboots.

## See also

[README](../README.md) · [Build](build.md) · [Install](install.md) · [Control](control.md) · [Architecture](architecture.md) · [Limits](limits.md) · [Diagnostics](diagnostics.md) · [Reboot verdict](reboot-verdict.md) · [Pre-unlock connect](preunlock-connect.md) · [Termux build](termux-build.md)
