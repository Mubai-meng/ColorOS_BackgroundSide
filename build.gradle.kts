plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

subprojects {
    // PanamaPort AARs declare minCompileSdk=37, but ZygoteLoader's plugin targets AGP 8.x which
    // cannot resolve that platform; the check is compile-time only, so it is safe to skip.
    tasks.configureEach {
        if (name.matches(Regex("check.*AarMetadata"))) enabled = false
    }
}
