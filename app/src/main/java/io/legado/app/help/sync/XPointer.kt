package io.legado.app.help.sync

data class XPointer(
    val fragmentIndex: Int?,
    val steps: List<XPointerStep>,
    val point: Int?,
)

sealed interface XPointerStep {
    data class Element(val name: String, val index: Int?) : XPointerStep
    data class Text(val index: Int?) : XPointerStep
}

object XPointerParser {

    private val prefixRegex =
        Regex("""^/body/DocFragment(?:\[(\d+)])?/body(.*)$""")
    private val textRegex =
        Regex("""^text\(\)(?:\[(\d+)])?$""")
    private val elementRegex =
        Regex("""^([A-Za-z0-9:_-]+)(?:\[(\d+)])?$""")

    fun parse(raw: String): XPointer? {
        val match = prefixRegex.matchEntire(raw) ?: return null
        val fragmentIndex = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (match.groupValues[1].isNotEmpty() && (fragmentIndex == null || fragmentIndex <= 0)) {
            return null
        }
        val remainder = match.groupValues[2]
        val segments = remainder.removePrefix("/")
            .split('/')
            .filter { it.isNotEmpty() }
        var point: Int? = null
        val steps = ArrayList<XPointerStep>()
        for ((index, rawSegment) in segments.withIndex()) {
            var segment = rawSegment
            if (index == segments.lastIndex) {
                val dot = segment.lastIndexOf('.')
                if (dot > 0 && dot < segment.length - 1) {
                    val suffix = segment.substring(dot + 1)
                    if (suffix.all { it.isDigit() }) {
                        point = suffix.toIntOrNull()
                        segment = segment.substring(0, dot)
                    }
                }
            }
            val step = parseStep(segment) ?: return null
            steps.add(step)
        }
        return XPointer(fragmentIndex, steps, point)
    }

    private fun parseStep(segment: String): XPointerStep? {
        textRegex.matchEntire(segment)?.let { match ->
            val index = match.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (match.groupValues[1].isNotEmpty() && (index == null || index <= 0)) return null
            return XPointerStep.Text(index)
        }
        elementRegex.matchEntire(segment)?.let { match ->
            val index = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (match.groupValues[2].isNotEmpty() && (index == null || index <= 0)) return null
            return XPointerStep.Element(match.groupValues[1].lowercase(), index)
        }
        return null
    }

    fun serialize(pointer: XPointer): String = buildString {
        append("/body/DocFragment")
        pointer.fragmentIndex?.let { append('[').append(it).append(']') }
        append("/body")
        for (step in pointer.steps) {
            when (step) {
                is XPointerStep.Element -> {
                    append('/').append(step.name)
                    step.index?.let { append('[').append(it).append(']') }
                }

                is XPointerStep.Text -> {
                    append("/text()")
                    step.index?.let { append('[').append(it).append(']') }
                }
            }
        }
        pointer.point?.let { append('.').append(it) }
    }
}
