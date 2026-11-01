plugins { id("kiroku.kotlin-library") }

description = "Kiroku optional exiftool deep-mode backend."

dependencies {
    api(project(":kiroku-core"))
    implementation(libs.jackson.core)
}
