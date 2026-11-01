plugins { id("kiroku.kotlin-library") }

description = "Kiroku optional ffprobe deep-mode backend."

dependencies {
    api(project(":kiroku-core"))
    implementation(libs.jackson.core)
}
