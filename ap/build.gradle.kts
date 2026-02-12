plugins {
    kotlin("jvm")
}

group = "me.ddayo"
version = "1.0-SNAPSHOT"

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.5")
    implementation("party.iroiro.luajava:luajit:4.0.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
