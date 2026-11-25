# Limits

```bash
./gradlew probe     # everything that can be checked without a phone
```


- The app cannot reconnect ADB; that is Termux's job (see the table in the [README](../README.md#why)).
- Pinning 5555 and switching Wi-Fi on need either the app's paired key (Gradle build)
  or Termux. The dependency-free `build.sh` APK has no in-app TLS client, so it always
  needs Termux for those two actions; without either it still keeps wireless debugging
  on and can open the Wi-Fi panel.
- `WifiNetworkSpecifier` (local-only mode) always needs a tap. Both modes need the saved
  passphrase unless the network is open or enhanced open (OWE).
- The TCP scan misses the live port if another scan runs at the same time — hence the
  single-instance guard. Avoid running `reconnect.sh` while the keepalive is scanning.
  With a serial attached, discovery reads `/proc/net/tcp` instead and no scan races.
- adbd only listens while a Wi-Fi network is connected; with Wi-Fi off there is nothing
  to connect to.

## See also

[README](../README.md) · [Build](build.md) · [Install](install.md) · [Control](control.md) · [Boot](boot.md) · [Architecture](architecture.md) · [Diagnostics](diagnostics.md) · [Reboot verdict](reboot-verdict.md) · [Pre-unlock connect](preunlock-connect.md) · [Termux build](termux-build.md)
