import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Shinigami Beta"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "id"
        baseUrl {
            custom("https://11.shinigami.asia")
        }
    }

    deeplink {
        host("11.shinigami.asia")
        path("/series/..*")
    }
}
