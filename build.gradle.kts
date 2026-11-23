// The Gradle view of this app. build.sh remains the on-phone build: it compiles
// with javac, dexes with d8 and packages with aapt. This build exists so a
// dependency can be declared instead of hand-dexed, and so the tree imports
// cleanly into an IDE.
//
// The sources and the manifest stay where they are. The root AndroidManifest.xml
// is canonical because build.sh feeds it to aapt directly; AGP 9 owns the package
// name and the version fields now, so this build stages a copy with those
// attributes removed (and the <uses-sdk> element, which comes from defaultConfig).

import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    // 9.x is the line that runs on Gradle 9.6+: AGP 8.x uses a Gradle internal
    // API that was removed there, and Termux packages Gradle 9.9.2. This pair is
    // what an on-device `gradle assembleDebug` needs; CI can use it too.
    id("com.android.application") version "9.5.2"
}

val canonicalManifest = layout.projectDirectory.file("AndroidManifest.xml")
val manifestText = canonicalManifest.asFile.readText()

fun manifestAttr(name: String, androidPrefix: Boolean = true): String? =
    Regex("""${if (androidPrefix) "android:" else ""}$name="([^"]*)"""")
        .find(manifestText)?.groupValues?.get(1)

// build.sh owns build/, so keep Gradle out of it.
layout.buildDirectory.set(file("build-gradle"))
val stagedManifest = layout.buildDirectory.file("manifest/AndroidManifest.xml")

val stageManifest by tasks.registering {
    description = "Stages the root manifest in the form AGP expects."
    inputs.file(canonicalManifest)
    outputs.file(stagedManifest)
    doLast {
        val text = canonicalManifest.asFile.readText()
            .replace(Regex("""\s+package="[^"]*""""), "")
            .replace(Regex("""\s+android:versionCode="[^"]*""""), "")
            .replace(Regex("""\s+android:versionName="[^"]*""""), "")
            .replace(Regex("""\s*<uses-sdk[^>]*/>"""), "")
            // PairActivity comes from src-pairing, so only this build declares it;
            // build.sh keeps a manifest that matches its own sources.
            .replace("</application>", """    <activity
            android:name=".PairActivity"
            android:exported="false" />
    </application>""")
        val out = stagedManifest.get().asFile
        out.parentFile.mkdirs()
        out.writeText(text)
    }
}

android {
    // Everything below is read from the root manifest, so build.sh and Gradle
    // cannot disagree about the package or the version.
    namespace = manifestAttr("package", androidPrefix = false)!!
    compileSdk = 35

    defaultConfig {
        applicationId = namespace
        minSdk = manifestAttr("minSdkVersion")!!.toInt()
        targetSdk = manifestAttr("targetSdkVersion")!!.toInt()
        versionCode = manifestAttr("versionCode")!!.toInt()
        versionName = manifestAttr("versionName")
    }

    sourceSets["main"].apply {
        manifest.srcFile(stagedManifest)
        // `src-pairing` needs libadb-android, so it is not part of the
        // dependency-free build.sh APK; it only joins the Gradle sources.
        java.srcDirs("src", "src-pairing")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Nothing reads a generated BuildConfig or R, and leaving both out keeps the
    // APK reproducible (AGP otherwise embeds the absolute build path).
    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = true
    }

}

// Pairing (SPAKE2 + TLS) is the one thing not worth re-implementing, and it is
// the only runtime dependency. It is confined to src-pairing and never reaches
// build.sh, whose APK keeps the Termux path and needs no dependency step.
dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
}

tasks.named("preBuild") {
    dependsOn(stageManifest)
}

// ---------------------------------------------------------------------------
// Host probes (tools/)
//
// tools/AdbProbe.java, TokenProbe.java and ResultProbe.java are diagnostics that
// used to need a hand-written javac line. They are not part of the APK: they get
// their own compile step, against the same android.jar AGP compiles the app
// with, and a JavaExec task each. AdbProbe stays manual because it needs adbd
// on this phone; the identity, certificate and provider checks are now suites
// under tests/unit, so one gate (probeUnit) counts them. ControlToken imports
// android.content.Context, so the jar is on the probe classpath even though the
// probe never touches Context (the device-protected directory needs a device).
//
// No test dependency is declared on purpose: JUnit is not in the offline Gradle
// cache, so `test` would stop resolving on the phone. The unit suites and the
// remaining probes are the zero-dependency stand-in, and `test`/`check` depend
// on them.
// ---------------------------------------------------------------------------
val probeClasses = layout.buildDirectory.dir("probe/classes")
val bootClasspath = androidComponents.sdkComponents.bootClasspath

val compileProbes = tasks.register<JavaCompile>("compileProbes") {
    group = "verification"
    description = "Compiles tools/*.java and the src classes they exercise."
    source(
        fileTree("tools") { include("*.java") },
        file("src/io/github/mbanucu/wirelessadbkeeper/AdbKey.java"),
        file("src/io/github/mbanucu/wirelessadbkeeper/AdbClient.java"),
        file("src/io/github/mbanucu/wirelessadbkeeper/AdbIdentity.java"),
        file("src/io/github/mbanucu/wirelessadbkeeper/Base64Codec.java"),
        file("src/io/github/mbanucu/wirelessadbkeeper/ControlToken.java"),
        file("src/io/github/mbanucu/wirelessadbkeeper/TermuxResult.java"),
    )
    classpath = files(bootClasspath)
    destinationDirectory.set(probeClasses)
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.encoding = "UTF-8"
}

val probeToken = tasks.register<JavaExec>("probeToken") {
    group = "verification"
    description = "Runs TokenProbe (ControlToken); needs no device."
    dependsOn(compileProbes)
    classpath = files(probeClasses, bootClasspath)
    mainClass.set("io.github.mbanucu.wirelessadbkeeper.TokenProbe")
    args(layout.buildDirectory.dir("probe/token").get().asFile.absolutePath)
}

val probeResult = tasks.register<JavaExec>("probeResult") {
    group = "verification"
    description = "Runs ResultProbe (TermuxResult); needs no device."
    dependsOn(compileProbes)
    classpath = files(probeClasses, bootClasspath)
    mainClass.set("io.github.mbanucu.wirelessadbkeeper.ResultProbe")
}

val probePairLocal = tasks.register<Exec>("probePairLocal") {
    group = "verification"
    description = "Checks pair-local.sh with a fake adb and fake mDNS; needs no device."
    commandLine("bash", layout.projectDirectory.file("tools/pair-local-test.sh").asFile.absolutePath)
}

val probeDeviceTooling = tasks.register<Exec>("probeDeviceTooling") {
    group = "verification"
    description = "Checks tests/device.sh with a fake adb, capture and connect; needs no device."
    commandLine("bash", layout.projectDirectory.file("tools/device-tooling-test.sh").asFile.absolutePath)
}

val probeScripts = tasks.register<Exec>("probeScripts") {
    group = "verification"
    description = "Checks the scripts' final RESULT line with a fake adb; needs no device."
    dependsOn(probePairLocal, probeDeviceTooling)
    commandLine("bash", layout.projectDirectory.file("tools/result-contract.sh").asFile.absolutePath)
}

val probeUnit = tasks.register<Exec>("probeUnit") {
    group = "verification"
    description = "Runs the zero-dependency JVM suites under tests/unit; needs no device."
    commandLine("bash", layout.projectDirectory.file("tests/unit.sh").asFile.absolutePath)
}

val probeAdb = tasks.register<JavaExec>("probeAdb") {
    group = "verification"
    description = "Runs AdbProbe against adbd on the loopback; needs a phone and is never part of CI."
    dependsOn(compileProbes)
    classpath = files(probeClasses, bootClasspath)
    mainClass.set("io.github.mbanucu.wirelessadbkeeper.AdbProbe")
    args(
        providers.gradleProperty("adbKey")
            .orElse("${System.getProperty("user.home")}/.android/adbkey").get(),
        providers.gradleProperty("adbPort").orElse("5555").get(),
        providers.gradleProperty("adbPinPort").orElse("5555").get(),
    )
    if (providers.gradleProperty("adbTcpip").map { it.toBoolean() }.orElse(false).get()) {
        args("--tcpip")
    }
}

// One name for "run what can run without a phone". probeAdb stays out: it would
// fail on a machine with no adbd listening on 5555.
tasks.register("probe") {
    group = "verification"
    description = "Runs the device-free unit suites and host probes (scripts, TokenProbe, ResultProbe)."
    dependsOn(probeUnit, probeToken, probeResult, probeScripts)
}

// `check` stays the standard entry point, and it now includes the probe. AGP
// registers `test` lazily (after this script runs), so it is matched rather than
// named; that keeps `./gradlew test` meaningful even though there is no JUnit
// dependency to run.
tasks.matching { it.name == "test" }.configureEach {
    dependsOn(probeUnit, probeToken, probeResult, probeScripts)
}
tasks.named("check") {
    dependsOn(probeUnit, probeToken, probeResult, probeScripts)
}
