plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "1.3.6"
}

group = "dev.piglin"
version = "1.7.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    
}

dependencies {
    paperDevBundle("1.19.2-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }
}
