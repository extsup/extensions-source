package eu.kanade.tachiyomi.extension.id.hwago

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable

@Serializable
class SearchResponseDto {
    val results: List<SearchResultDto> = emptyList()
}

@Serializable
class SearchResultDto {
    val slug: String = ""
    val title: String = ""
    val coverImage: String? = null

    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = "/comic/$slug"
        this.title = this@SearchResultDto.title
        thumbnail_url = when {
            coverImage == null -> null
            coverImage.startsWith("http") -> coverImage
            else -> "$baseUrl/api/image/${coverImage.trimStart('/')}"
        }
    }
}