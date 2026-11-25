# Building and hardening the on-device Gradle build

This is the on-device counterpart to `.github/workflows/build.yml`: what a fresh
checkout needs on Termux, which of the two builds is canonical, how the
dependency and the wrapper are pinned, and which warnings come from the tools
rather than from this repository.

`build.sh` remains the no-network, no-Gradle bootstrap. Gradle is the canonical,
feature-complete build: it compiles `src-pairing/` and adds `libadb-android`.
Neither may silently become the other.

## What was verified here

Verified on 2026-11-24 on Termux aarch64 / Android 15, unrooted, with
`ANDROID_HOME` unset:

| item | value |
|---|---|
| Gradle | 9.9.2 (wrapper; `pkg install gradle` is the same line) |
| AGP | 9.5.2 (from `gradle/libs.versions.toml`) |
| JDK | OpenJDK 21.0.13 |
| SDK | `$HOME/android-sdk`, platform 35 |
| aapt2 | Termux 16.0.0.4, passed as `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2` |
| dependency | `com.github.MuntashirAkon:libadb-android:3.1.1`, vendored in `gradle/vendor/` and SHA-256 pinned |
| dependency | `org.lsposed.hiddenapibypass:hiddenapibypass:6.1` from Maven Central, SHA-256 pinned |
| canonical APK | `build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk` (8.8 MB) |
| bootstrap APK | `wifidebug.apk` (97 KB) |
| `./gradlew --offline probe` | BUILD SUCCESSFUL in 57 s (cold configuration cache) |
| `./gradlew --offline lintDebug` | BUILD SUCCESSFUL in 21 s |
| `./gradlew --offline assembleDebug` | BUILD SUCCESSFUL in 1 s (warm) |
| `bash build.sh` | BUILD SUCCESSFUL, about 9 s |
| configuration cache | clean cold 9 s, warm 1 s; clean with build cache 1 s |
| wrapper | `distributionSha256Sum` matches Gradle's published `.sha256` |

## The two builds

| build | command | APK | pairing | dependency |
|---|---|---|---|---|
| **canonical** | `tools/termux-build.sh` | `build-gradle/outputs/apk/debug/wireless-adb-keeper-debug.apk` | yes | `libadb-android` + `spake2` |
| bootstrap | `./build.sh` | `wifidebug.apk` | no | none |

`build.sh` exists for the phone that has no network, no Gradle and no SDK
configuration. It uses `javac`, `d8` and `aapt`, and the root
`AndroidManifest.xml` as both the packaging manifest and the source of
`--min-api`. It must keep working when the Gradle path cannot.

Gradle is canonical for everything else, including the pairing screen. It reads
the same package, `versionCode`, `versionName`, `minSdk` and `targetSdk` from the
canonical manifest, so the two builds cannot disagree about identity. The two
debug APKs use different signing keys, so one cannot update the other.

## A fresh checkout

### 1. JDK and Gradle

```bash
pkg install openjdk-21 gradle
java -version      # 17 or newer; 21 is what this project is tested with
gradle --version   # 9.9.2 here; the build needs 9.6+ for AGP 9.x
```

The checked-in wrapper is preferred. If the wrapper distribution is not in the
local Gradle cache and there is no network, `GRADLE=gradle
tools/termux-build.sh` uses the packaged Gradle instead.

### 2. Android SDK

Either rely on the Termux default:

```bash
# tools/termux-build.sh finds $HOME/android-sdk when nothing is exported
```

or point at the SDK explicitly:

```bash
export ANDROID_HOME="$HOME/android-sdk"
# or
cp local.properties.example local.properties
$EDITOR local.properties        # sdk.dir=/data/data/com.termux/files/home/android-sdk
```

`local.properties` is machine-specific, ignored by git and must never be
committed. `ANDROID_HOME`/`ANDROID_SDK_ROOT` wins when both are present, and the
settings preflight reports a missing or non-existent SDK before AGP does.

### 3. aapt2

AGP's downloadable aapt2 is a glibc build; Termux is bionic. Install Termux's
own and the wrapper will use it:

```bash
pkg install aapt
ls -l "$PREFIX/bin/aapt2"
```

`tools/termux-build.sh` passes `-Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2`
unless `~/.gradle/gradle.properties` or the command line already sets an
override. For direct `./gradlew` invocations, add the same property to the user
Gradle properties:

```bash
cat >> ~/.gradle/gradle.properties <<EOF
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
EOF
```

### 4. Build

```bash
tools/termux-build.sh                 # checkBuildEnv + assembleDebug, offline
OFFLINE=0 tools/termux-build.sh       # first run, allow downloads
tools/termux-build.sh lintDebug       # any task, same environment
tools/termux-build.sh --check         # print the resolved SDK and aapt2
```

The script exports `ANDROID_HOME`/`ANDROID_SDK_ROOT` for the Gradle process. It
does not write `local.properties`. When the SDK comes only from the environment
and there is no `local.properties`, it passes `--no-daemon`: Termux's Gradle
daemon cannot adopt a client's environment, so a daemon started earlier would
not see the export.

The same tasks by hand:

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew --offline checkBuildEnv probe lintDebug assembleDebug
```

### Environment failures

`checkBuildEnv` prints one block instead of an AGP stack trace:

```
The Android build environment is not ready:
  - no Android SDK: export ANDROID_HOME=/path/to/android-sdk, or copy ...
  - Android platform 35 is not installed in ...; run: sdkmanager "platforms;android-35"
  - no aapt2 override on Termux; add this to ~/.gradle/gradle.properties ...
  - the aapt2 override ... is not executable; run chmod +x ...
See docs/termux-build.md for a working Termux setup.
```

`tools/termux-build.sh` checks the same conditions before Gradle starts, so a
missing SDK or a missing Termux `aapt2` produces one actionable line even on a
completely fresh checkout. `tools/build-tooling-test.sh` proves those messages
and the argument passing without a device, SDK or Gradle.

## Versions live in one place

`gradle/libs.versions.toml` owns:

| key | used by |
|---|---|
| `agp` | the `android-application` plugin alias |
| `compileSdk` | Gradle and the checks in `settings.gradle.kts` / the tools script |
| `libadb` | the `libadb-android` library alias |
| `hiddenapibypass` | the hidden-API exemption alias used during pairing |

The Android package and version still come from `AndroidManifest.xml`, which
`build.sh` also feeds to `aapt`. That is deliberate: the manifest is the one
file both builds read, and moving the version into the catalog would make
`build.sh` depend on the catalog (or duplicate it).

## Dependencies

### What is pinned

`gradle/verification-metadata.xml` carries a SHA-256 for every resolved
artifact, including `libadb-android`, its transitive
`com.github.MuntashirAkon.spake2-java:spake2-android`, and
`hiddenapibypass`. Dependency verification
runs on every build; a changed artifact fails with Gradle's verification error
instead of silently entering the APK.

The two modules are also committed under `gradle/vendor/`, so the canonical
build no longer needs JitPack to resolve them. `tools/vendor-libadb.sh --check`
verifies the committed bytes against the metadata without a Gradle cache; CI
runs it before any Gradle work.

`./gradlew checkDependencyPolicy` is part of `probe` and `check`. It asserts
that the version in the catalog still has a component entry and an AAR checksum
in the metadata, so a version bump cannot quietly disable the pin.

Some entries are host-specific and are added by hand:

- The two POM checksums (`origin="Vendored"`): a Maven directory repository
  may be asked for the POM even though Gradle normally resolves the `.module`
  metadata. A metadata regeneration may drop them; keep them, or rerun
  `tools/vendor-libadb.sh` and add the two hashes again.
- The `aapt2` platform jars: Termux uses the override, so it never resolves
  them. CI's Ubuntu runner resolves `-linux.jar`; macOS and Windows resolve
  their own. The published hashes are listed for all of them.
- The rest of the file was regenerated with an empty `GRADLE_USER_HOME` so it
  covers what a clean CI runner downloads. Generating over the phone's warm
  cache missed metadata-only files (BOM POMs/modules, Kotlin build tools, test
  infrastructure) that a fresh runner still verifies. When regenerating, use a
  fresh cache:

  ```bash
  GRADLE_USER_HOME=$(mktemp -d) gradle --write-verification-metadata sha256 \
      check assembleDebug \
      -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2"
  ```

If a new host resolves an artifact that is not listed, Gradle fails closed.
Add its published SHA-256 (or, for a project mirror, the exact bytes you
served); do not disable verification.

There is no `gradle.lockfile`. A global lock would cover AGP's plugin classpath
and its many detached configurations, which changes with every AGP update, and
would be regenerated far more often than it protects anything. For a single
runtime dependency, per-artifact SHA-256 is the stronger pin: it fixes the bytes,
not just the coordinate. If more runtime dependencies are added, enable
dependency locking then rather than carrying a lockfile that mostly locks the
build tools.

### JitPack, the vendor tree, and the fallback

JitPack is a network and availability dependency, and its artifacts are not
immutable the way Maven Central's are. `gradle/vendor/` is committed to the
repository and is the project-controlled copy: `settings.gradle.kts` lists that
directory as the first repository, so the normal path never asks JitPack for
these two modules. The order is:

1. **The committed vendor tree.** Both modules and their POM/module metadata
   live in `gradle/vendor/`; every file's SHA-256 is in
   `verification-metadata.xml`, so a changed or truncated file fails
   verification instead of entering the APK. `tools/vendor-libadb.sh --check`
   validates the tree and needs no Gradle cache, so CI runs it on every build.
2. **The Gradle cache**, pinned by the same metadata. A checkout without the
   vendor directory falls back to this once it is warm, which is what makes
   `--offline` work.
3. **JitPack**, the upstream source. It is contacted only for a version the
   vendor tree does not carry (a deliberate bump) or when the tree is absent.
   `tools/vendor-libadb.sh` then copies the resolved bytes into `gradle/vendor/`
   after verifying them against the metadata:

   ```bash
   tools/vendor-libadb.sh
   tools/vendor-libadb.sh --check
   git add gradle/vendor
   ./gradlew --offline assembleDebug
   ```

4. **A controlled mirror.** Publish the same group/artifact/version to a Maven
   repository the project controls, add it ahead of JitPack in
   `settings.gradle.kts`, and keep the same verification metadata. The bytes
   must match the pinned hashes; a mirror that re-signs or rebuilds the AAR is a
   different dependency and needs a deliberate version bump and metadata
   regeneration.
5. **No dependency at all.** `./build.sh` still produces `wifidebug.apk` with
   the Termux path and no pairing screen. It is the honest fallback when the
   vendor tree, JitPack and the cache are all unavailable.

Bumping `libadb` is a deliberate change to the catalog, the metadata and the
vendor tree:

```bash
# 1. edit gradle/libs.versions.toml
# 2. resolve and regenerate the metadata (needs the new artifact from JitPack)
OFFLINE=0 tools/termux-build.sh --write-verification-metadata sha256 assembleDebug
# 3. refresh the committed vendor tree with the verified new bytes
tools/vendor-libadb.sh
# 4. verify the policy and the offline path
./gradlew --offline checkDependencyPolicy assembleDebug
```

A JitPack re-publication of the same version is exactly what the checksum is
for: the build fails, and the correct response is to compare the bytes and bump
the version, not to regenerate the metadata blindly.

## Wrapper

`gradle/wrapper/gradle-wrapper.properties` pins both the distribution URL and
its SHA-256:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.2-bin.zip
distributionSha256Sum=971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b
```

That hash was taken from `services.gradle.org`'s published `.sha256` for the
9.9.2 binary distribution and checked by `tools/check-wrapper.sh --online`. CI
runs two independent checks:

- `gradle/actions/wrapper-validation@v4` checks the checked-in
  `gradle-wrapper.jar` against Gradle's published JAR checksums.
- `tools/check-wrapper.sh --online` compares `distributionSha256Sum` with the
  distribution's published `.sha256`.

Offline, the same script still checks the URL scheme, the 64-hex format and the
JAR structure. `tools/build-tooling-test.sh` covers the rejection paths.

## Manifest staging

`AndroidManifest.xml` at the repository root is canonical for both builds. AGP 9
takes the package from `namespace`, owns `versionCode`/`versionName`, and
rejects or warns about a source manifest that still carries them, so Gradle
stages a transformed copy at `build-gradle/manifest/AndroidManifest.xml`.

The transformation is DOM-based, not string replacement: it removes the owned
attributes and `<uses-sdk>`, and appends the `.PairActivity` declaration that
`src-pairing` provides. `checkStagedManifest` then parses the result and fails
the build if any of these are missing:

- `.MainActivity` and `.PairActivity`;
- `WRITE_SECURE_SETTINGS`, `INTERNET`, `ACCESS_FINE_LOCATION`,
  `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE_SPECIAL_USE`,
  `POST_NOTIFICATIONS`, `com.termux.permission.RUN_COMMAND`;
- the `ControlProvider` authority.

It also asserts that `package=`, `android:versionCode`, `android:versionName`
and `<uses-sdk>` did not survive. It runs before every compile (`preBuild`) and
as part of `check` and `probe`, so adding a component to the canonical manifest
is enough for the bootstrap build, while a Gradle-only component needs a line in
`StageManifest` and, if it matters, in `CheckStagedManifest`.

## Configuration cache and build cache

Both are enabled in `gradle.properties`:

```properties
org.gradle.caching=true
org.gradle.configuration-cache=true
```

Measured on the phone after the dependency cache was warm:

| run | result |
|---|---|
| `rm -rf build-gradle .gradle/configuration-cache`, then `./gradlew --offline --configuration-cache --no-build-cache assembleDebug` | BUILD SUCCESSFUL in 9 s, entry stored |
| the same command again | BUILD SUCCESSFUL in 1 s, entry reused |
| `rm -rf build-gradle`, then `./gradlew --offline --configuration-cache --build-cache assembleDebug` | BUILD SUCCESSFUL in 1 s, 12 tasks `FROM-CACHE`, entry reused |

The custom tasks are cache-friendly or explicitly
`@DisableCachingByDefault` when they read the machine's environment
(`checkBuildEnv`). No project-owned Gradle 10 deprecations remain: the
`registering` property delegates and `srcDirs` calls were replaced with
`register<T>()` and `directories`.

## Warnings left upstream

`./gradlew --offline assembleDebug --warning-mode all` is clean for this
repository's Gradle 10 deprecations. What remains:

- **`Configuration.setVisible(boolean)`** is reported while AGP 9's built-in
  Kotlin support configures a Java-only project. It is scheduled for removal in
  **Gradle 11**, comes from the plugin (not from this build script), and does
  not affect the configuration cache. `android.builtInKotlin=false` removes it
  but makes AGP 9.5.2 fail to store a configuration cache entry
  (`processDebugNavigationResources` cannot serialize its navigation files), and
  the configuration cache matters more, so the upstream warning is left visible.
- **`android.aapt2FromMavenOverride` is experimental.** It is the documented
  Termux workaround. `android.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE`
  suppresses only that unsupported-option notice.
- **`javac` deprecation notes** are Android API compatibility choices in the
  app sources, not Gradle 10 deprecations.
- **`libspake2.so` cannot be stripped** because the AAR ships a pre-stripped
  native library; AGP packages it as-is. This is expected.

## Release shrinking

The release build has `isMinifyEnabled = false` and no resource shrinking, so
AGP 9's `--resource-path-shortening-map` path is not exercised and the reported
Termux aapt2 release-packaging bug is not on this build's path. If shrinking is
enabled later, do not trust a green `assembleRelease` alone: run `aapt dump
badging`/`unzip -l` on the release APK and assert the manifest, the resources
and `classes.dex` are present before shipping it. That check belongs here with
the release change, because the CI Ubuntu runner uses AGP's glibc aapt2 and
cannot reproduce a Termux aapt2 failure.

## CI and the on-device job

`.github/workflows/build.yml` has two jobs:

- **`tests`** — Ubuntu x86_64. Runs `tests/unit.sh` and the gate first (the
  phone path, no Gradle), then `./gradlew checkBuildEnv probe lintDebug
  assembleDebug`. It validates the wrapper JAR and the distribution hash before
  any Gradle work.
- **`termux`** — opt-in, `runs-on: [self-hosted, termux]`. Runs
  `OFFLINE=0 tools/termux-build.sh lintDebug assembleDebug`, the unit suites on
  the phone's JDK, and `bash build.sh`. It is skipped until the repository
  variable `TERMUX_RUNNER_ENABLED` is set to `true`.

### Registering the phone runner

The phone is both the host and the target, so the runner must not need a device
or a cable. Termux can run the standard Linux arm64 runner:

```bash
pkg install openjdk-21 gradle git curl aapt
mkdir -p ~/actions-runner && cd ~/actions-runner
# Take the current linux-arm64 release from
# https://github.com/actions/runner/releases (no version is pinned here).
runner_version=2.x.y
curl -fsSLO "https://github.com/actions/runner/releases/download/v${runner_version}/actions-runner-linux-arm64-${runner_version}.tar.gz"
tar xzf "actions-runner-linux-arm64-${runner_version}.tar.gz"
./config.sh --url https://github.com/MBanucu/wireless-adb-keeper \
    --token <runner-token> --labels self-hosted,termux
./run.sh
```

Set `ANDROID_HOME="$HOME/android-sdk"` (or create `local.properties`) for the
runner user, and keep the aapt2 override in `~/.gradle/gradle.properties`. Start the
runner from Termux's boot scripts or a `tmux`/`sv` service if it should survive
a reboot; GitHub only sees it while it is running. Then set the repository
variable:

```bash
gh variable set TERMUX_RUNNER_ENABLED --body true
```

The job is deliberately not enabled by default: a self-hosted runner on a public
repository is a trust decision, and a missing runner would otherwise leave the
job queued forever.

## Troubleshooting

| symptom | fix |
|---|---|
| `no Android SDK found` | export `ANDROID_HOME`, set `sdk.dir` in `local.properties`, or install the SDK at `$HOME/android-sdk` |
| `Android platform 35 is not installed` | `sdkmanager "platforms;android-35"` |
| `Termux's aapt2 was not found` | `pkg install aapt`; do not point at a glibc aapt2 |
| aapt2 exits with a loader error | the override is a glibc build; use Termux's bionic `aapt2` |
| `No cached version ... available for offline mode` on a first build | run once with `OFFLINE=0 tools/termux-build.sh` |
| `SDK location not found` although `ANDROID_HOME` is exported | the Gradle daemon did not inherit it; use `tools/termux-build.sh`, add `local.properties`, or pass `--no-daemon` |
| `checkDependencyPolicy` fails | regenerate `gradle/verification-metadata.xml` after a deliberate version bump |
| dependency verification fails after a JitPack rebuild | do not regenerate blindly; compare the bytes, bump the version, then regenerate |
| configuration cache "cannot be reused" after editing a build file | expected; the next run stores a new entry |

## See also

[README](../README.md) · [Build](build.md) · [Install](install.md) · [Control](control.md) · [Boot](boot.md) · [Architecture](architecture.md) · [Limits](limits.md) · [Diagnostics](diagnostics.md) · [Reboot verdict](reboot-verdict.md) · [Pre-unlock connect](preunlock-connect.md)
