plugins {
    application
    id("com.github.ngyewch.capsule")
}

dependencies {
    implementation("org.apache.commons:commons-lang3:3.12.0")
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("mypackage.MyApp")
}
