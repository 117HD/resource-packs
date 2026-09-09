plugins {
    java
}

group = "org.example"
version = "1.0"


apply<ManifestPlugin>()

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.8.2")
}

tasks.test {
    useJUnitPlatform()
}
