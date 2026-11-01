plugins {
    id("kiroku.kotlin-library")
    application
}

description = "Kiroku demo CLI (not required for embedding)."

kotlin { explicitApi = null }

dependencies {
    implementation(project(":kiroku-core"))
    implementation(project(":kiroku-exiftool"))
    implementation(project(":kiroku-ffprobe"))
    implementation(project(":kiroku-json"))
}

application {
    mainClass.set("dev.kiroku.cli.MainKt")
    applicationName = "kiroku"
}
