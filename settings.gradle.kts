// A Gradle build alongside build.sh, for what Gradle does better: dependency
// resolution (a client library, if one is added), an IDE import, and a standard
// APK task. build.sh stays the build that runs on the phone without a download;
// this one is for a machine with time and network, and builds the same sources
// and the same root manifest.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // libadb-android (and its spake2 AAR) are published through JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "wireless-adb-keeper"
