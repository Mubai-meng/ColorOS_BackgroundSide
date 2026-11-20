// Builds aerath-stuff/AndroidVMTools from the git submodule (its own build needs AGP 9 / SDK 37
// and is not published on jitpack). CI updates the submodule to upstream HEAD on every build,
// so dependency versions are read from upstream's own version catalog to stay in sync.
plugins {
    alias(libs.plugins.android.library)
}

val upstream = "../external/AndroidVMTools"
val upstreamCatalog = file("$upstream/gradle/libs.versions.toml").readText()

fun upstreamVersion(key: String): String =
    Regex("""(?m)^\s*$key\s*=\s*"([^"]+)"""").find(upstreamCatalog)?.groupValues?.get(1)
        ?: error("version '$key' not found in $upstream/gradle/libs.versions.toml")

val panama = upstreamVersion("panama")

android {
    namespace = "com.v7878.vmtools"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("$upstream/consumer-rules.pro")
    }

    sourceSets["main"].java.srcDir("$upstream/src/main/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api("com.github.aerath-stuff.PanamaPort:Core:$panama")
    api("com.github.aerath-stuff.PanamaPort:Unsafe:$panama")
    api("com.github.aerath-stuff.PanamaPort:LLVM:$panama")
    implementation("io.github.vova7878:SunCleanerStub:${upstreamVersion("sun-cleaner")}")
    implementation("io.github.vova7878:R8Annotations:${upstreamVersion("r8-annotations")}")
}
