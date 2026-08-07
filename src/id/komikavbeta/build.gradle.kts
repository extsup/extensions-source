import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Komikav Beta"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"   // ← sesuai permintaan Anda untuk kompatibilitas Android 6

    source {
        baseUrl = "https://komikav.net"
        lang = "id"
    }

    deeplink {
        path("/..*")
    }
}
