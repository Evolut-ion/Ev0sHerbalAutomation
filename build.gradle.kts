plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.3.1"
    id("run-hytale")
}

group = findProperty("pluginGroup") as String? ?: "com.example"
version = findProperty("pluginVersion") as String? ?: "1.0.0"
description = findProperty("pluginDescription") as String? ?: "A Hytale plugin template"

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "hytale"
        url = uri("https://maven.hytale.com/release")
    }
    maven {
        name = "hytale-pre-release"
        url = uri("https://maven.hytale.com/pre-release")
    }
    maven {
        name = "cursemaven"
        url = uri("https://cursemaven.com")
    }
}

dependencies {
    // Hytale Server API (provided by server at runtime)
    val hytaleBuild = findProperty("hytale_build") as String? ?: "+"
    compileOnly("com.hypixel.hytale:Server:$hytaleBuild")

    // Also compile against the local HytaleServer.jar so method descriptors
    // match the actual runtime classes (avoids NoSuchMethodError on fastutil
    // return types that differ between the Maven artifact and the shipped jar).
    val hytaleHome = System.getProperty("user.home") + "/AppData/Roaming/Hytale"
    val patchline = "pre-release"
    val localJar = file("$hytaleHome/install/$patchline/package/game/latest/Server/HytaleServer.jar")
    if (localJar.exists()) {
        compileOnly(files(localJar))
    }

    // ArcIO mod (provided by server at runtime, optional)
    compileOnly("curse.maven:arcio-1473915:7692946")

    // HyUI — Hytale UI library (provided by server at runtime, optional)
    compileOnly("curse.maven:hyui-1431415:7603155")

    // Common dependencies (will be bundled in JAR)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains:annotations:24.1.0")
    
    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Configure server testing
runHytale {
    // TODO: Update this URL when Hytale server is available
    // Using Paper server as placeholder for testing the runServer functionality
    jarUrl = "https://fill-data.papermc.io/v1/objects/d5f47f6393aa647759f101f02231fa8200e5bccd36081a3ee8b6a5fd96739057/paper-1.21.10-115.jar"
}

tasks {
    // Configure Java compilation
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
    }
    
    // Configure resource processing
    processResources {
        filteringCharset = Charsets.UTF_8.name()
        
        // Replace placeholders in manifest.json
        val props = mapOf(
            "group" to project.group,
            "version" to project.version,
            "description" to project.description
        )
        inputs.properties(props)
        
        filesMatching("manifest.json") {
            expand(props)
        }
    }
    
    // Configure ShadowJar (bundle dependencies)
    shadowJar {
        archiveBaseName.set(rootProject.name)
        archiveClassifier.set("")
        
        // Relocate dependencies to avoid conflicts
        relocate("com.google.gson", "com.yourplugin.libs.gson")
        
        // Minimize JAR size (removes unused classes)
        minimize()
    }
    
    // Configure tests
    test {
        useJUnitPlatform()
    }
    
    // Make build depend on shadowJar
    build {
        dependsOn(shadowJar)
    }
}

// Configure Java toolchain
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
