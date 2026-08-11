import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
	alias(kei.plugins.extension)
}

keiyoushi {
	name = "Hwago"
	versionCode = 1
	contentWarning = ContentWarning.MIXED
	libVersion = "1.6"

	source {
		baseUrl = "https://02.hwago.xyz"
		lang = "id"
	}

	deeplink {
		path("/..*")
	}
}
