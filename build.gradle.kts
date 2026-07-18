plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.teenkung.devstoragedrawer"
version = providers.gradleProperty("version").orElse("1.0.0-SNAPSHOT").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("org.geysermc.geyser:api:2.10.0-SNAPSHOT")

    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("26.1.2")
        jvmArgs("-Xms1G", "-Xmx2G")
    }

    processResources {
        val properties = mapOf("version" to project.version)
        inputs.properties(properties)
        filesMatching("plugin.yml") {
            expand(properties)
        }
    }
}
