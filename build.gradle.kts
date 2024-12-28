plugins {
    kotlin("jvm") version "1.6.10"
    id("com.google.devtools.ksp") version "1.6.10-1.0.4"
}

group = "me.ddayo"
version = "1.0-SNAPSHOT"

dependencies {
    api(project(":ap"))
    ksp(project(":ap"))
    testImplementation(kotlin("test"))
    implementation("party.iroiro.luajava:luajit:4.0.2")
    testRuntimeOnly("party.iroiro.luajava:luajit-platform:4.0.2:natives-desktop")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    // jvmToolchain(8)
}