package app.resound.transcribe.local

import app.resound.transcribe.AsrResult
import app.resound.transcribe.PipelineTimes

/** Pure hotword correction logic retained for desktop callers. */
internal class LocalHotwordCorrection(
    @Suppress("UNUSED_PARAMETER") context: Any,
    private val hotwords: () -> List<String>,
) {
    private val corrector by lazy { HomophoneCorrector(HomophoneLexicon.load()) }

    fun apply(result: AsrResult): AsrResult {
        val words = hotwords()
        if (words.isEmpty()) return result
        val corrected = corrector.correct(result, words)
        PipelineTimes.record("同音纠正 ${corrected.replacements} 处")
        return corrected.result
    }
}

internal class HomophoneLexicon(val commonWords: Set<String>, private val extraReadings: Map<Int, Set<String>>) {
    fun readings(codePoint: Int): Set<String> = extraReadings[codePoint].orEmpty() // curated-only desktop fallback

    companion object {
        @Volatile private var cached: HomophoneLexicon? = null

        fun load(): HomophoneLexicon = cached ?: synchronized(this) {
            cached ?: run {
                val loader = checkNotNull(HomophoneLexicon::class.java.classLoader)
                // The desktop source set does not ship the Android assets; absent files disable replacements safely.
                val common = loader.getResourceAsStream("homophones/common-words.txt")
                    ?.bufferedReader()?.use { it.readLines().toSet() }.orEmpty()
                val readings = loader.getResourceAsStream("homophones/readings.txt")
                    ?.bufferedReader()?.useLines { lines ->
                        lines.associate { line -> line.codePointAt(0) to line.substringAfter(' ').split(',').toSet() }
                    }.orEmpty()
                HomophoneLexicon(common, readings).also { cached = it }
            }
        }
    }
}

internal class HomophoneCorrector(private val lexicon: HomophoneLexicon) {
    data class Correction(val result: AsrResult, val replacements: Int)
    private data class Word(val text: String, val sounds: List<Set<String>>)
    private var previousWords: List<String>? = null
    private var byFirstSound: Map<String, List<Word>> = emptyMap()
    private val characterCache = HashMap<Int, Set<String>>()
    private fun readings(cp: Int) = characterCache.getOrPut(cp) {
        if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) lexicon.readings(cp) else emptySet()
    }

    @Synchronized fun correct(result: AsrResult, hotwords: List<String>): Correction {
        if (previousWords != hotwords) {
            val words = hotwords.distinct().mapNotNull { text ->
                val points = text.codePoints().toArray()
                if (points.size < 2) return@mapNotNull null
                val sounds = points.map(::readings)
                if (sounds.any { it.isEmpty() }) null else Word(text, sounds)
            }
            byFirstSound = words.flatMap { word -> word.sounds.first().map { it to word } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, words) -> words.sortedByDescending { it.sounds.size } }
            previousWords = hotwords.toList()
        }
        var count = 0
        val sentences = result.sentences.map { sentence ->
            val points = sentence.text.codePoints().toArray()
            val sounds = points.map(::readings)
            val output = StringBuilder()
            var position = 0
            while (position < points.size) {
                val candidates = sounds[position].flatMap { byFirstSound[it].orEmpty() }.distinct()
                    .filter { word -> position + word.sounds.size <= points.size && word.sounds.indices.all { offset ->
                        word.sounds[offset].any { it in sounds[position + offset] }
                    } }
                val longest = candidates.maxOfOrNull { it.sounds.size }
                if (longest == null) {
                    output.appendCodePoint(points[position++]); continue
                }
                val matches = candidates.filter { it.sounds.size == longest }
                val original = String(points, position, longest)
                val replacement = matches.singleOrNull()?.text
                if (replacement != null && replacement != original && original !in lexicon.commonWords) {
                    output.append(replacement); count++
                } else output.append(original)
                position += longest
            }
            val text = output.toString()
            if (text == sentence.text) sentence else sentence.copy(text = text, originalText = sentence.originalText ?: sentence.text)
        }
        return Correction(result.copy(sentences = sentences), count)
    }
}
