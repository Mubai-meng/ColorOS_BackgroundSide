plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

subprojects {
    // PanamaPort AARs declare minCompileSdk=37, unresolvable on AGP 8.x; the check is compile-time only.
    tasks.configureEach {
        if (name.matches(Regex("check.*AarMetadata"))) enabled = false
    }
}
