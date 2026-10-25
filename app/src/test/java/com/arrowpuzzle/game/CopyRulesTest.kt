package com.arrowpuzzle.game

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ChaiUI copy rules over the app's sources: no em dashes, sentence case for UI text. */
class CopyRulesTest {
    private val root = File("src/main")
    private fun sources() = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()

    @Test
    fun sourcesAreFound() = assertTrue("run from the app module", sources().any { it.name == "MenuScreen.kt" })

    @Test
    fun noEmDashesAnywhere() {
        val hits = sources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (line.contains('—')) "${f.name}:${i + 1}" else null }
        }
        assertTrue("em dashes at $hits", hits.isEmpty())
    }

    @Test
    fun uiStringsUseSentenceCase() {
        // Proper nouns that keep their capitals.
        val allowed = setOf("Path Puzzle", "Kotlin", "C++", "Java")
        val literal = Regex("\"([A-Z][^\"$]{2,60})\"")
        val bad = ArrayList<String>()
        for (f in sources().filter { it.extension == "kt" }) {
            f.readLines().forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || line.contains("Suppress")) return@forEachIndexed
                for (m in literal.findAll(line)) {
                    var s = m.groupValues[1]
                    for (a in allowed) s = s.replace(a, "")
                    // each sentence may start with a capital; a capital later in one means Title Case
                    for (sentence in s.split(Regex("[.!?:;] "))) {
                        val words = sentence.split(' ', ',', '.', '!', '?').filter { it.isNotEmpty() }
                        if (words.size >= 2 && words.drop(1).any { it[0].isUpperCase() && it.length > 1 && !it.all { ch -> ch.isUpperCase() } })
                            bad.add("${f.name}:${i + 1} \"${m.groupValues[1]}\"")
                    }
                }
            }
        }
        assertTrue("Title Case UI text: $bad", bad.isEmpty())
    }

    @Test
    fun noAllCapsEyebrows() {
        val caps = Regex("\"([A-Z]{3,}( [A-Z]{2,})+)\"")
        val hits = sources().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> caps.find(line)?.let { "${f.name}:${i + 1} ${it.value}" } }
        }
        assertTrue("all-caps labels: $hits", hits.isEmpty())
    }
}
