import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Baozinho"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        baseUrl = "https://baozinho.com"
        lang = "pt-BR"
    }

    deeplink {
        path("/obras/..*")
    }
}

android {
    sourceSets.named("test") {
        kotlin.directories.add("test")
    }
}

dependencies {
    implementation(project(":lib:imagedecoder"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.stdlib)
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}
