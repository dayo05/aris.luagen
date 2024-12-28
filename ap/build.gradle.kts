plugins {
    kotlin("jvm")
}

group = "me.ddayo"
version = "1.0-SNAPSHOT"

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:1.6.10-1.0.4")
    implementation("party.iroiro.luajava:luajit:4.0.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    // jvmToolchain(8)
}