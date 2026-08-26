plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android: AGP 9 provides built-in Kotlin, and applying
    // the Kotlin Android plugin alongside it is a hard build failure.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Stages the exported ECAPA-TDNN model into the packaged assets.
 *
 * The model is gitignored, so a fresh clone must run tools/onnx/export_ecapa.py
 * before the app will build. Staging through the build directory keeps the 80 MB
 * artifact out of the source tree and out of any chance of being committed.
 *
 * The source is declared as a FileCollection rather than an InputFile so a missing
 * model produces the instruction below instead of a bare "file does not exist".
 */
abstract class StageOnnxModelTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modelFile: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val source = modelFile.files.firstOrNull()
        if (source == null || !source.isFile) {
            throw GradleException(
                "Missing the exported model at tools/onnx/build/ecapa_tdnn.onnx\n" +
                    "Run: python tools/onnx/export_ecapa.py",
            )
        }
        val target = outputDirectory.get().asFile.resolve("models/ecapa_tdnn.onnx")
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        logger.lifecycle("staged model (${source.length() / (1024 * 1024)} MB) for packaging")
    }
}

val exportedModel = rootProject.layout.projectDirectory.file("tools/onnx/build/ecapa_tdnn.onnx")

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val stageModel = tasks.register<StageOnnxModelTask>("stage${suffix}OnnxModel") {
            modelFile.from(exportedModel)
        }
        // Variant API rather than android.sourceSets: AGP 9 refuses Provider
        // instances there, and this carries the task dependency automatically.
        variant.sources.assets?.addGeneratedSourceDirectory(
            stageModel,
            StageOnnxModelTask::outputDirectory,
        )
    }
}

android {
    namespace = "com.prabu.voicelock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.prabu.voicelock"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-m3"

        ndk {
            // ONNX Runtime ships a ~30 MB native library per ABI. Dropping x86 and
            // x86_64 removes 73 MB of APK that no physical phone loads. The cost is
            // that this will not run on a Windows-hosted emulator; per CLAUDE.md the
            // things worth testing here need a real device anyway. Add "x86_64" back
            // if an emulator is ever needed.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Under built-in Kotlin, jvmTarget is inherited from targetCompatibility.
    }

    androidResources {
        // Store the model uncompressed. It is protobuf and barely compresses (80.3 MB
        // -> 74.9 MB), and leaving it uncompressed lets ModelProvider read its length
        // from an asset descriptor instead of streaming all 80 MB to measure it.
        noCompress += "onnx"
    }

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG, which gates the model self-test affordance.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
