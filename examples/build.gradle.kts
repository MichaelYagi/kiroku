plugins { id("kiroku.kotlin-library") }

description = "Compiled usage examples, including the Shashin adapter sketch (not published)."

kotlin { explicitApi = null }

dependencies {
    implementation(project(":kiroku-core"))
    implementation(project(":kiroku-json"))
    runtimeOnly(project(":kiroku-exiftool"))
    runtimeOnly(project(":kiroku-ffprobe"))
}

// Examples are documentation, not a library: never publish them.
tasks.withType<AbstractPublishToMaven>().configureEach { enabled = false }
