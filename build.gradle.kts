plugins {
    kotlin("jvm") version "2.0.0"
}

group = "me.ddayo"
version = "1.0-SNAPSHOT"

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.0.0-1.0.21")
    implementation("party.iroiro.luajava:luajit:4.0.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}