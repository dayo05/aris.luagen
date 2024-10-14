plugins {
    kotlin("jvm") version "2.0.0"
    id("com.google.devtools.ksp") version "2.0.0-1.0.21"
}

group = "me.ddayo"
version = "1.0-SNAPSHOT"

dependencies {
    api(project(":ap"))
    ksp(project(":ap"))
    testImplementation(kotlin("test"))
    testRuntimeOnly("party.iroiro.luajava:luajit-platform:4.0.2:natives-desktop")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}