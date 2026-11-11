package pt.casa.daikinocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import javax.imageio.ImageIO

class SevenSegOcrTest {

    private fun load(name: String): Triple<ByteArray, Int, Int> {
        val img = ImageIO.read(javaClass.classLoader!!.getResourceAsStream(name))
        val w = img.width
        val h = img.height
        val rgb = img.getRGB(0, 0, w, h, null, 0, w)
        return Triple(SevenSegOcr.lumaFromRgb(rgb), w, h)
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
