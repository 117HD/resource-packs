
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("commons-net:commons-net:3.8.0")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("com.beust:klaxon:5.6")
    implementation("org.kohsuke:github-api:1.307")
}

