import okhttp3.internal.wait

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.6.20"
    id("org.jetbrains.intellij") version "1.7.0"
}

group = "io.github.singlerr"
version = "1.2-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies{
    implementation("com.google.guava:guava:31.1-jre")
    implementation("org.jetbrains:marketplace-zip-signer:0.1.8")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.0")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2022.1")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("com.intellij.java"))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
    }

    patchPluginXml {
        sinceBuild.set("213")
        untilBuild.set("223.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
task("cleanProject"){
    val targetWorkspace = File("../WakgoodFanGame")
    val ideaProjectFolder = File("../")
    if(targetWorkspace.exists())
        targetWorkspace.deleteRecursively()

    ProcessBuilder("git","clone","https://github.com/Bafguit/WakgoodFanGame.git")
        .directory(ideaProjectFolder)
        .start()
        .also { it.waitFor() }
}