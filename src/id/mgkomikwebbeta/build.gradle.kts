import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MGKomikWeb Beta"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        baseUrl = "https://web1.mgkomik.cc"
        lang = "id"
    }
}
// v2
