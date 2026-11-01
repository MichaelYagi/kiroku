import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-library`
    `maven-publish`
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    withSourcesJar()
}

// Java 17 bytecode and the Kotlin 2.0 API so that Java 17 / Kotlin 2.0 applications (e.g. Spring Boot 3.4 apps
// such as Shashin) can consume the jars. Compiling and testing on JDK 17 also catches use of newer JDK APIs.
// See 09-DECISIONS.md D6.
kotlin {
    jvmToolchain(17)
    explicitApi()
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjvm-default=all")
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    systemProperty("kiroku.fixtures", rootProject.file("fixtures").absolutePath)
    // Optional tool locations for integration tests; tests skip when absent.
    providers.environmentVariable("KIROKU_EXIFTOOL").orNull?.let { systemProperty("kiroku.exiftool", it) }
    providers.environmentVariable("KIROKU_FFPROBE").orNull?.let { systemProperty("kiroku.ffprobe", it) }
    // Test outcomes depend on which external tools are visible, so these are cache inputs: a cached
    // "tools absent" result must never be reused when the tools are present (or the other way round).
    for (name in listOf("KIROKU_NO_EXTERNAL", "KIROKU_EXIFTOOL", "KIROKU_FFPROBE", "PATH")) {
        inputs.property("env.$name", providers.environmentVariable(name).orElse(""))
    }
    inputs.property("exiftool.dev.present", rootProject.file(".tools/exiftool-dist/exiftool").exists())
    inputs.property("fixtures.external.present", rootProject.file("fixtures/external").list()?.size ?: 0)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

ktlint {
    version.set("1.8.0")
    filter { exclude { it.file.path.contains("/build/") } }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set(project.description)
                url.set("https://github.com/MichaelYagi/kiroku")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/MichaelYagi/kiroku")
                    connection.set("scm:git:https://github.com/MichaelYagi/kiroku.git")
                }
            }
        }
    }
}
