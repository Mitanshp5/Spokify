// AGP 9.x has built-in Kotlin support, so `org.jetbrains.kotlin.android` is not applied.
// Kotlin/KSP are pinned on the buildscript classpath above AGP's bundled versions so the
// Compose compiler plugin and KSP processor match Kotlin 2.4.21.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.4.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}
