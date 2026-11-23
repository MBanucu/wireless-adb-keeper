// The Gradle view of this app. build.sh remains the on-phone build: it compiles
// with javac, dexes with d8 and packages with aapt. This build exists so a
// dependency can be declared instead of hand-dexed, and so the tree imports
// cleanly into an IDE.
//
// The sources and the manifest stay where they are. The root AndroidManifest.xml
// is canonical because build.sh feeds it to aapt directly; AGP 9 owns the package
// name and the version fields now, so this build stages a copy with those
// attributes removed (and the <uses-sdk> element, which comes from defaultConfig).
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
