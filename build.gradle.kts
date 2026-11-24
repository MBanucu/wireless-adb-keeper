// The Gradle view of this app. Gradle is the canonical build: it adds
// src-pairing and libadb-android to the same src/ and the same root
// AndroidManifest.xml. build.sh remains the dependency-free bootstrap for a
// phone with no network and no Gradle. docs/termux-build.md records the
// contract between them.
//
// The sources and the manifest stay where they are. The root AndroidManifest.xml
// is canonical because build.sh feeds it to aapt directly; AGP 9 owns the
// package name and the version fields, so this build stages a parsed copy with
// those attributes removed (and the <uses-sdk> element, which comes from
// defaultConfig). The transformation is DOM-based, not regex-based, and
// checkStagedManifest fails the build if the result loses a component or a
// permission.

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Document
import org.w3c.dom.Element

plugins {
    // 9.x is the line that runs on Gradle 9.6+: AGP 8.x uses a Gradle internal
    // API that was removed there, and Termux packages Gradle 9.9.2.
    alias(libs.plugins.android.application)
}

val canonicalManifest = layout.projectDirectory.file("AndroidManifest.xml")

// Task classes in a build script are nested declarations, so they cannot call
// the script's own methods. The manifest helpers live in a nested object both
// the configuration code and the task actions can name.
object ManifestSupport {
    const val ANDROID = "http://schemas.android.com/apk/res/android"

    fun parse(file: File): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isIgnoringComments = false
        }.newDocumentBuilder().parse(file)

    fun attr(doc: Document, name: String, androidPrefix: Boolean = true): String? {
        val root = doc.documentElement
        val value = if (androidPrefix) {
            root.getAttributeNS(ANDROID, name)
        } else {
            root.getAttribute(name)
        }
        return value.takeIf { it.isNotEmpty() }
    }

    fun usesSdkAttr(doc: Document, name: String): String? {
        val nodes = doc.getElementsByTagName("uses-sdk")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val value = element.getAttributeNS(ANDROID, name)
            if (value.isNotEmpty()) return value
        }
        return null
    }
}

// Read once at configuration time; the build cannot describe itself without a
// package and an SDK level, so a malformed canonical manifest should fail now
// rather than after aapt or a manifest merger has run.
val canonicalManifestDocument: Document = ManifestSupport.parse(canonicalManifest.asFile)

// build.sh owns build/, so keep Gradle out of it.
layout.buildDirectory.set(file("build-gradle"))
val stagedManifest = layout.buildDirectory.file("manifest/AndroidManifest.xml")

/**
 * Rewrites the canonical manifest into the form AGP 9 expects. Using the DOM
 * instead of string replacement means a comment, an attribute order or a
 * namespace prefix cannot silently change what the build packages.
 */
abstract class StageManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonical: RegularFileProperty

    @get:OutputFile
    abstract val staged: RegularFileProperty

    @TaskAction
    fun stage() {
        val android = "http://schemas.android.com/apk/res/android"
        val document = ManifestSupport.parse(canonical.get().asFile)
        val root = document.documentElement

        // AGP 9 takes these from namespace/defaultConfig and warns about a
        // source manifest that still carries them. build.sh needs them in the
        // canonical file, so only the staged copy drops them.
        root.removeAttribute("package")
        root.removeAttributeNS(android, "versionCode")
        root.removeAttributeNS(android, "versionName")

        val usesSdk = document.getElementsByTagName("uses-sdk")
        while (usesSdk.length > 0) {
            val node = usesSdk.item(0)
            node.parentNode.removeChild(node)
        }

        val application = document.getElementsByTagName("application").item(0) as? Element
            ?: throw GradleException("${canonical.get().asFile} has no <application> element")

        // PairActivity comes from src-pairing, so only this build declares it;
        // build.sh keeps a manifest that matches its own sources.
        val activity = document.createElement("activity")
        activity.setAttributeNS(android, "android:name", ".PairActivity")
        activity.setAttributeNS(android, "android:exported", "false")
        application.appendChild(activity)

        val output = staged.get().asFile
        output.parentFile.mkdirs()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "utf-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        output.outputStream().use { stream ->
            transformer.transform(DOMSource(document), StreamResult(stream))
        }
    }
}

/**
 * The staged manifest is generated, so it needs the same acceptance test as a
 * source file: the components and permissions build.sh and the app depend on
 * must still be there. This runs before every compile and as part of `check`.
 */
abstract class CheckStagedManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun check() {
        val android = "http://schemas.android.com/apk/res/android"
        val document = ManifestSupport.parse(manifest.get().asFile)
        val root = document.documentElement
        val problems = mutableListOf<String>()

        if (root.hasAttribute("package")) {
            problems += "the root still has package= (AGP 9 takes it from namespace)"
        }
        if (root.hasAttributeNS(android, "versionCode") ||
            root.hasAttributeNS(android, "versionName")
        ) {
            problems += "the root still has android:versionCode/versionName (AGP owns them)"
        }
        if (document.getElementsByTagName("uses-sdk").length > 0) {
            problems += "a <uses-sdk> element survived (defaultConfig owns it)"
        }

        fun names(tag: String, attribute: String): Set<String> {
            val nodes = document.getElementsByTagName(tag)
            return (0 until nodes.length).mapNotNull { index ->
                (nodes.item(index) as? Element)?.getAttributeNS(android, attribute)
                    ?.takeIf { it.isNotEmpty() }
            }.toSet()
        }

        val activities = names("activity", "name")
        val expectedActivities = setOf(".MainActivity", ".PairActivity")
        (expectedActivities - activities).forEach {
            problems += "missing activity $it"
        }

        val permissions = names("uses-permission", "name")
        val expectedPermissions = setOf(
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.INTERNET",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "com.termux.permission.RUN_COMMAND",
        )
        (expectedPermissions - permissions).forEach {
            problems += "missing permission $it"
        }

        val providers = names("provider", "authorities")
        if ("io.github.mbanucu.wirelessadbkeeper.control" !in providers) {
            problems += "missing ControlProvider authority"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The staged manifest at ${manifest.get().asFile} is missing build contract elements:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nFix the canonical AndroidManifest.xml or the StageManifest task."
            )
        }
        logger.lifecycle(
            "staged manifest: ${activities.size} activities, ${permissions.size} permissions, " +
                "${providers.size} providers"
        )
    }
}

/**
 * Checks the Gradle environment before AGP produces a stack trace about it.
 * Not wired into `assemble`: an x86_64 CI runner uses AGP's own aapt2 and does
 * not need the Termux override. tools/termux-build.sh runs it first.
 */
@DisableCachingByDefault(because = "Reads the machine's build environment")
abstract class CheckBuildEnv : DefaultTask() {
    @get:Input
    abstract val compileSdk: Property<Int>

    @get:Input
    abstract val javaSpecificationVersion: Property<String>

    @get:Input
    abstract val osArch: Property<String>

    @get:Input
    abstract val javaHome: Property<String>

    @get:Input
    @get:Optional
    abstract val termuxPrefix: Property<String>

    @get:Input
    @get:Optional
    abstract val sdkFromEnvironment: Property<String>

    @get:Input
    @get:Optional
    abstract val aapt2Override: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localProperties: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        val jdk = javaSpecificationVersion.get().removePrefix("1.").substringBefore('.').toIntOrNull() ?: 0
        if (jdk < 17) {
            problems += "JDK $jdk is too old; use JDK 17 or newer (Termux: pkg install openjdk-21)"
        }

        val localPropertiesFile = localProperties.files.firstOrNull()?.takeIf { it.isFile }
        val localSdk = if (localPropertiesFile != null) {
            Properties().apply {
                localPropertiesFile.inputStream().use { load(it) }
            }.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val sdk = sdkFromEnvironment.orNull?.takeIf { it.isNotBlank() } ?: localSdk
        val platform = compileSdk.get()
        if (sdk == null) {
            problems += "no Android SDK: export ANDROID_HOME=/path/to/android-sdk, or copy " +
                "local.properties.example to local.properties and set sdk.dir"
        } else if (!File(sdk).isDirectory) {
            problems += "the Android SDK at $sdk does not exist; set ANDROID_HOME or " +
                "local.properties sdk.dir to a real SDK directory"
        } else {
            val androidJar = File(sdk, "platforms/android-$platform/android.jar")
            if (!androidJar.isFile) {
                problems += "Android platform $platform is not installed in $sdk; run " +
                    "sdkmanager \"platforms;android-$platform\""
            }
        }

        val aapt2 = aapt2Override.orNull?.takeIf { it.isNotBlank() }
        if (aapt2 == null) {
            // Termux's aapt2 is a bionic binary; AGP's arm64/x86_64 downloads
            // are glibc builds and do not run there. Other arm64 hosts (macOS,
            // glibc Linux) can use AGP's own aapt2.
            val onTermux = termuxPrefix.orNull?.contains("com.termux") == true ||
                javaHome.get().contains("com.termux")
            val arm64 = osArch.get().lowercase().let { it == "aarch64" || it == "arm64" }
            if (onTermux && arm64) {
                problems += "no aapt2 override on Termux; add this to " +
                    "~/.gradle/gradle.properties (Termux's aapt2 is bionic, AGP's is glibc): " +
                    "android.aapt2FromMavenOverride=\$PREFIX/bin/aapt2"
            }
        } else {
            val binary = File(aapt2)
            if (!binary.isFile) {
                problems += "android.aapt2FromMavenOverride points at $aapt2, which is not a file; " +
                    "use \$PREFIX/bin/aapt2 on Termux"
            } else if (!binary.canExecute()) {
                problems += "the aapt2 override $aapt2 is not executable; run chmod +x $aapt2"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The Android build environment is not ready:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nSee docs/termux-build.md for a working Termux setup."
            )
        }
        logger.lifecycle(
            "build environment: JDK $jdk, SDK $sdk, platform $platform, " +
                "aapt2 ${aapt2 ?: "AGP's own"}"
        )
    }
}

/**
 * The dependency policy is more than a version string: libadb-android must
 * have a SHA-256 in the verification metadata, so a re-published JitPack
 * artifact cannot enter a build unnoticed. tools/vendor-libadb.sh uses the
 * same checksums for a controlled mirror.
 */
abstract class CheckDependencyPolicy : DefaultTask() {
    @get:Input
    abstract val libadbVersion: Property<String>

    @get:Input
    abstract val generatingMetadata: Property<Boolean>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val verificationMetadata: ConfigurableFileCollection

    @TaskAction
    fun check() {
        // `--write-verification-metadata` runs this task before the file it
        // checks exists. Skipping here keeps the generating build working; the
        // next ordinary build validates the committed file.
        if (generatingMetadata.get()) {
            logger.lifecycle("dependency policy: skipped while writing verification metadata")
            return
        }
        val metadata = verificationMetadata.files.singleOrNull()
            ?: throw GradleException("no verification metadata file is configured")
        if (!metadata.isFile) {
            throw GradleException(
                "${metadata} is missing. Regenerate it with: " +
                    "./gradlew --write-verification-metadata sha256 assembleDebug"
            )
        }
        val text = metadata.readText()
        val version = libadbVersion.get()
        val component = "group=\"com.github.MuntashirAkon\" name=\"libadb-android\" version=\"$version\""
        if (!text.contains(component)) {
            throw GradleException(
                "${metadata} has no component entry for libadb-android $version. " +
                    "Regenerate it after a version bump, and record why in gradle/libs.versions.toml."
            )
        }
        val artifact = "libadb-android-$version.aar"
        if (!text.contains(artifact)) {
            throw GradleException("${metadata} has no checksum for $artifact")
        }
        logger.lifecycle("dependency policy: libadb-android $version has a verification checksum")
    }
}

val stageManifest = tasks.register<StageManifest>("stageManifest") {
    description = "Stages the canonical manifest in the form AGP expects."
    canonical.set(canonicalManifest)
    staged.set(stagedManifest)
}

val checkStagedManifest = tasks.register<CheckStagedManifest>("checkStagedManifest") {
    group = "verification"
    description = "Checks the staged manifest still has the expected activities and permissions."
    manifest.set(stagedManifest)
    dependsOn(stageManifest)
}

val checkBuildEnv = tasks.register<CheckBuildEnv>("checkBuildEnv") {
    group = "verification"
    description = "Fails early, with one message, when the JDK, SDK, compile SDK or aapt2 is missing."
    compileSdk.set(libs.versions.compileSdk.map(String::toInt))
    javaSpecificationVersion.set(providers.systemProperty("java.specification.version"))
    osArch.set(providers.systemProperty("os.arch"))
    javaHome.set(providers.systemProperty("java.home"))
    termuxPrefix.set(providers.environmentVariable("PREFIX"))
    sdkFromEnvironment.set(
        providers.environmentVariable("ANDROID_HOME").filter { it.isNotBlank() }
            .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    )
    aapt2Override.set(providers.gradleProperty("android.aapt2FromMavenOverride"))
    localProperties.from(layout.projectDirectory.file("local.properties"))
}

val checkDependencyPolicy = tasks.register<CheckDependencyPolicy>("checkDependencyPolicy") {
    group = "verification"
    description = "Checks libadb-android has a checksum in gradle/verification-metadata.xml."
    libadbVersion.set(libs.versions.libadb)
    generatingMetadata.set(gradle.startParameter.writeDependencyVerifications.isNotEmpty())
    verificationMetadata.from(layout.projectDirectory.file("gradle/verification-metadata.xml"))
}

android {
    // Everything below is read from the canonical manifest, so build.sh and
    // Gradle cannot disagree about the package or the version.
    namespace = ManifestSupport.attr(canonicalManifestDocument, "package", androidPrefix = false)!!
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = namespace
        minSdk = ManifestSupport.usesSdkAttr(canonicalManifestDocument, "minSdkVersion")!!.toInt()
        targetSdk = ManifestSupport.usesSdkAttr(canonicalManifestDocument, "targetSdkVersion")!!.toInt()
        versionCode = ManifestSupport.attr(canonicalManifestDocument, "versionCode")!!.toInt()
        versionName = ManifestSupport.attr(canonicalManifestDocument, "versionName")
    }

    sourceSets["main"].apply {
        manifest.srcFile(stagedManifest)
        // `src-pairing` needs libadb-android, so it is not part of the
        // dependency-free build.sh APK; it only joins the Gradle sources.
        java.directories.addAll(listOf("src", "src-pairing"))
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
// build.sh, whose APK keeps the Termux path and needs no dependency step. The
// coordinate and version live in gradle/libs.versions.toml.
dependencies {
    implementation(libs.libadb.android)
}

tasks.named("preBuild") {
    dependsOn(checkStagedManifest)
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
        file("src/io/github/mbanucu/wirelessadbkeeper/DiagFormat.java"),
        file("src/io/github/mbanucu/wirelessadbkeeper/DiagJournal.java"),
        file("src/io/github/mbanucu/wirelessadbkeeper/DiagRetention.java"),
        file("src/io/github/mbanucu/wirelessadbkeeper/DiagRotation.java"),
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

val probeDiag = tasks.register<JavaExec>("probeDiag") {
    group = "verification"
    description = "Runs DiagProbe: fake secrets through the journal append path; needs no device."
    dependsOn(compileProbes)
    classpath = files(probeClasses, bootClasspath)
    mainClass.set("io.github.mbanucu.wirelessadbkeeper.DiagProbe")
    args(layout.buildDirectory.dir("probe/diag").get().asFile.absolutePath)
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

val probeBuildTooling = tasks.register<Exec>("probeBuildTooling") {
    group = "verification"
    description = "Checks tools/check-wrapper.sh and the Termux build script's guards; needs no device."
    commandLine("bash", layout.projectDirectory.file("tools/build-tooling-test.sh").asFile.absolutePath)
}

val checkAdbHarness = tasks.register<Exec>("checkAdbHarness") {
    group = "verification"
    description = "Fails if mDNS discovery is forked out of the pinned adb-harness; needs no device."
    commandLine("bash", layout.projectDirectory.file("tools/check-adb-harness.sh").asFile.absolutePath)
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
    description = "Runs the device-free unit suites, build/manifest checks and host probes."
    dependsOn(
        checkStagedManifest,
        checkDependencyPolicy,
        probeUnit,
        probeDiag,
        probeToken,
        probeResult,
        probeScripts,
        probeBuildTooling,
        checkAdbHarness,
    )
}

// `check` stays the standard entry point, and it now includes the probe. AGP
// registers `test` lazily (after this script runs), so it is matched rather than
// named; that keeps `./gradlew test` meaningful even though there is no JUnit
// dependency to run.
tasks.matching { it.name == "test" }.configureEach {
    dependsOn(checkStagedManifest, checkDependencyPolicy, probeUnit, probeDiag, probeToken, probeResult, probeScripts, probeBuildTooling, checkAdbHarness)
}
tasks.named("check") {
    dependsOn(checkStagedManifest, checkDependencyPolicy, probeUnit, probeDiag, probeToken, probeResult, probeScripts, probeBuildTooling, checkAdbHarness)
}
