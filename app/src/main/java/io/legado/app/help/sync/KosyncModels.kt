package io.legado.app.help.sync

import com.google.gson.annotations.SerializedName
import java.io.IOException

data class KosyncProgressDto(
    @SerializedName("document") val document: String? = null,
    @SerializedName("progress") val progress: String? = null,
    @SerializedName("percentage") val percentage: Double? = null,
    @SerializedName("device") val device: String? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("timestamp") val timestamp: Long? = null,
)

data class KosyncMetadata(
    @SerializedName("filename") val filename: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("authors") val authors: String? = null,
)

data class KosyncListItem(
    @SerializedName("document") val document: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("author") val author: String? = null,
    @SerializedName("filename") val filename: String? = null,
    @SerializedName("progress") val progress: String? = null,
    @SerializedName("percentage") val percentage: Double? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("timestamp") val timestamp: Long? = null,
)

data class KosyncListResponse(
    @SerializedName("items") val items: List<KosyncListItem> = emptyList(),
    @SerializedName("next") val next: String? = null,
)

class KosyncException(
    message: String,
    val statusCode: Int? = null,
) : IOException(message)
