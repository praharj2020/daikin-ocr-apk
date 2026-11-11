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

    /** Visor sintético com segmentos separados por intervalos (como o Daikin). Garante 71 ≠ 11. */
    @Test
    fun segmentosSeparadosNaoViram1() {
        val digits = mapOf(0 to "abcdef", 1 to "bc", 2 to "abdeg", 3 to "abcdg", 4 to "bcfg",
            5 to "acdfg", 6 to "acdefg", 7 to "abc", 8 to "abcdefg", 9 to "abcdfg")
        val rnd = Random(7)
        for (gap in listOf(0.0, 0.05, 0.10)) {
            val seg = mapOf(
                'a' to doubleArrayOf(0.15 + gap, 0.0, 0.85 - gap, 0.09),
                'b' to doubleArrayOf(0.88, 0.08 + gap, 1.0, 0.47 - gap / 2),
                'c' to doubleArrayOf(0.88, 0.53 + gap / 2, 1.0, 0.92 - gap),
                'd' to doubleArrayOf(0.15 + gap, 0.91, 0.85 - gap, 1.0),
                'e' to doubleArrayOf(0.0, 0.53 + gap / 2, 0.12, 0.92 - gap),
                'f' to doubleArrayOf(0.0, 0.08 + gap, 0.12, 0.47 - gap / 2),
                'g' to doubleArrayOf(0.15 + gap, 0.455, 0.85 - gap, 0.545),
            )
            for (v in 10..99) {
                val w = 320; val h = 200
                val img = ByteArray(w * h) { (15 + rnd.nextInt(20)).toByte() }
                val txt = v.toString()
                for (i in txt.indices) {
                    val ox = 30 + i * 115; val oy = 30; val dw = 80; val dh = 140
                    for (sname in digits[txt[i] - '0']!!) {
                        val r = seg[sname]!!
                        for (y in (oy + r[1] * dh).toInt()..(oy + r[3] * dh).toInt())
                            for (x in (ox + r[0] * dw).toInt()..(ox + r[2] * dw).toInt())
                                img[y * w + x] = (220 + rnd.nextInt(30)).toByte()
                    }
                }
                // "°" junto ao último dígito
                for (y in 30..40) for (x in 255..265) img[y * w + x] = 230.toByte()
                val r = SevenSegOcr.read(img, w, h, SevenSegOcr.Roi(15, 15, 250, 190))
                assertEquals("gap=$gap valor $v votos ${r.votes}", v, r.value)
            }
        }
    }
}
