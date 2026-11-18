import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Reino Manga"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "Reino Mangá"
        baseUrl = "https://reinomanga.com.br"
        lang = "pt-BR"
    }

    deeplink {
        host("reinomanga.com.br")
        host("www.reinomanga.com.br")
        path("/manga/..*")
        path("/genero/..*")
        path("/scans/..*")
        path("/catalogo/..*")
        path("/catalogo")
    }
}
