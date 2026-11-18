import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaLeap"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.mangaleap.com"
    }

    source {
        lang = "fr"
        baseUrl = "https://www.mangaleap.com"
    }

    source {
        lang = "ar"
        baseUrl = "https://www.mangaleap.com"
    }

    source {
        lang = "ru"
        baseUrl = "https://www.mangaleap.com"
    }

    source {
        lang = "es"
        baseUrl = "https://www.mangaleap.com"
    }

    source {
        lang = "pt-BR"
        baseUrl = "https://www.mangaleap.com"
    }

    source {
        lang = "th"
        baseUrl = "https://www.mangaleap.com"
    }

    deeplink {
        host("www.mangaleap.com")
        host("mangaleap.com")
        path("/en/..*/..*")
        path("/fr/..*/..*")
        path("/ar/..*/..*")
        path("/ru/..*/..*")
        path("/es/..*/..*")
        path("/pt/..*/..*")
        path("/th/..*/..*")
    }
}
