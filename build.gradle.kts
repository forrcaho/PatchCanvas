// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.3.2" apply false
    // AGP 9 has built-in Kotlin support, so no org.jetbrains.kotlin.android here.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
