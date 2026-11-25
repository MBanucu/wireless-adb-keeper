# Connect to adbd before unlock

`tools/preunlock-connect.sh` attaches this **host** to a phone that adbd is
already serving wireless debugging on, while the phone is still locked. It is
the stopgap for [#101](https://github.com/MBanucu/wireless-adb-keeper/issues/101)
until the app-owned pin makes 5555 available at boot.

adbd starts at boot and its `_adb-tls-connect._tcp` listener answers with the
host key it has stored, so `adb shell id` returns `uid=2000(shell)` with no
keyguard interaction and no USB. The port is random per adbd start, and an mDNS
record can outlive the listener, so every candidate is proved before it is
used.

This script runs on the laptop. It must not be copied into Termux; the phone
side has `pin-adb-port.sh`, which uses loopback.

## Usage

```bash
tools/preunlock-connect.sh                          # discover and attach
tools/preunlock-connect.sh --tcpip                   # ... and pin 5555
tools/preunlock-connect.sh --host 192.168.77.232     # skip address discovery
tools/preunlock-connect.sh --tcpip 5555 --no-scan    # no range scan
```

| option | meaning |
| --- | --- |
| `--host ADDR` | phone IPv4 address. Default: an attached adb serial, the USB-attached phone's `wlan0` address, then the mDNS record |
| `--tcpip [PORT]` | after attaching, run `adb tcpip PORT` (default `ADB_PIN_PORT`, 5555) and reconnect on it |
| `--seconds N` | mDNS timeout for `adb-connect` (default `MDNS_SECONDS`, 3) |
| `--no-scan` | skip the 32768–60999 fallback scan |

The last stdout line is machine readable, matching the Termux scripts:

```
RESULT connected=192.168.77.232:33801
RESULT connected=192.168.77.232:5555 tcpip=5555
RESULT error=no-host | RESULT error=no-adbd | RESULT error=no-adb
RESULT error=tcpip-refused tcpip=5555 | RESULT error=port-closed port=5555
```

`RESULT error=no-host` means nothing advertised the phone and no address was
available: pass `--host`. `RESULT error=no-adbd` means candidates were seen but
none answered as an adbd this host is paired with.

The script also runs `adb shell id` and prints the line, because that is the
issue's own proof. It is informational: a locked userspace can still refuse the
shell service while the transport is fine.

## Discovery order

1. The pinned port, if this boot already has it.
2. `_adb-tls-connect._tcp` resolved with `avahi-browse -rtp` (IPv4 records). The
   host's adb often has no mDNS support (`unknown host service 'mdns:services'`),
   so a system browser is used instead of adb.
3. The same advertisement through `adb-connect` from
   `adb-harness[mdns]`, the helper `discover.sh` already uses. Install it with
   `bash tools/setup-adb-harness.sh` when the host has no avahi.
4. The range scan from `scan_ports.py` (`PORT_START`–`PORT_END`, Linux's
   ephemeral range by default) against the phone's address.

Each candidate is first probed with a TCP connect and then attached with
`adb connect`; only a serial that `adb devices` lists as `device` counts. A
port already remembered as `offline` is disconnected first and the connect is
retried, which is what a stale advertisement usually needs.

## Prerequisites

- `adb` on the host and **the host's existing key already paired with the
  phone** (`~/.android/adbkey`, normally). The script creates no key and asks
  for no code: pairing is a one-time `adb pair` step done from the host, or
  `pair-local.sh` from Termux.
- Wireless debugging is on: adbd only listens while Wi-Fi is connected.
- Optional: `avahi-utils` (`avahi-browse`) or `adb-harness[mdns]`
  (`bash tools/setup-adb-harness.sh`).

## systemd / udev example

Run it whenever the phone appears, so a reboot is followed automatically and no
unlock is needed. The unit runs as the user who owns `~/.android/adbkey`.

`/etc/systemd/system/preunlock-connect.service`:

```ini
[Unit]
Description=Attach adb to the phone before unlock
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
User=youruser
Environment=HOME=/home/youruser
ExecStart=/opt/wireless-adb-keeper/tools/preunlock-connect.sh --host 192.168.77.232 --no-scan
# Optional: pin 5555 once attached.
# ExecStart=/opt/wireless-adb-keeper/tools/preunlock-connect.sh --host 192.168.77.232 --tcpip 5555 --no-scan
TimeoutStartSec=120
```

USB appearance (`/etc/udev/rules.d/90-preunlock-adb.rules`; use the phone's
vendor id, `lsusb` shows it):

```
ACTION=="add", SUBSYSTEM=="usb", ATTR{idVendor}=="2e04", TAG+="systemd", ENV{SYSTEMD_WANTS}+="preunlock-connect.service"
```

Wi-Fi appearance does not need a custom daemon either. With NetworkManager, add
`/etc/NetworkManager/dispatcher.d/90-preunlock-connect`:

```sh
#!/bin/sh
[ "$2" = "up" ] || exit 0
systemctl --no-block start preunlock-connect.service
```

For systemd-networkd, the same one-liner goes in
`/usr/lib/networkd-dispatcher/routable.d/50-preunlock-connect`, with the
`[ "$2" = "up" ]` check replaced by `[ "$1" = "routable" ] || exit 0`.

Then:

```bash
sudo systemctl daemon-reload
sudo udevadm control --reload-rules
adb devices                      # after the next reboot, before unlock
adb -s 192.168.77.232:<port> shell id
```

No USB, the keeper app, or a system service on the phone is required; the hook
is only an example trigger. A plain `systemd.timer` is enough if the phone's
address is fixed.

## Measured

On 2026-11-24, boot_id 135, this host ran the avahi path against a
`RUNNING_LOCKED` phone:

```
$ ADB_PIN_PORT=1 tools/preunlock-connect.sh --host 192.168.77.232 --no-scan
-- 192.168.77.232:39787: open, connecting
connected: 192.168.77.232:39787
shell: uid=2000(shell) ...
RESULT connected=192.168.77.232:39787
```

The pinned 5555 path was also exercised on the same (unlocked) phone and
returned `RESULT connected=192.168.77.232:5555` with `uid=2000(shell)`. Both
were read-only connects; nothing on the phone was changed by this script.

## Limitations

- IPv4 only. The mDNS IPv6 records are skipped; the scenario is a laptop on the
  phone-link network.
- `--tcpip` is **unencrypted and RSA-authorised**, unlike the TLS listener.
  Use it only on a network you control. It also restarts adbd, so the TLS
  serial goes offline afterwards.
- `--no-scan` is recommended for unattended hooks: the scan can take tens of
  seconds and is only the last resort.
- Pinning still needs Termux on the phone (see the [README](../README.md)); this script only
  attaches the host.

Tests: `bash tools/preunlock-connect-test.sh` (fake `avahi-browse`/`adb` plus
real loopback listeners; no phone, no reboot). It runs in `./gradlew
probeScripts` and therefore in `probe`.

## See also

[README](../README.md) · [Build](build.md) · [Install](install.md) · [Control](control.md) · [Boot](boot.md) · [Architecture](architecture.md) · [Limits](limits.md) · [Diagnostics](diagnostics.md) · [Reboot verdict](reboot-verdict.md) · [Termux build](termux-build.md)
