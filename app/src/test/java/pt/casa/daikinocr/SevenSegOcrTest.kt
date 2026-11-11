package pt.casa.daikinocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SevenSegOcrTest {

    /** Lê PGM binário (P5) com a luminância das fotos reais. */
    private fun load(name: String): Triple<ByteArray, Int, Int> {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name.replace(".jpg", ".pgm"))!!.readBytes()
        var pos = 0
        val tokens = ArrayList<String>()
        while (tokens.size < 4) {
            val sb = StringBuilder()
            while (bytes[pos].toInt().toChar().isWhitespace()) pos++
            while (!bytes[pos].toInt().toChar().isWhitespace()) { sb.append(bytes[pos].toInt().toChar()); pos++ }
            tokens.add(sb.toString())
        }
        pos++ // um espaço após o 255
        val w = tokens[1].toInt()
        val h = tokens[2].toInt()
        return Triple(bytes.copyOfRange(pos, pos + w * h), w, h)
    }

    private val rois = mapOf(
        "dia.jpg" to SevenSegOcr.Roi(660, 1545, 772, 1620),
        "noite.jpg" to SevenSegOcr.Roi(550, 1195, 728, 1315),
        "flash.jpg" to SevenSegOcr.Roi(555, 1245, 790, 1390),
    )

    @Test
    fun leTresIluminacoes() {
        for ((name, roi) in rois) {
            val (g, w, h) = load(name)
            val r = SevenSegOcr.read(g, w, h, roi)
            assertEquals("leitura de $name ${r.votes}", 46, r.value)
        }
    }

    @Test
    fun nuncaLeErradoComCaixaImprecisa() {
        val rnd = Random(42)
        for ((name, roi) in rois) {
            val (g, w, h) = load(name)
            var ok = 0
            val n = 25
            repeat(n) {
                val bw = roi.x1 - roi.x0
                val bh = roi.y1 - roi.y0
                fun m(s: Int) = (s * (0.02 + rnd.nextDouble() * 0.2)).toInt()
                val r = SevenSegOcr.read(g, w, h, SevenSegOcr.Roi(roi.x0 - m(bw), roi.y0 - m(bh), roi.x1 + m(bw), roi.y1 + m(bh)))
                if (r.value != null) {
                    assertEquals("valor errado em $name", 46, r.value)
                    ok++
                }
            }
            assertTrue("$name: poucas leituras ($ok/$n)", ok >= n * 0.8)
        }
    }
}
