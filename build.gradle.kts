// Top-level build file — Lyreon, "Hear What Words Can't Say"
// CATATAN AGP 9.x: dukungan Kotlin sudah built-in di AGP; plugin
// 'org.jetbrains.kotlin.android' DILARANG diterapkan (fatal diagnostic KGP).
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
