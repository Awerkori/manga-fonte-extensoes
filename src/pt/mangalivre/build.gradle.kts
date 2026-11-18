import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ToonLivre"
    versionCode = 96
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "ToonLivre"
        baseUrl = "https://toonlivre.net"
        lang = "pt-BR"
        id = 2834885536325274328L
        versionId = 2
    }

    deeplink {
        path("/..*")
    }
}

android {
    sourceSets.named("test") {
        kotlin.directories.add("test")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}
