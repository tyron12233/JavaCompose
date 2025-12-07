import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Kotlin is required for Compose Desktop
    kotlin("jvm") version "2.1.0"
    // The main Compose Multiplatform plugin
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

group = "org.example"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
    // specific repo for Compose Multiplatform might be needed for dev versions,
    // but mavenCentral is usually sufficient for stable.
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")


    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    implementation(compose.preview)
}

val javacAddExports = listOf(
    "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
)

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MyComposeApp"
            macOS {
                bundleID = "org.example.mycomposeapp"
            }
        }

        // Pass the exports to the running application
        jvmArgs += javacAddExports
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs = javacAddExports;
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(javacAddExports);
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // Kotlin compiler requires -J prefix to pass args to the underlying JVM
//        freeCompilerArgs.addAll(javacAddExports.map { "-J$it" })
    }
}

tasks.test {
    useJUnitPlatform()
    // Pass exports to tests as well
    jvmArgs(javacAddExports)
}