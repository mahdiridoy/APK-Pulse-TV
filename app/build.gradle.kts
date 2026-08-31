import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.jetbrains.dokka.gradle.engine.parameters.KotlinPlatform
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kotlin.serialization)
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

abstract class GenerateGitHashTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val headFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    abstract val headsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val head = headFile.getOrNull()?.asFile

        val hash = try {
            if (head != null && head.exists()) {
                // Read the commit hash from .git/HEAD
                val headContent = head.readText().trim()
                if (headContent.startsWith("ref:")) {
                    val refPath = headContent.substring(5) // e.g., refs/heads/main
                    val commitFile = File(head.parentFile, refPath)
                    if (commitFile.exists()) commitFile.readText().trim() else ""
                } else headContent // If it's a detached HEAD (commit hash directly)
            } else "" // If .git/HEAD doesn't exist (e.g. building from a zip export, no git checkout)
        } catch (_: Throwable) {
            "" // Just set to an empty string if any exception occurs
        }.take(7) // Get the short commit hash

        val outFile = outputDir.file("git-hash.txt").get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(hash)
    }
}

val generateGitHash = tasks.register<GenerateGitHashTask>("generateGitHash") {
    val gitDir = layout.projectDirectory.dir("../.git")

    // Only wire these up as tracked inputs if they actually exist on disk. Gradle validates
    // that any *set* @InputFile/@InputDirectory value must exist, regardless of @Optional -
    // @Optional only permits the property to have no value at all. When building from a plain
    // zip export (no .git folder present), we simply never set them, so the task falls back to
    // an empty git hash instead of failing the build.
    if (gitDir.file("HEAD").asFile.exists()) {
        headFile.set(gitDir.file("HEAD"))
    }
    if (gitDir.dir("refs/heads").asFile.exists()) {
        headsDir.set(gitDir.dir("refs/heads"))
    }

    outputDir.set(layout.buildDirectory.dir("generated/git"))
}

android {
    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Looks like google likes to add metadata only they can read https://gitlab.com/IzzyOnDroid/repo/-/work_items/491
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    androidComponents {
        onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(
                generateGitHash,
                GenerateGitHashTask::outputDir
            )
        }
    }

    signingConfigs {
        // Stable local signing key so every build shares the same signature.
        // This lets Android install new builds over the old app without uninstalling
        // (which would wipe saves and plugins). The keystore lives at keystore/release.keystore
        // and is git-ignored. If you ever replace it, the first update after that will
        // require a reinstall.
        create("stable") {
            val localProperties = gradleLocalProperties(rootDir, project.providers)
            val keystorePath = localProperties["signing.keystore"] as String?
            storeFile = rootProject.file(keystorePath ?: "keystore/release.keystore")
            storePassword = (localProperties["signing.storePassword"] as String?) ?: "android"
            keyAlias = (localProperties["signing.keyAlias"] as String?) ?: "androiddebugkey"
            keyPassword = (localProperties["signing.keyPassword"] as String?) ?: "android"
        }
        // We just use SIGNING_KEY_ALIAS here since it won't change
        // so won't kill the configuration cache.
        if (System.getenv("SIGNING_KEY_ALIAS") != null) {
            create("prerelease") {
                val tmpFilePath = System.getProperty("user.home") + "/work/_temp/keystore/"
                val prereleaseStoreFile: File? = File(tmpFilePath).listFiles()?.first()

                storeFile = prereleaseStoreFile?.let { file(it) }
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.lagradost.cloudstream3"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode is generated from the build time (seconds since a fixed epoch).
        // This guarantees each new build has a HIGHER versionCode than the last, so Android
        // treats it as an update and installs over the existing app (data kept) instead of
        // demanding an uninstall.
        versionCode = ((System.currentTimeMillis() / 1000L) - 1_700_000_000L).toInt()
        versionName = libs.versions.versionName.get()

        manifestPlaceholders["target_sdk_version"] = libs.versions.targetSdk.get()

        // Reads local.properties
        val localProperties = gradleLocalProperties(rootDir, project.providers)

        buildConfigField(
            "long",
            "BUILD_DATE",
            "${System.currentTimeMillis()}"
        )
        buildConfigField(
            "String",
            "SIMKL_CLIENT_ID",
            "\"" + (System.getenv("SIMKL_CLIENT_ID") ?: localProperties["simkl.id"]) + "\""
        )
        buildConfigField(
            "String",
            "SIMKL_CLIENT_SECRET",
            "\"" + (System.getenv("SIMKL_CLIENT_SECRET") ?: localProperties["simkl.secret"]) + "\""
        )
        buildConfigField(
            "String",
            "MAL_KEY",
            "\"" + (System.getenv("MAL_KEY") ?: localProperties["mal.key"]) + "\""
        )
        buildConfigField(
            "String",
            "ANILIST_KEY",
            "\"" + (System.getenv("ANILIST_KEY") ?: localProperties["anilist.key"]) + "\""
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.toVersion(javaTarget.target)
        targetCompatibility = JavaVersion.toVersion(javaTarget.target)
    }

    java {
        // Use Java 17 toolchain even if a higher JDK runs the build.
        // We still use Java 8 for now which higher JDKs have deprecated.
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jdkToolchain.get()))
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Enables legacy JNI packaging to reduce APK size (similar to builds before minSdk 23).
            // Note: This may increase app startup time slightly.
            useLegacyPackaging = true
        }
    }

    namespace = "com.lagradost.cloudstream3"
}

// ============================================================================
// AUTO-INCREMENT VERSION & APK NAMING
// ============================================================================

// Task: Rename APK output to pulsestreamV{versionName}.apk after build.
tasks.register("renameApk") {
    description = "Renames APK output files to pulsestreamV{version}.apk"
    group = "versioning"
    val version = libs.versions.versionName.get()
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk").get().asFile
        if (!apkDir.exists()) return@doLast

        // Only rename main APKs — skip androidTest APKs
        val debugDir = File(apkDir, "debug")
        val releaseDir = File(apkDir, "release")

        listOf(debugDir, releaseDir).forEach { dir ->
            if (!dir.exists()) return@forEach
            dir.listFiles()
                ?.filter { it.isFile && it.extension == "apk" && !it.name.startsWith("pulsestream") }
                ?.forEach { apk ->
                    val newName = "pulsestreamV${version}.apk"
                    val target = File(apk.parentFile, newName)
                    if (apk.absolutePath != target.absolutePath) {
                        apk.renameTo(target)
                        println("✅ APK renamed: ${apk.name} → $newName")
                    }
                }
        }
    }
}

// Task: Auto-increment patch version after a successful build.
// This reads libs.versions.toml, bumps the patch number (e.g. 1.0.0.3 → 1.0.0.4),
// and writes it back so the NEXT build picks up the new version automatically.
tasks.register("autoIncrementVersion") {
    description = "Increments the patch version in libs.versions.toml after a successful build"
    group = "versioning"
    dependsOn("renameApk")
    doLast {
        val tomlFile = rootProject.file("gradle/libs.versions.toml")
        if (!tomlFile.exists()) {
            logger.warn("libs.versions.toml not found — skipping version increment.")
            return@doLast
        }

        val content = tomlFile.readText()

        // Find and increment versionName (format: versionName = "x.y.z")
        val q = "\""
        val versionPattern = "versionName = $q"
        val versionStart = content.indexOf(versionPattern)
        if (versionStart < 0) {
            throw GradleException("Could not find versionName in libs.versions.toml")
        }

        val valueStart = versionStart + versionPattern.length
        val valueEnd = content.indexOf(q, valueStart)
        val currentVersion = content.substring(valueStart, valueEnd)

        val parts = currentVersion.split(".")
        if (parts.size != 4) {
            throw GradleException("versionName must be in x.y.z.w format, got: $currentVersion")
        }

        val major = parts[0]
        val minor = parts[1]
        val patch = parts[2]
        val build = parts[3].toInt() + 1
        val newVersion = "$major.$minor.$patch.$build"

        var newContent = content.replace("versionName = $q$currentVersion$q", "versionName = $q$newVersion$q")
        tomlFile.writeText(newContent)

        // Also bump versionCode to stay in sync
        val codePattern = "versionCode = $q"
        val codeStart = newContent.indexOf(codePattern)
        if (codeStart >= 0) {
            val cvStart = codeStart + codePattern.length
            val cvEnd = newContent.indexOf(q, cvStart)
            val currentCode = newContent.substring(cvStart, cvEnd)
            val newCode = currentCode.toInt() + 1
            newContent = newContent.replace("versionCode = $q$currentCode$q", "versionCode = $q$newCode$q")
            tomlFile.writeText(newContent)
        }

        println("✅ Version incremented: $currentVersion → $newVersion")
        println("   APK name for this build: pulsestreamV$currentVersion.apk")
        println("   Next build version: pulsestreamV$newVersion.apk")
    }
}

// Make the auto-increment run after all assemble tasks complete successfully
tasks.configureEach {
    if (name.startsWith("assemble")) {
        finalizedBy("autoIncrementVersion")
    }
}

dependencies {
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.instancio.core)
    androidTestImplementation(libs.junit.ktx)
    androidTestImplementation(libs.kotlin.test)

    // Android Core & Lifecycle
    implementation(libs.core.ktx)
    implementation(libs.swiperefreshlayout)
    implementation(libs.activity.ktx)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.navigation)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.serialization.json) // JSON Parser

    // Design & UI
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Coil Image Loading
    implementation(libs.bundles.coil)

    // Media 3 (ExoPlayer)
    implementation(libs.bundles.media3)
    implementation(libs.video)

    // FFmpeg Decoding
    implementation(libs.bundles.nextlib)

    // Anime-db for filler
    implementation(libs.anime.db)

    // PlayBack
    implementation(libs.colorpicker) // Subtitle Color Picker
    implementation(libs.newpipeextractor) // For Trailers
    implementation(libs.juniversalchardet) // Subtitle Decoding

    // UI Stuff
    implementation(libs.shimmer) // Shimmering Effect (Loading Skeleton)
    implementation(libs.palette.ktx) // Palette for Images -> Colors
    implementation(libs.tvprovider)
    implementation(libs.overlappingpanels) // Gestures
    implementation(libs.biometric) // Fingerprint Authentication
    implementation(libs.previewseekbar.media3) // SeekBar Preview
    implementation(libs.qrcode.kotlin) // QR Code for PIN Auth on TV

    // Extensions & Other Libs
    implementation(libs.jsoup) // HTML Parser
    implementation(libs.ksoup) // HTML Parser
    implementation(libs.rhino) // Run JavaScript
    implementation(libs.safefile) // To Prevent the URI File Fu*kery
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio) // NIO Flavor Needed for NewPipeExtractor
    implementation(libs.conscrypt.android) // To Fix SSL Fu*kery on Android 9
    implementation(libs.jackson.module.kotlin) // JSON Parser
    implementation(libs.zipline)

    // Temp/deprecated; will be removed once extensions have time to migrate from using it
    implementation("com.google.code.gson:gson:2.11.0")
    // Deprecated; will be removed once extensions have time to migrate from using it
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Torrent Support
    implementation(libs.torrentserver)

    // Downloading & Networking
    implementation(libs.work.runtime.ktx)
    implementation(libs.nicehttp) // HTTP Lib
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Watch Together WebSocket

    implementation(project(":library"))
}

tasks.register<Jar>("androidSourcesJar") {
    archiveClassifier.set("sources")
    from(android.sourceSets.getByName("main").java.directories) // Full Sources
}

tasks.register<Copy>("copyJar") {
    dependsOn("build", ":library:jvmJar")
    from(
        "build/intermediates/compile_app_classes_jar/prereleaseDebug/bundlePrereleaseDebugClassesToCompileJar",
        "../library/build/libs"
    )
    into("build/app-classes")
    include("classes.jar", "library-jvm*.jar")
    // Remove the version
    rename("library-jvm.*.jar", "library-jvm.jar")
}

// Merge the app classes and the library classes into classes.jar
tasks.register<Jar>("makeJar") {
    // Duplicates cause hard to catch errors, better to fail at compile time.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(tasks.getByName("copyJar"))
    from(
        zipTree("build/app-classes/classes.jar"),
        zipTree("build/app-classes/library-jvm.jar")
    )
    destinationDirectory.set(layout.buildDirectory)
    archiveBaseName = "classes"
}

tasks.withType<KotlinJvmCompile> {
    compilerOptions {
        jvmTarget.set(javaTarget)
        jvmDefault.set(JvmDefaultMode.ENABLE)
        optIn.addAll(
            "com.lagradost.cloudstream3.InternalAPI",
            "com.lagradost.cloudstream3.Prerelease",
        )
    }
}

dokka {
    moduleName = "App"
    dokkaSourceSets {
        configureEach {
            suppress = name != "prereleaseDebug"
            analysisPlatform = KotlinPlatform.JVM
            displayName = "JVM"
            documentedVisibilities(
                VisibilityModifier.Public,
                VisibilityModifier.Protected
            )

            sourceLink {
                localDirectory = file("..")
                remoteUrl("https://github.com/recloudstream/cloudstream/tree/master")
                remoteLineSuffix = "#L"
            }
        }
    }
}
