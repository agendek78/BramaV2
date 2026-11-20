package com.agendek.bramav2.protocol

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pliki `resources/vectors/` odpowiadają bieżącej implementacji (firmware odtwarza je jako strona serwera).
 * Generacja: `./gradlew :protocol:test -PvectorsGen=true --tests '*VectorScenariosTest*'`.
 */
class VectorScenariosTest {

    private val dir = File("src/test/resources/vectors")

    @Test
    fun vectorScenariosMatchCurrentImplementation() {
        for ((name, lines) in VectorScenarios.all()) {
            val file = File(dir, name)
            assertEquals("Brak pliku $name", true, file.exists())
            assertEquals("Plik $name nieaktualny", lines.joinToString("\n", postfix = "\n"), file.readText())
        }
    }

    @Test
    fun generateVectorScenarios() {
        assumeTrue(System.getProperty("vectors.gen") != null)
        dir.mkdirs()
        for ((name, lines) in VectorScenarios.all()) File(dir, name).writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
