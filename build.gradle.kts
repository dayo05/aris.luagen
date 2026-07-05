plugins {
    kotlin("jvm") version "2.3.0"
    id("com.google.devtools.ksp") version "2.3.5"
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
    api(project(":ap"))
    ksp(project(":ap"))
    testImplementation(kotlin("test"))
    implementation("party.iroiro.luajava:luajit:4.0.2")
    testRuntimeOnly("party.iroiro.luajava:luajit-platform:4.0.2:natives-desktop")
}

ksp {
    arg("export_api_schema", "true")
    arg("api_display_lang", "en|kr")
    arg("api_contexts.LuaGenerated", "base")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
