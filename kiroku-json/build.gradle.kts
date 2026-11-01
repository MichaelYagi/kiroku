plugins { id("kiroku.kotlin-library") }

description = "Kiroku optional JSON serialization with a stable schema."

dependencies {
    api(project(":kiroku-core"))
    implementation(libs.jackson.core)
    testImplementation(libs.jackson.databind)
}
