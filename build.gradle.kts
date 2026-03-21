plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

group = "com.github.tabmcp"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/IntelliJ IDEA.app/Contents")
        instrumentationTools()
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.spring.mvc")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = "253.*"
        }
    }
    buildSearchableOptions = false
}

kotlin {
    jvmToolchain(21)
}

// IDEA 2025.x requires nio-fs.jar on the boot classpath for MultiRoutingFileSystemProvider
val ideaContents = "/Applications/IntelliJ IDEA.app/Contents"
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Xbootclasspath/a:$ideaContents/lib/nio-fs.jar")
    }
}
