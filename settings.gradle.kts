rootProject.name = "kiroku"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "kiroku-core",
    "kiroku-exiftool",
    "kiroku-ffprobe",
    "kiroku-json",
    "kiroku-cli",
    "examples",
)
