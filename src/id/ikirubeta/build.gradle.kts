import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Ikiru Beta"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"
    theme = "natsuid"

    source {
        lang = "id"
        baseUrl {
            custom("https://07.ikiru.wtf")
        }
    }
}
