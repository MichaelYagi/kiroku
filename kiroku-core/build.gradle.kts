plugins { id("kiroku.kotlin-library") }

description = "Kiroku core: public API, models, native metadata parsers and normalization."

dependencies {
    api(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    // Independent oracle for cross-checking native parser output (test scope only).
    testImplementation(libs.metadata.extractor)
    // Reads ExifTool oracle JSON in tests only.
    testImplementation(libs.jackson.databind)
}
