# Diagnostics: snapshot fields and reason codes

The Diagnostics page (and `content call --method diag`) is meant to answer "why
did it not work" without a laptop. It has two parts: a `key=value` snapshot of
the current state, and a journal whose every line is
`time uptime level code detail`.

## Snapshot fields

Every field is read independently. A field whose supplier throws (before the
first unlock, for example) renders as `unavailable` and does not remove the
fields around it.

| Field | Meaning |
|---|---|
| `app_version` | the installed version name and version code |
| `android` | Android release and SDK |
| `boot_id` | the boot counter, so a kept line can be tied to its boot |
| `uptime_s` | `SystemClock.elapsedRealtime()` in seconds |
| `process_started_after_boot_s` | when this process started, relative to boot |
| `oem.autostart` | the DokeOS boot gate as `allowed`, `blocked` or `unknown`, with the evidence; never a read of the vendor list (see below) |
| `unlocked` | whether credential-protected storage is readable |
| `mode` | `auto` or `local` (saved connect mode) |
| `service` | monitor foreground service: `running` or `stopped` |
| `verbose` | the opt-in verbose window: `off`, or the minutes and events left |
| `adb_wifi_enabled` | the `adb_wifi_enabled` global setting |
| `dev_settings` | the `development_settings_enabled` global setting |
| `wifi_connected` | whether any Wi-Fi transport is connected |
| `port_5555` | the fixed-port probe state, including the last script result |
| `pin_result` | the last `pin-adb-port.sh` RESULT line, or `none` |
| `wifi_result` | the last `wifi-on.sh` RESULT line when Termux took the fallback, or `none` |
| `wifi_client` | the last client that took a Wi-Fi-on request: `none`, `Wi-FiManager`, `app_tls`, `termux` or `panel` |
| `last_result` | the last local-only state detail, naming the profile as `local link: <ssid> (<id>)`, or `suggestion <id>: <status>` per profile in auto mode |
| `wifi_mode` | the saved mode's boot stage: `applied (user unlocked)`, `not applied (waiting for unlock)`, or `not applied (service stopped)` |
| `wifi_passphrase` | `saved=… missing=… unreadable=…` across the profiles, `no saved network`, or `unavailable (before unlock)`; an unreadable count carries `save the password again` |
| `suggestion_held` | how many suggestions Android currently reports this app holds, or `unavailable` (before unlock or on Android 10) |
| `perm.write_secure_settings` | granted / not granted, plus the exact `pm grant` line |
| `perm.fine_location` | granted / not granted, plus the exact `pm grant` line (not needed before Android 10) |
| `perm.run_command` | Termux `RUN_COMMAND` grant, plus the exact `pm grant` line; if Termux is missing, the install step comes first |
| `perm.notifications` | granted / not granted, plus how to allow notifications in system settings |
| `perm.termux` | Termux installed, or how to install it |
| `perm.termux_external_apps` | the requirement and command; deliberately not verifiable from this app |
| `key_present` | the ADB private key file exists |
| `key_certificate` | the self-signed certificate file exists |
| `journal_events` | how many entries the journal holds |
| `journal_bytes` | the journal file size |
| `journal_retention` | the journal-length setting: the default, last day, or off |
| `journal_previous` | the previous build and boot that the kept block came from, or `none` |

A row that is not granted contains the same sentence a failing action repeats,
so the page shows `WRITE_SECURE_SETTINGS` is revoked before any button is
pressed instead of only after a failed write.

### Why `allow-external-apps` says "not verifiable"

`allow-external-apps` lives in Termux's own private storage
(`~/.termux/termux.properties`). Android gives this app no way to read another
app's files, so the audit shows the requirement and the command. Claiming
"granted" without being able to check would turn a real failure into a wrong
diagnosis. Termux does report the condition when it refuses a `RUN_COMMAND`;
that becomes `pin.external_apps` in the journal.

### Per-profile suggestion records

Auto-connect keeps one applied record per profile in the encrypted slot
`suggestion_applied.<id>`. Each holds the SSID, security and passphrase that
were actually handed to Android, which is what Android 10 needs for an exact
removal. An upgrade moves the old single slot to the first profile, so the same
network is not registered twice.

A matching record is only treated as "active" when Android still reports holding
that SSID (`getNetworkSuggestions()`, API 30+). If the record survives a reboot
but the suggestion does not, the pass registers it again and journals
`wifi.suggest.missing`. An undecryptable record is kept rather than cleared:
the Keystore may be usable again later, and the ciphertext is the only object
Android 10 can match for removal. An undecryptable passphrase skips only that
profile (`wifi.suggest.decrypt_failed`) and the Network page shows
`password unreadable - save it again` instead of `no password`.

### Reboot and unlock

A reboot starts the monitor at `LOCKED_BOOT_COMPLETED`, before credential-
protected storage and the Keystore are readable. The saved mode is not applied
there and the app does not pretend it was. The stages are journalled:

| Code | Stage |
|---|---|
| `boot.received` | the boot action, with `before unlock` / `after unlock` |
| `svc.start` | the service started, with `unlocked=true/false` |
| `svc.mode.pending` | the saved mode cannot be applied yet (credential storage locked) |
| `svc.unlocked` | `ACTION_USER_UNLOCKED` arrived and the saved mode is applied |
| `svc.mode` | the mode was applied; in auto mode it carries the per-profile suggestion status |
| `svc.mode.failed` | the unlock still could not apply the mode after the bounded retries |
| `svc.stop` | the monitor stopped, so the mode is not kept |
| `wifi.suggest.locked` | a suggestion pass was asked for before the first unlock |
| `wifi.suggest.missing` | the record matched but Android no longer held the suggestion; it was registered again |
| `wifi.suggest.job` | the persisted job re-registered the suggestions because the monitor service was stopped |
| `wifi.suggest.job_failed` | that stopped-monitor fallback failed |
| `wifi.suggest.decrypt_failed` | a profile's passphrase cannot be decrypted; save it again |

The unlock always re-applies the saved mode, even when an earlier start command
already did: re-registering an identical record is skipped by
`SuggestionRegistry`, so the pass is cheap and the reboot case cannot be missed.

### Two independent boot gates (DokeOS)

A reboot on the XPLORE 2 has two independent gates. Neither one substitutes for
the other:

| Gate | Where | What it decides | How to verify |
|---|---|---|---|
| DokeOS Auto Start Management | **System Manager → App Management → Auto Start Management**, the app's row | whether the package may receive `LOCKED_BOOT_COMPLETED`/`BOOT_COMPLETED` at boot at all | `./keeper.sh diag \| grep oem.autostart` and the first `boot.received` line says `before unlock` |
| Doze / battery exemption | **Settings → Apps → Wireless ADB Keeper → Battery → Unrestricted** (or `adb shell cmd deviceidle whitelist +io.github.mbanucu.wirelessadbkeeper`) | whether the monitor is kept alive after boot | `adb shell dumpsys deviceidle whitelist \| grep io.github.mbanucu.wirelessadbkeeper` |

With the autostart row off, the vendor leaves the package `stopped=true` at boot
and logs `BVPFWLogger: AppAutoStartupPolicy: prevent start receiver of package
...`. The app-targeted boot broadcasts are then enqueued only when the app is
opened by hand: on the 2026-11-24 boot that was +238 s, with
`process_started_after_boot_s=238`, while an allowlisted boot delivered at
~27 s, before unlock.

`oem.autostart` derives its state from that delivery, because the vendor list
itself is behind `com.freeme.freemanager.permission.ACCESS_INTERFACE` and the
app cannot read it:

| State | Evidence |
|---|---|
| `allowed` | the first `boot.received` **of this boot** says `before unlock` |
| `blocked` | the first `boot.received` says `after unlock` **and** `process_started_after_boot_s >= 120`; the reason names the screen to enable |
| `unknown` | no `boot.received` for this boot, an after-unlock delivery before 120 s, an unreadable/empty journal, or no DokeOS manager detected |

The journal can outlive a reboot, so only lines at/after the current boot's
wall-clock start count; a previous boot's `before unlock` line cannot make this
boot look allowed. "Unknown" is deliberate: the app never reports `allowed`
from the absence of a block, and a short after-unlock start can simply be an
early unlock. `autostart-allow.sh --check "Wireless ADB Keeper"` reads the
screen itself on an unlocked phone; that is the only path that reports what the
vendor list says, and it is not on the app's snapshot.

### Local-only profile selection

Local-only mode requests one profile at a time, using the profile order as the
priority. A profile whose SSID is in `getScanResults()` is preferred over one
that is not; the first present profile wins. When no saved SSID is present and a
link is already up, that link is kept and no new request is made. A cycle tries
at most three present profiles, and a failed approval advances to the next
present profile instead of retrying the same one. The status names the profile
in use as `local link: <ssid> (<id>)`, and every transition emits one
`local.profile` event.

## Verbose logging

The **Verbose logging** switch on the Diagnostics page is off by default. It
lives in device-protected prefs (`diag_verbose`), so a tick before the first
unlock can already read it and the choice survives a reboot. While it is on, the
page shows a note and the journal also records the high-frequency paths that are
otherwise silent:

- every pin probe, with the port state, the Termux state and what the probe did
  (`diag.verbose.pin`);
- every observed settings change (`diag.verbose.observer`);
- every network callback transition, including the local-only link
  (`diag.verbose.network`);
- every notification update (`diag.verbose.notification`).

The window is bounded to one hour or 200 events, whichever comes first. When it
ends, `diag.verbose.expired` is written once and the window switches itself off;
a report therefore shows exactly where the noisy part stopped. With verbose off,
none of the four codes above is written, so the journal behaves as before.

## Sharing a report

**Copy diagnostics** puts the snapshot and the journal on the clipboard.
**Share diagnostics** sends the same text as one `ACTION_SEND` block, so a
report can go straight into a GitHub issue without a laptop. The block is, in
order: the `key=value` snapshot, `-- journal (oldest first) --`, and, when one
is kept, `-- previous build ... --` with that file's lines. Nothing else is
added, so the text in the issue is the text the page shows.

## Journal length and rotation

The **Journal length** radio buttons store the choice in device-protected prefs
(`diag_retention`), like the verbose toggle, so it is readable before the first
unlock and survives a reboot. It is read on every append, so a running monitor
follows a change.

| Setting | What is kept |
|---|---|
| Last 200 events or 32 KiB (default) | the newest 200 lines, and at most 32 KiB; whichever cap comes first |
| Last day | lines from the last 24 hours, still bounded by 32 KiB so verbose logging cannot fill the disk |
| Off | nothing: appends return before the file is opened, so the journal stops growing; the existing file is left as it is |

The default is the same 200 events / 32 KiB ring as before. Trimming happens on
append, from the front: the newest lines are the useful ones.

On the first append after a version change, `events.log` is moved to
`events.previous.log` and `previous.meta` records the version and boot the old
journal came from. Only the last previous file is kept, so a chain of updates
does not grow without limit. The page shows a **Previous build** section with
the label (`previous build 1.9 (10), boot 42: 12 events from the previous
install`) and the old lines, so a line from another install is never mistaken
for this boot's. A journal that predates this feature is labelled
`previous build unknown`: claiming it came from this build would be the exact
mistake the rotation avoids.

## Redaction probe

`./gradlew probe` runs `DiagProbe`, a host-only check with no device. It writes
a fake passphrase, control token and pairing code through the same
`DiagJournal.append` path `Diag.event` uses, then reads the file back and fails
loudly if any canary is present; it also checks that `off` creates no file and
leaves an existing file byte-identical. The formatting and retention decisions
live in `DiagFormat`, `DiagJournal`, `DiagRetention` and `DiagRotation`, which
have no Android dependency, so the probe can run against a temporary file.
`DiagJournalTest` repeats the canaries and the retention seams in the JVM
suite.

## Reason codes

Codes are stable `area.action` strings. Every failure site emits one event, so a
copied report can be searched by code. The detail is one sentence, capped by the
ring buffer, and never contains a passphrase, control token or pairing code.

| Code | Level | Meaning / fix |
|---|---|---|
| `boot.received` | info | the boot receiver ran |
| `boot.jobs` | info | the periodic and immediate ensure jobs were scheduled |
| `boot.start_failed` | error | the boot receiver could not start the monitor |
| `svc.start` | info | the monitor service was created, with the unlock state |
| `svc.unlocked` | info | the user unlocked; the saved Wi-Fi mode is applied |
| `svc.mode` | info | the saved mode was applied (auto or local-only) |
| `svc.connect` | info | a Connect now request was applied |
| `svc.start_refused` | warn | Android refused a background foreground-service start; use `adb shell am start-foreground-service -n <pkg>/.MonitorService` |
| `svc.start_failed` | error | the monitor service start threw for another reason |
| `ensure.no_permission` | warn | `WRITE_SECURE_SETTINGS` is missing; the detail carries the `pm grant` line |
| `ensure.no_wifi` | info | no Wi-Fi transport; the detail names the remembered SSID |
| `ensure.failed` | error | the wireless-debugging settings could not be read |
| `ensure.settings_refused` | error | the settings read worked but a `putInt` was refused |
| `ensure.changed` | info | this call enabled wireless debugging |
| `ensure.noop` | info | wireless debugging was already enabled |
| `pin.state` | info | the 5555 probe is listening (the state includes a fresh script result) |
| `pin.wait_for_wifi` | info | a service started before unlock has no Wi-Fi network yet; the first pin attempt waits for `ConnectivityManager` |
| `pin.blocked_locked` | info | the 5555 probe is closed, user 0 is locked, and the in-app TLS pin did not take; Termux is not asked until `ACTION_USER_UNLOCKED` because its home is credential-encrypted |
| `pin.result` | info | Termux returned a successful `pin-adb-port.sh` result |
| `pin.termux_missing` | warn | Termux is not installed; install it from F-Droid and set `allow-external-apps=true` |
| `pin.external_apps` | warn | Termux refused `RUN_COMMAND` because `allow-external-apps` is off; set it in `~/.termux/termux.properties` |
| `pin.no_permission` | warn | `com.termux.permission.RUN_COMMAND` is not granted; the detail carries the `pm grant` line |
| `pin.run_refused` | warn | the `RunCommandService` start was refused (background start, `allow-external-apps`, or grant) |
| `pin.script_failed` | warn | `pin-adb-port.sh` exited non-zero or printed an `error=` RESULT line |
| `pin.no_result` | warn | Termux returned a bundle with no `RESULT` line |
| `pin.port_closed` | warn | the script reported success, but `127.0.0.1:5555` still does not answer |
| `pin.waiting` | info | a transport was asked to pin and the port or result is still pending |
| `pin.tls_ok` | info | the in-app TLS client sent `tcpip:5555` over the app's trusted key |
| `pin.tls_timeout` | warn | the in-app TLS pin did not finish inside its bound |
| `pin.tls_untrusted` | warn | adbd does not trust the app key; pair again before relying on the in-app pin |
| `pin.tls_failed` | warn | the in-app TLS transport could not discover, connect, or send `tcpip`; Termux fallback remains |
| `pin.fallback` | warn | Termux predates result reporting, so the port probe is the only evidence |
| `script.result` | info/warn | a non-pin Termux script result (`wifi-on.sh`), with exit code and stderr |
| `script.no_result` | warn | Termux sent no result bundle at all |
| `wifi.suggest.no_service` | warn | `WifiManager` is unavailable (`-1`) |
| `wifi.suggest.decrypt_failed` | warn | a profile's stored passphrase cannot be decrypted; the detail names its id; save it again (`-2`) |
| `wifi.suggest.no_password` | warn | no passphrase is stored for a profile that needs one; the detail names its id (`-2`) |
| `wifi.suggest.build_failed` | error | a profile's suggestion could not be built; the detail names its id (`-3`) |
| `wifi.suggest.refused` | warn/error | Android refused or failed `addNetworkSuggestions` for a profile; the detail names its id and keeps the raw status (`-4`) |
| `wifi.suggest.added` | info | the profile's suggestion was accepted; the detail names its id |
| `wifi.suggest.record_unsealed` | warn | a profile's applied-suggestion record could not be encrypted; the detail names its id |
| `wifi.suggest.record_undecryptable` | warn | a profile's applied-suggestion record cannot be decrypted; the detail names its id; save the network again |
| `wifi.secret.undecryptable` | warn | the local-only passphrase cannot be decrypted; save it again |
| `wifi.secret.unsealable` | error | the Keystore could not encrypt a passphrase; save it again |
| `wifi.on.no_transport` | warn | neither the app's paired key nor Termux took the Wi-Fi-on request; switch Wi-Fi on by hand |
| `wifi.on.run_refused` | warn | the Wi-Fi-on `RUN_COMMAND` was refused; set `allow-external-apps=true` and check the grant |
| `wifi.tls_ok` | info | the in-app TLS client sent `shell:svc wifi enable` over the app's trusted key; `wifi_client=app_tls` |
| `wifi.manager_ok` | info | Android 9 or older accepted `setWifiEnabled(true)`; `wifi_client=Wi-FiManager` |
| `wifi.termux_asked` | info | the app asked Termux to run `wifi-on.sh`; `wifi_client=termux` |
| `wifi.tls_timeout` | warn | the in-app TLS Wi-Fi-on did not finish inside its bound; Termux remains the fallback |
| `wifi.tls_untrusted` | warn | adbd does not trust the app key for the Wi-Fi-on shell; pair again or Termux takes the fallback |
| `wifi.tls_failed` | warn | the in-app TLS Wi-Fi-on could not discover, connect or run the shell; Termux remains the fallback |
| `wifi.ssid.missing` | warn | `save` was called without an SSID |
| `wifi.profile.full` | warn | the profile store is at its cap; remove a saved network first |
| `wifi.profile.save_failed` | warn | the profile store refused the save (named error in the detail) |
| `profile.added` | info | `save` created a profile; the detail carries its id and SSID |
| `profile.updated` | info | a profile was saved, renamed or moved in the try order |
| `profile.removed` | info | `profile-rm` deleted a profile, its ciphertext and applied record |
| `profile.unknown` | warn | the id is not in the list; run `./keeper.sh profiles` and use the `id=...` it lists |
| `profile.priority.invalid` | warn | the priority is not a number in `1..profiles`; the detail carries the current range |
| `local.unsupported` | warn | local-only mode needs Android 10 or newer |
| `local.no_permission` | warn | `ACCESS_FINE_LOCATION` is missing; the detail carries the `pm grant` line |
| `local.service_refused` | warn | the monitor service refused to start for Connect now |
| `local.wifi_off` | warn | the Wi-Fi radio is off |
| `local.no_service` | error | `ConnectivityManager` is unavailable |
| `local.request_refused` | error | `requestNetwork` threw `SecurityException` |
| `local.request_failed` | error | `requestNetwork` threw another exception |
| `local.no_candidate` | info | no saved SSID is in the scan and no link is up; the detail lists the profiles tried |
| `local.profile` | info/warn | one local-only profile transition; the detail names its id, SSID and state (requesting, connected, lost, failed, kept) |
| `local.dialog_timeout` | warn | the per-request approval dialog timed out; a retry or the next present profile is scheduled |
| `local.attempts_exhausted` | warn | no automatic attempt is left: the three-attempt budget is used, or every present profile was tried; the detail names the profile and the profiles tried |
| `key.created` | info | no ADB key was present; a new one was generated, so pair again |
| `key.load_failed` | error | the `pubkey` action could not load the key (including an unreadable file) |
| `key.pair_unavailable` | warn | `pair`/`pair_verify` was called on the `build.sh` APK, which has no libadb; install the Gradle APK |
| `key.pair_bridge_failed` | error | the Gradle-only pairing class could not be called or threw; the detail names the cause |
| `key.certificate_created` | info | the self-signed certificate was created beside the key |
| `key.certificate_regenerated` | warn | the certificate did not match the current key and was replaced; pair again |
| `key.pair_ok` | info | pairing succeeded; the phone now trusts the app's key |
| `key.pair_invalid_code` | warn | the pairing code was not six digits |
| `key.pair_timeout` | warn | pairing timed out |
| `key.pair_failed` | error | pairing failed |
| `key.verify_ok` | info | the trusted-shell test connected |
| `key.verify_timeout` | warn | the trusted-shell test timed out |
| `key.verify_untrusted` | warn | adbd does not trust this key; pair again |
| `key.verify_failed` | error | the trusted-shell test failed for another reason |
| `mode.invalid` | warn | `mode` must be `auto` or `local` |
| `diag.verbose.on` | info | the verbose window was opened from the Diagnostics page (one hour / 200 events) |
| `diag.verbose.off` | info | the verbose window was closed from the Diagnostics page |
| `diag.verbose.expired` | info | the window reached one hour or 200 events; verbose switched itself off (exactly one entry) |
| `diag.verbose.pin` | info | verbose only: one per pin probe, with port state, Termux state and action |
| `diag.verbose.wifi` | info | verbose only: one per Wi-Fi-on attempt, naming the client that took it |
| `diag.verbose.observer` | info | verbose only: one per observed settings change |
| `diag.verbose.network` | info | verbose only: one per network callback transition |
| `diag.verbose.notification` | info | verbose only: one per notification update |
| `diag.retention.default` | info | the journal length was set back to the default 200 events / 32 KiB |
| `diag.retention.day` | info | the journal length was set to the last day |
| `diag.retention.off` | info | journal writing was switched off; the existing file is kept |

## Secret canaries

The `pair` action takes the 6-digit code as an extra and passes it only to
`Pairing`; `pair_verify` takes no secret. Both are shell-only, like `pubkey`, and
a token holder is refused. The code is never logged, journalled or echoed in a
reply; a success is `paired=true` (or `connected=true`) plus the screen's
sentence, and a failure is an `error=` line.

The journal formatter scrubs `passphrase=…`, `token=…` and `pairing_code=…`
before a line is written or logged. No action puts those values in a journal
detail: pairing state is journalled as success/failure, and a passphrase failure
names only the feature. `DiagReasonsTest` checks the formatter with canary values,
`PermissionAuditTest` checks that no audit row contains a secret-shaped value,
and `DiagJournalTest` plus the `DiagProbe` in `./gradlew probe` write canaries
through the real append path and assert the file contains none of them.

## One event per failure

`Keeper` funnels every `error=…` return through `DiagReasons.fail`, which emits
exactly one event and returns the same sentence to the caller.
`DiagReasonsTest` counts the events for every code; the pin path journals the
specific result once and the follow-up state transition only repeats it when
the reason actually changes.
