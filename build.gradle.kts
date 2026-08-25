// The version catalog accessor is not available inside buildscript {}, so these two
// versions are duplicated from gradle/libs.versions.toml and must be kept in step
// with the kotlin and ksp entries there.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Raises KGP above the 2.2.10 that AGP 9.3.2 pins. Required because KSP only
        // supports built-in Kotlin from 2.3.1 onward and that line targets Kotlin 2.3+.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.11")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
