package dev.lelonio.square.backend.lyrics

import dev.lelonio.square.data.LyricLine
import dev.lelonio.square.data.Lyrics

/**
 * Converts plain unsynced lyrics text into auto-advancing synchronized lyrics
 * using a 2-tier structural anchor and phonetic cadence algorithm.
 *
 * Tier 1 (Macro Structural Anchors):
 * - Decomposes text into stanzas and identifies musical sections (Verses, Pre-Chorus,
 *   Chorus / Hook, Bridge, Outro) using header parsing and stanza fingerprint repetition.
 * - Allocates time budgets per stanza to prevent drift accumulation across the song.
 * - Applies music-theory-based transition pauses (turnaround after Chorus, build-up before Chorus).
 *
 * Tier 2 (Micro Phonetics & Cadence):
 * - Evaluates vowel nuclei, stressed acute accents (á, é, í, ó, ú for sustained melodic notes),
 *   consonant cluster friction, mid-line commas, and end-of-phrase punctuation holds.
 * - Distributes each stanza's time budget to its individual lines, clamped within natural singing boundaries.
 */
object LyricsEstimator {

    private val VOWELS_REGEX = Regex("[aeiouyáéíóúüàèìòùâêîôûäëïöüãõ]+", RegexOption.IGNORE_CASE)
    private val STRESSED_REGEX = Regex("[áéíóúÁÉÍÓÚ]")
    private val CONSONANT_CLUSTERS_REGEX = Regex("[bcdfghjklmnpqrstvwxyz]{2,}", RegexOption.IGNORE_CASE)
    private val STRIP_WORD_REGEX = Regex("[^a-zA-ZáéíóúüàèìòùâêîôûäëïöüãõñÁÉÍÓÚÜÀÈÌÒÙÂÊÎÔÛÄËÏÖÜÃÕÑ]")
    private val SIGNATURE_STRIP_REGEX = Regex("[^a-z0-9]")

    private val CHORUS_KEYWORDS = listOf(
        "chorus", "coro", "refrain", "hook", "estribillo", "refrão",
    )

    private data class StanzaData(
        val lines: List<String>,
        val lineWeights: List<Double>,
        val totalWeight: Double,
        val isChorus: Boolean,
        val isInstrumentalBreak: Boolean,
    )

    private fun countSyllables(cleanWord: String): Int {
        if (cleanWord.isEmpty()) return 1
        val matches = VOWELS_REGEX.findAll(cleanWord).count()
        var count = if (matches == 0) 1 else matches

        // English silent 'e' heuristic (e.g. 'fade', 'love', but keep 'me', 'the', 'little')
        if (cleanWord.endsWith("e") && !cleanWord.endsWith("le") && count > 1 && !STRESSED_REGEX.containsMatchIn(cleanWord)) {
            count--
        }
        return count.coerceAtLeast(1)
    }

    private fun calculateWordWeight(word: String): Double {
        val clean = STRIP_WORD_REGEX.replace(word, "").lowercase()
        if (clean.isEmpty()) return 1.0

        val syllables = countSyllables(clean)
        val stressedCount = STRESSED_REGEX.findAll(word).count()
        val clustersCount = CONSONANT_CLUSTERS_REGEX.findAll(clean).count()

        // Syllable base + stressed vowel sustained note bonus + consonant cluster articulation time
        return (syllables * 2.2) + (stressedCount * 0.8) + (clustersCount * 0.4) + 1.0
    }

    private fun calculateLineWeight(line: String): Double {
        val words = line.split(Regex("\\s+")).filter { w -> w.any { it.isLetterOrDigit() } }
        if (words.isEmpty()) return 4.0

        val wordsWeight = words.sumOf { calculateWordWeight(it) }
        var punctuationBonus = 1.2

        val trimmed = line.trim()
        if (trimmed.endsWith('?') || trimmed.endsWith('!') || trimmed.endsWith("...") || trimmed.endsWith('.')) {
            punctuationBonus += 1.3 // End-of-phrase breath / musical hold
        }
        if (trimmed.contains(',') || trimmed.contains(';')) {
            punctuationBonus += 0.5 // Mid-phrase caesura
        }

        return wordsWeight + (words.size * 0.8) + 2.5 + punctuationBonus
    }

    fun estimate(plainText: String, durationMs: Long): Lyrics {
        val rawLines = plainText.lines()
        val rawStanzas = mutableListOf<MutableList<String>>()
        var currentStanza = mutableListOf<String>()
        val explicitChorusStanzas = mutableSetOf<Int>()
        val instrumentalStanzas = mutableSetOf<Int>()

        var pendingChorus = false
        var pendingInstrumental = false

        for (raw in rawLines) {
            val line = raw.trim()
            if (line.isBlank()) {
                if (currentStanza.isNotEmpty()) {
                    rawStanzas.add(currentStanza)
                    currentStanza = mutableListOf()
                }
                continue
            }

            // Check for section headers: [Chorus], [Verso 1], (Coro), etc.
            val isBracketHeader = (line.startsWith('[') && line.endsWith(']'))
            val isParenHeader = (line.startsWith('(') && line.endsWith(')') && line.length < 35)

            if (isBracketHeader || isParenHeader) {
                if (currentStanza.isNotEmpty()) {
                    rawStanzas.add(currentStanza)
                    currentStanza = mutableListOf()
                }
                val lower = line.lowercase()
                if (CHORUS_KEYWORDS.any { lower.contains(it) }) {
                    pendingChorus = true
                }
                if (lower.contains("solo") || lower.contains("instrumental") || lower.contains("drop")) {
                    pendingInstrumental = true
                }
                continue
            }

            if (pendingChorus) {
                explicitChorusStanzas.add(rawStanzas.size)
                pendingChorus = false
            }
            if (pendingInstrumental) {
                instrumentalStanzas.add(rawStanzas.size)
                pendingInstrumental = false
            }

            currentStanza.add(line)
        }
        if (currentStanza.isNotEmpty()) {
            rawStanzas.add(currentStanza)
        }

        if (rawStanzas.isEmpty()) {
            return Lyrics(emptyList(), synced = false)
        }

        // If duration is unknown, return unsynced static lines
        if (durationMs <= 0) {
            val flatLines = rawStanzas.flatten().map { LyricLine(startTimeMs = null, text = it) }
            return Lyrics(flatLines, synced = false)
        }

        // Compute stanza fingerprints to detect unlabelled repeated Choruses / Hooks
        val stanzaSignatures = rawStanzas.map { stanza ->
            stanza.joinToString(" ") { l -> SIGNATURE_STRIP_REGEX.replace(l.lowercase(), "") }
        }
        val signatureCounts = mutableMapOf<String, Int>()
        for (sig in stanzaSignatures) {
            if (sig.length > 20) {
                signatureCounts[sig] = (signatureCounts[sig] ?: 0) + 1
            }
        }

        val processedStanzas = rawStanzas.mapIndexed { idx, lines ->
            val weights = lines.map { calculateLineWeight(it) }
            val sig = stanzaSignatures[idx]
            val isRepeatedChorus = (signatureCounts[sig] ?: 0) > 1
            val isChorus = explicitChorusStanzas.contains(idx) || isRepeatedChorus
            val isInstrumental = instrumentalStanzas.contains(idx)

            StanzaData(
                lines = lines,
                lineWeights = weights,
                totalWeight = weights.sum().coerceAtLeast(1.0),
                isChorus = isChorus,
                isInstrumentalBreak = isInstrumental,
            )
        }

        val totalWeight = processedStanzas.sumOf { it.totalWeight }.coerceAtLeast(1.0)

        // Macro-timing anchors: Intro and Outro
        val introMs = (durationMs * 0.065).toLong().coerceIn(6000L, 15000L)
        val outroMs = (durationMs * 0.055).toLong().coerceIn(5000L, 14000L)

        // Musical transition pauses between stanzas
        val stanzaPauses = mutableListOf<Long>()
        for (i in 0 until processedStanzas.size - 1) {
            val curr = processedStanzas[i]
            val next = processedStanzas[i + 1]

            val pause = when {
                curr.isInstrumentalBreak || next.isInstrumentalBreak -> 4500L
                curr.isChorus -> 3400L // Turnaround after Chorus
                next.isChorus -> 2000L // Pre-Chorus tension pause before Chorus drop
                else -> 2400L          // Standard verse-to-verse pause
            }
            stanzaPauses.add(pause)
        }

        val totalPausesMs = stanzaPauses.sum()
        val vocalPoolMs = (durationMs - introMs - outroMs - totalPausesMs).coerceAtLeast(10000L)

        val resultLines = mutableListOf<LyricLine>()
        var currentMs = introMs

        for (sIdx in processedStanzas.indices) {
            val stanza = processedStanzas[sIdx]
            if (sIdx > 0) {
                currentMs += stanzaPauses[sIdx - 1]
            }

            // Closed time budget for this specific stanza (anchors timing, resets drift)
            val stanzaDurationMs = (stanza.totalWeight / totalWeight) * vocalPoolMs

            for (lIdx in stanza.lines.indices) {
                val lineText = stanza.lines[lIdx]
                val lineWeight = stanza.lineWeights[lIdx]

                val lineDurationMs = ((lineWeight / stanza.totalWeight) * stanzaDurationMs)
                    .toLong()
                    .coerceIn(1400L, 8500L)

                val lineStartTime = currentMs
                resultLines.add(
                    LyricLine(
                        startTimeMs = lineStartTime,
                        text = lineText,
                    ),
                )
                currentMs += lineDurationMs
            }
        }

        return Lyrics(resultLines, synced = true)
    }
}

