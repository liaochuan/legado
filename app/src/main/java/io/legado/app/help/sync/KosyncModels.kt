package io.legado.app.help.sync

import com.google.gson.annotations.SerializedName
import java.io.IOException

data class KosyncProgressDto(
    val document: String? = null,
    val progress: String? = null,
    val percentage: Double? = null,
    val device: String? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    val timestamp: Long? = null,
)

data class KosyncMetadata(
    val filename: String? = null,
    val title: String? = null,
    val authors: String? = null,
)

data class KosyncListItem(
    val document: String? = null,
    val title: String? = null,
    val author: String? = null,
    val filename: String? = null,
    val progress: String? = null,
    val percentage: Double? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    val timestamp: Long? = null,
)

data class KosyncListResponse(
    val items: List<KosyncListItem> = emptyList(),
    val next: String? = null,
)

class KosyncException(
    message: String,
    val statusCode: Int? = null,
) : IOException(message)
