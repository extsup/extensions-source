import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Kiryuu Beta"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"
    theme = "natsuid"

    source {
        lang = "id"
        baseUrl = "https://v7.kiryuu.to"
        id = 3639673976007021338L
    }
}
