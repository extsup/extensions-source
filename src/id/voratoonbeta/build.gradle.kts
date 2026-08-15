import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Voratoon Beta"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        baseUrl {
            custom("https://v1.voratoon.com")
        }
        lang = "id"
    }
}
