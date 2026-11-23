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
| `app_version` | the installed version name |
| `android` | Android release and SDK |
| `uptime_s` | `SystemClock.elapsedRealtime()` in seconds |
| `process_started_after_boot_s` | when this process started, relative to boot |
| `unlocked` | whether credential-protected storage is readable |
| `mode` | `auto` or `local` (saved connect mode) |
| `service` | monitor foreground service: `running` or `stopped` |
| `adb_wifi_enabled` | the `adb_wifi_enabled` global setting |
| `dev_settings` | the `development_settings_enabled` global setting |
| `wifi_connected` | whether any Wi-Fi transport is connected |
| `port_5555` | the fixed-port probe state, including the last script result |
| `pin_result` | the last `pin-adb-port.sh` RESULT line, or `none` |
| `wifi_result` | the last `wifi-on.sh` RESULT line, or `none` |
| `last_result` | the last local-only state detail, or `suggestion <id>: <status>` per profile in auto mode |
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
| `pin.result` | info | Termux returned a successful `pin-adb-port.sh` result |
| `pin.termux_missing` | warn | Termux is not installed; install it from F-Droid and set `allow-external-apps=true` |
| `pin.external_apps` | warn | Termux refused `RUN_COMMAND` because `allow-external-apps` is off; set it in `~/.termux/termux.properties` |
| `pin.no_permission` | warn | `com.termux.permission.RUN_COMMAND` is not granted; the detail carries the `pm grant` line |
| `pin.run_refused` | warn | the `RunCommandService` start was refused (background start, `allow-external-apps`, or grant) |
| `pin.script_failed` | warn | `pin-adb-port.sh` exited non-zero or printed an `error=` RESULT line |
| `pin.no_result` | warn | Termux returned a bundle with no `RESULT` line |
| `pin.port_closed` | warn | the script reported success, but `127.0.0.1:5555` still does not answer |
| `pin.waiting` | info | Termux was asked to pin and the port or result is still pending |
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
| `wifi.on.no_termux` | warn | Termux is unavailable for the Wi-Fi-on button; switch Wi-Fi on by hand |
| `wifi.on.run_refused` | warn | the Wi-Fi-on `RUN_COMMAND` was refused; set `allow-external-apps=true` and check the grant |
| `wifi.ssid.missing` | warn | `save` was called without an SSID |
| `wifi.profile.full` | warn | the profile store is at its cap; remove a saved network first |
| `wifi.profile.save_failed` | warn | the profile store refused the save (named error in the detail) |
| `local.unsupported` | warn | local-only mode needs Android 10 or newer |
| `local.no_permission` | warn | `ACCESS_FINE_LOCATION` is missing; the detail carries the `pm grant` line |
| `local.service_refused` | warn | the monitor service refused to start for Connect now |
| `local.wifi_off` | warn | the Wi-Fi radio is off |
| `local.no_service` | error | `ConnectivityManager` is unavailable |
| `local.request_refused` | error | `requestNetwork` threw `SecurityException` |
| `local.request_failed` | error | `requestNetwork` threw another exception |
| `local.dialog_timeout` | warn | the per-request approval dialog timed out; a retry is scheduled |
| `local.attempts_exhausted` | warn | three approvals failed; approve in the dialog or tap Connect now |
| `key.created` | info | no ADB key was present; a new one was generated, so pair again |
| `key.load_failed` | error | the `pubkey` action could not load the key (including an unreadable file) |
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

## Secret canaries

The journal formatter scrubs `passphrase=…`, `token=…` and `pairing_code=…`
before a line is written or logged. No action passes those values in: pairing
state is journalled as success/failure, and a passphrase failure names only the
feature. `DiagReasonsTest` checks the formatter with canary values, and
`PermissionAuditTest` checks that no audit row contains a secret-shaped value.

## One event per failure

`Keeper` funnels every `error=…` return through `DiagReasons.fail`, which emits
exactly one event and returns the same sentence to the caller.
`DiagReasonsTest` counts the events for every code; the pin path journals the
specific result once and the follow-up state transition only repeats it when
the reason actually changes.
