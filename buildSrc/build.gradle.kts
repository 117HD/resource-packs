
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.10")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.kohsuke:github-api:1.307")

    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.0.0-alpha.10")
    testImplementation("org.mockito:mockito-core:5.11.0")
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

tasks.test {
    useJUnitPlatform()
}
