import java.net.URI
import java.util.Locale
import org.gradle.api.GradleException

plugins {
    id("org.jetbrains.kotlin.jvm")
}

/**
 * The default `tflite-native-cpu` coordinate is a placeholder POM; JNI is in classifier JARs on Maven Central
 * (see https://repo1.maven.org/maven2/ai/djl/tflite/tflite-native-cpu/2.6.2/).
 */
fun tfliteNativeClassifier(): String {
    val os = System.getProperty("os.name").lowercase(Locale.US)
    val arch = System.getProperty("os.arch").lowercase(Locale.US)
    return when {
        os.contains("linux") && (arch == "amd64" || arch == "x86_64") -> "linux-x86_64"
        os.contains("linux") && arch.contains("aarch64") ->
            throw GradleException(
                "face-embedding-regression: Linux aarch64 has no published tflite-native-cpu:2.6.2 native JAR. " +
                    "Run on x86_64 or extend build.gradle.kts if you add a compatible native artifact.",
            )
        os.contains("mac") || os.contains("darwin") ->
            when (arch) {
                "x86_64", "amd64" -> "osx-x86_64"
                "aarch64" ->
                    throw GradleException(
                        "face-embedding-regression: Apple Silicon has no osx-aarch64 tflite-native-cpu:2.6.2 on Maven Central. " +
                            "Use an x86_64 JDK (Rosetta) or run this task on Linux x86_64.",
                    )
                else -> throw GradleException("face-embedding-regression: unsupported macOS architecture: $arch")
            }
        else ->
            throw GradleException(
                "face-embedding-regression: unsupported OS for bundled TFLite JNI ($os $arch). " +
                    "Published natives for 2.6.2: linux-x86_64, osx-x86_64.",
            )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // JVM TFLite: DJL bundles org.tensorflow.lite.Interpreter in tflite-engine (not the Android AAR).
    // JNI is *not* pulled via Gradle's default Maven coordinate: the unclassified tflite-native-cpu JAR is a
    // placeholder; natives live in classifier artifacts. Gradle often still resolves the placeholder, so we
    // download the classified JAR in syncTfliteNativeForRegression and append it to the JavaExec classpath only.
    implementation("ai.djl:api:0.27.0")
    implementation("ai.djl.tflite:tflite-engine:0.27.0")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    // Same slf4j-api major line as DJL’s transitive api; avoids SLF4J “StaticLoggerBinder” noise on stderr.
    runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
}

val resolvedTfliteNativeClassifier = tfliteNativeClassifier()
val tfliteNativeJar =
    layout.buildDirectory.file(
        "embedded-tflite-native/tflite-native-cpu-$resolvedTfliteNativeClassifier.jar",
    )

val syncTfliteNativeForRegression = tasks.register("syncTfliteNativeForRegression") {
    group = "build"
    description =
        "Downloads ai.djl.tflite:tflite-native-cpu:2.6.2:$resolvedTfliteNativeClassifier from Maven Central (JNI)."
    val url =
        URI(
            "https://repo1.maven.org/maven2/ai/djl/tflite/tflite-native-cpu/2.6.2/" +
                "tflite-native-cpu-2.6.2-$resolvedTfliteNativeClassifier.jar",
        ).toURL()
    val out = tfliteNativeJar.get().asFile
    outputs.file(out)
    doLast {
        out.parentFile.mkdirs()
        url.openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
    }
}

tasks.register<JavaExec>("faceEmbeddingRegressionTest") {
    group = "verification"
    description =
        "Local JVM face embedding regression on testdata/face_matching (same_persons/<id>/ or flat <id>/). " +
            "Writes build/face-matching-report/. Optional: -PfaceTestMode=diagnostic, -PfaceRegressionMinCosine=0.65"
    dependsOn(syncTfliteNativeForRegression)
    classpath = sourceSets["main"].runtimeClasspath + files(tfliteNativeJar)
    mainClass.set("com.virtualvolunteer.regression.jvm.FaceEmbeddingRegressionMainKt")
    workingDir = rootProject.layout.projectDirectory.asFile
    val outDir = rootProject.layout.buildDirectory.dir("face-matching-report").get().asFile
    val testdataRoot = rootProject.file("testdata/face_matching")
    val model = rootProject.file("app/src/main/assets/models/face_embedding.tflite")
    args(
        "--testdata-root",
        testdataRoot.absolutePath,
        "--model",
        model.absolutePath,
        "--out",
        outDir.absolutePath,
        "--mode",
        (rootProject.findProperty("faceTestMode")?.toString() ?: "strict"),
    )
    rootProject.findProperty("faceRegressionMinCosine")?.toString()?.let { v ->
        args("--min-cosine", v)
    }
    // Quiet DJL / slf4j-simple (default would print INFO from ai.djl.util.Platform on every run).
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    systemProperty("org.slf4j.simpleLogger.log.ai.djl", "warn")
    standardOutput = System.out
    errorOutput = System.err
}
