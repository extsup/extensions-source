import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Kiryuu Beta"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"
    theme = "natsuid"

    source {
        lang = "id"
        baseUrl {
            custom("https://v7.kiryuu.to")
        }
        id = 492955672069898297L
    }
}
