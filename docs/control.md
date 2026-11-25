# Control

```bash
./keeper.sh status    # the same actions the buttons run, from adb
```

## Control from adb

Every action is also a shell command, so the app never needs to be tapped.
`keeper.sh` wraps the raw call; `content call` starts the app even when it was
force-stopped or only just installed, which a broadcast cannot do.

```bash
./keeper.sh status            # the Status page as key=value lines
./keeper.sh enable            # Enable now
./keeper.sh wifi-on           # Turn Wi-Fi on
./keeper.sh start | stop      # monitor service
./keeper.sh connect           # Connect now
./keeper.sh mode auto|local   # Connect mode
./keeper.sh profiles          # saved profiles, one line each, in try order
./keeper.sh save SSID [PASS]  # Save SSID and password (add or update)
./keeper.sh save --profile ID SSID [PASS]  # update one profile by id
./keeper.sh profile-rm ID     # delete a profile, its secret and applied record
./keeper.sh priority ID N     # move a profile in the try order (1 is first)
./keeper.sh pin               # Pin adb port 5555 now
./keeper.sh pubkey            # the app's own ADB public key
./keeper.sh pair CODE         # pair with the code Developer options shows
./keeper.sh pair-verify       # open shell:id over the trusted port
./keeper.sh schedule          # 15-minute keep-alive job
./keeper.sh diag              # status snapshot + recent events
```

### Profiles from the command line

`profiles` prints one line per saved network, in try order:

```
id=default ssid=WP22 security=wpa2 passphrase=set priority=1
id=p1 ssid=Lab security=auto passphrase=none priority=2
```

`security=auto` means the security is decided from the scan; the four explicit
values are `wpa2`, `wpa3`, `owe` and `open`. The passphrase itself is never part
of a reply; `passphrase=set|none` is all that is reported. With nothing saved,
`profiles` replies `profiles=0`.

`save SSID [PASSWORD]` keeps its old meaning: the profile with that SSID is
updated, or a new one is appended, so calling it twice with the same SSID does
not create a duplicate. `save --profile ID SSID [PASSWORD]` targets one id
instead; a rename keeps that id, and an unknown id is an error rather than an
implicit add. `profile-rm ID` deletes the profile, its ciphertext and its
applied-suggestion record. `priority ID N` moves a profile to a 1-based
position, where 1 is tried first; a position outside the list is refused.

`status` also reports `profiles=<n>` and `profile_active=<id>` (the first
profile in the try order). A save, rename, delete or move writes one coded
journal event: `profile.added`, `profile.updated` or `profile.removed`.

`mode` and `save` reply with `reload=monitor` when a running monitor applied the
change, or `reload=next start` when no service was running: the value is saved in
either case and does not need a service restart. (`content call` cannot start the
monitor service on Android 12+, so the running service watches the saved settings
instead of being told by an intent.)

Without the wrapper, the same thing by hand:

```bash
adb shell content call --uri content://io.github.mbanucu.wirelessadbkeeper.control \
    --method status
```

The provider is exported, but it gates the caller: adb shell, root, system and the
app itself get through by uid, and an ordinary app that reaches it through its
own `ContentResolver` may present the control token instead. A caller with
neither gets the same 403 whether it sends no token or a wrong one. Errors come back as `code=400, data=error=...`; `keeper.sh` strips the
envelope. `start` is the one command that does not use the provider: Android 12+
refuses to let a background app start its own foreground service, so the shell
starts it with `am start-foreground-service` instead.

## Control from Termux (no adb)

`adb shell content call` — and therefore `keeper.sh` — runs as uid 2000 and has
always passed the gate. The path that did not work is `content call` straight
from a Termux prompt: the `content` command asks the platform for an "external"
provider, which needs the signature permission
`ACCESS_CONTENT_PROVIDERS_EXTERNALLY`. Only shell and root hold that permission,
so an app uid is refused inside `getContentProviderExternal()` before
`ControlProvider` is entered. A token extra cannot change that; the provider is
simply not reachable from that command.

The app therefore also exports an ordered broadcast (`ControlReceiver`) with the
same dispatch and the token gate. It is token-only because a broadcast does not
give the receiver a trustworthy sender uid on every supported release; shell and
root keep their uid gate through the provider. `keeperctl.sh` uses the
broadcast. Mint the token once, from adb:

```bash
mkdir -p ~/.config/wireless-adb-keeper
chmod 700 ~/.config/wireless-adb-keeper
touch ~/.config/wireless-adb-keeper/token
chmod 600 ~/.config/wireless-adb-keeper/token
adb shell content call --uri content://io.github.mbanucu.wirelessadbkeeper.control \
    --method token \
  | sed -n 's/.*data=token=\([0-9a-f]\{64\}\).*/\1/p' \
  > ~/.config/wireless-adb-keeper/token
```

`--method token` is shell-only, like `pubkey`, and mints 32 random bytes on first
use. The app keeps them in device-protected storage beside its ADB key, at
`/data/user_de/0/<package>/files/adb-identity/control.token` with mode 0600. The
copy in Termux holds the 64 hex characters with mode 600. `keeperctl.sh` refuses
a file that is not owned by the current user or is not 600, and never prints the
token.

```bash
./keeperctl.sh status            # the same key=value fields as keeper.sh status
./keeperctl.sh enable
./keeperctl.sh wifi-on
./keeperctl.sh connect | stop
./keeperctl.sh mode auto|local
./keeperctl.sh profiles
./keeperctl.sh save SSID [PASS]
./keeperctl.sh save --profile ID SSID [PASS]
./keeperctl.sh profile-rm ID
./keeperctl.sh priority ID N
./keeperctl.sh schedule
./keeperctl.sh diag
```

It runs from the checkout; there is nothing to install. If the scripts are copied to
`~/wireless-adb-keeper/`, copy `keeperctl.sh` beside them.

The token is a capability, not a package check: any process that can read the
600 file can drive the app, and no other app can. That is deliberate — it
survives a Termux reinstall and works with a Termux fork whose package id is
different. Root, adb shell and the app itself keep working without it, so the
existing `adb shell` path is unchanged. `start`, `pin`, `pubkey`, `pair`,
`pair-verify` and `token` still need adb; they do something an app uid cannot
(start another app's foreground service, run `adb tcpip`, or handle identity
material).

A force-stopped app receives no broadcasts. If `keeperctl.sh` reports no reply,
run `./keeper.sh status` once (the provider starts the app) or open it; the
provider path is the recovery path.

Residual exposure: the token travels in the argv of the short-lived `am`/`cmd`
process as `--es token ...`. On this device `/proc` is mounted
`hidepid=invisible`, so another app cannot read it; a process running as
Termux's uid can, but it can also read the 600 file. `am`/`content` have no
environment or stdin channel for a provider extra, so avoiding argv entirely
would need a second app with a Binder helper. The token is not echoed by the
wrapper, written to logcat, or put in an error reply: the app logs the caller
(or `broadcast sender`), the method and only whether a token was presented,
never its characters.

`ENSURE` is a broadcast rather than a provider call:

```bash
adb shell am broadcast -a io.github.mbanucu.wirelessadbkeeper.ENSURE \
    -n io.github.mbanucu.wirelessadbkeeper/.EnsureReceiver
```

`reconnect.sh` prints the same command as a hint. The receiver is exported but
requires `WRITE_SECURE_SETTINGS`, which shell, root, system and the app itself
hold; an ordinary app's broadcast is refused.

## Script result contract

The app asks Termux for the execution result with the
`com.termux.RUN_COMMAND_PENDING_INTENT` extra (Termux >= 0.109). Every script the
app runs ends with one machine-readable line on stdout, `RESULT key=value ...`;
values are single tokens separated by whitespace, and the last `RESULT` line
wins if a script prints more than one.

| script | success | failure |
| --- | --- | --- |
| `pin-adb-port.sh` | `RESULT pinned=5555` | `RESULT error=no-adbd`, `RESULT error=port-closed port=5555` |
| `wait_adb.sh` | `RESULT connected=5555` | `RESULT error=timeout` |
| `wifi-on.sh` | `RESULT wifi=on` | `RESULT error=no-device`, `RESULT error=wifi-off state=0` |

A pin already running under another PID exits 0 with `RESULT skipped=running`.
The app parses that line, keeps Termux's stdout, stderr and exit code, and
writes one `pin.result` (or `script.result`) event to the journal. The raw
bundle keys are Termux's own: `result` → `stdout`, `stderr`, `exitCode`, `err`,
`errmsg`.

What the person holding the phone sees:

- **Success**: the Status page's **Pin result** row shows the script's own
  `pinned=5555 (exit 0)`, and **Pinned port** reads
  `listening on 5555; script pinned=5555`.
- **Failure**: **Pin result** shows the script's error, its exit code and the
  first stderr line, for example `error=no-adbd (exit 1); adbd: no devices`.
  **Pinned port** carries the same reason instead of a bare `closed`, and the
  full line is in the Diag journal under `pin.result`.
- **Older Termux** (before 0.109): the pending intent is ignored, **Pin
  result** stays `none`, and one `pin.fallback` journal event says the port
  probe is the only evidence. Pinning itself behaves exactly as before.

The probe is still the authority on whether the socket answers; a script's
RESULT line is only trusted for five minutes, so a later failure is not hidden
by an old success.

Measured on this phone (Android 15, unrooted):

| event | 5555 closed → reachable |
| --- | --- |
| monitor service running: Wi-Fi back, wireless debugging re-enabled, `adb usb`, or adbd dying | typically ≤ ~16 s; up to ~31 s if the close follows a Termux ask inside the 30 s window (10 s probe, a check 15 s after a network/ensure event, ~1 s script) |
| reboot, without touching anything | ≈ 60 s after `sys.boot_completed` (boot receiver → ask → `tcpip`) |

The first check after the service starts runs 8 s in. The monitor's checks ask Termux at
most once per 30 s, so a close that follows an ask inside that window waits for the
window to pass (≤ ~31 s); `keeper.sh pin` and the button ask immediately. After a closed
result the port is probed once more 15 s later; that is when `pinned_port=` catches up,
so the status line can lag the port by up to 15 s.
The 10 s probe interval is what keeps the no-event case inside #5's ≤ 30 s target; the
probe is a loopback connect, and the 30 s window still limits what Termux is asked to do.

The Termux script itself takes about a second once it has a serial to talk to. Pinning
survives the keeper re-enabling wireless debugging: adbd restarts with a fresh TLS port
*and* keeps listening on 5555 (measured: TLS 46015 and 5555 listening at the same time).

Caveats:

- `tcpip` mode is **unencrypted and authorised by RSA key only** — unlike wireless
  debugging, which is TLS. Use it on the network you control (the phone-link AP), never
  on a public one. `adb usb` removes it again.
- The property is not persistent: a reboot clears it, which is why the app re-pins at
  every boot instead of trying to make it survive on its own.
- Only needed if something wants the phone at a predictable port. The mount transport
  is sshd on 8022, which is fixed already.

## See also

[README](../README.md) · [Build](build.md) · [Install](install.md) · [Boot](boot.md) · [Architecture](architecture.md) · [Limits](limits.md) · [Diagnostics](diagnostics.md) · [Reboot verdict](reboot-verdict.md) · [Pre-unlock connect](preunlock-connect.md) · [Termux build](termux-build.md)
