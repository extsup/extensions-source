import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hwago"
    versionCode = 5
    contentWarning = ContentWarning.MIXED

    source {
        lang = "id"
        baseUrl = "https://02.hwago.xyz"
    }
}
