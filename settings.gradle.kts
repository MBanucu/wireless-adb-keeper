// A Gradle build alongside build.sh. Gradle is the canonical, feature-complete
// build: it adds src-pairing and libadb-android on top of the same src/ and the
// same root AndroidManifest.xml. build.sh stays the dependency-free bootstrap
// for a phone with no network and no Gradle; neither may silently become the
// other. docs/termux-build.md records the contract.
import org.gradle.api.initialization.resolve.RepositoriesMode
import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Repositories belong here and only here. A project-level repositories {}
    // block would split resolution across two places; fail instead of silently
    // adding another source.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // A project-controlled mirror wins when someone has vendored the
        // dependency with tools/vendor-libadb.sh. The directory is optional; an
        // absent directory is simply an empty repository.
        maven {
            name = "projectVendor"
            url = uri("gradle/vendor")
            content {
                includeGroup("com.github.MuntashirAkon")
                includeGroup("com.github.MuntashirAkon.spake2-java")
            }
        }
        google()
        mavenCentral()
        // libadb-android (and its spake2 AAR) are published through JitPack.
        // Restrict the group so this repository cannot silently serve anything
        // else. docs/termux-build.md has the controlled-mirror and vendoring
        // fallback for when JitPack is unavailable.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.MuntashirAkon")
                includeGroup("com.github.MuntashirAkon.spake2-java")
            }
        }
    }
}

// AGP fails with a generic "SDK location not found" when nothing is configured.
// Check the same sources first and print one actionable message instead. This
// runs while settings are evaluated, before the Android plugin is configured.
val sdkFromEnv = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
    ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
val sdkFromLocal = file("local.properties")
    .takeIf { it.isFile }
    ?.let { propertiesFile ->
        Properties().apply {
            propertiesFile.inputStream().use(::load)
        }.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
    }
val configuredSdk = sdkFromLocal ?: sdkFromEnv ?: throw GradleException(
    """
    Android SDK not found: set ANDROID_HOME, or sdk.dir in local.properties.

    On Termux, use the documented one-command build (it resolves
    ${'$'}HOME/android-sdk and Termux's bionic aapt2 for you):

        tools/termux-build.sh assembleDebug

    Or configure it yourself:

        export ANDROID_HOME="${'$'}HOME/android-sdk"
        ./gradlew assembleDebug

    local.properties.example is the template for the second path. The
    dependency-free bootstrap, ./build.sh, needs no SDK configuration at all.
    """.trimIndent()
)
if (!file(configuredSdk).isDirectory) {
    throw GradleException(
        "Android SDK '$configuredSdk' does not exist. " +
            "Set ANDROID_HOME to an SDK directory, or run tools/termux-build.sh."
    )
}

rootProject.name = "wireless-adb-keeper"
