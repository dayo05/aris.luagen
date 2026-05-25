plugins {
    kotlin("jvm")
    `maven-publish`
    `java-library`
}

group = "me.ddayo"
version = "1.0-SNAPSHOT"

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.5")
    implementation("party.iroiro.luajava:luajit:4.0.2")
    implementation("com.squareup:kotlinpoet:1.18.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
