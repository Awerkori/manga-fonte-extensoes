import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manga Stop"
    versionCode = 49
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "pt-BR"
        baseUrl = "https://mangastop.net"
    }

    deeplink {
        path("/obra/..*")
        path("/leitor/..*")
        path("/manga/..*")
    }
}

android {
    sourceSets.named("test") {
        kotlin.directories.add("test")
        resources.directories.add("test/fixtures")
    }
}

tasks.matching { it.name.startsWith("ksp") && it.name.contains("UnitTest") }.configureEach {
    enabled = false
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
}
