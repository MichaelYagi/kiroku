// Root project: aggregation only. Module conventions live in buildSrc (kiroku.kotlin-library).
tasks.register("check-all") {
    group = "verification"
    dependsOn(subprojects.map { "${it.path}:check" })
}
