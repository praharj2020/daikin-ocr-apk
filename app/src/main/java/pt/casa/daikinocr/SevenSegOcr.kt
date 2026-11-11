package pt.casa.daikinocr

/**
 * Leitor de visor de 7 segmentos (só dígitos).
 * Entrada: imagem em tons de cinza (luminância 0..255, 1 byte por pixel) e a área (ROI) dos dígitos.
 * Estratégia: vários limiares de binarização -> separa dígitos por colunas -> amostra os 7 segmentos
 * -> só aceita dígitos com correspondência perfeita -> votação entre limiares.
 * Prefere devolver null a devolver um número errado.
 */
object SevenSegOcr {

    data class Roi(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    data class Result(val value: Int?, val votes: Map<Int, Int>)

    private const val ORDER = "abcdefg"

    // x0, x1, y0, y1 normalizados à caixa do dígito
    private val SEGS = mapOf(
        'a' to floatArrayOf(0.30f, 0.70f, 0.00f, 0.16f),
        'b' to floatArrayOf(0.72f, 1.00f, 0.14f, 0.44f),
        'c' to floatArrayOf(0.72f, 1.00f, 0.56f, 0.86f),
        'd' to floatArrayOf(0.30f, 0.70f, 0.84f, 1.00f),
        'e' to floatArrayOf(0.00f, 0.28f, 0.56f, 0.86f),
        'f' to floatArrayOf(0.00f, 0.28f, 0.14f, 0.44f),
        'g' to floatArrayOf(0.30f, 0.70f, 0.42f, 0.58f),
    )

    private val DIGITS: List<Pair<Int, String>> = listOf(
        0 to "abcdef", 1 to "bc", 2 to "abdeg", 3 to "abcdg", 4 to "bcfg",
        5 to "acdfg", 6 to "acdefg", 7 to "abc", 8 to "abcdefg", 9 to "abcdfg",
        // variantes usadas por alguns visores
        6 to "cdefg", 7 to "abcf", 9 to "abcfg",
    )

    fun otsu(v: IntArray): Int {
        val hist = DoubleArray(256)
        for (p in v) hist[p] += 1.0
        val total = v.size.toDouble()
        var sumAll = 0.0
        for (t in 0 until 256) sumAll += t * hist[t]
        var wb = 0.0
        var sb = 0.0
        var best = -1.0
        var thr = 128
        for (t in 0 until 256) {
            wb += hist[t]
            if (wb == 0.0) continue
            val wf = total - wb
            if (wf == 0.0) break
            sb += t * hist[t]
            val mb = sb / wb
            val mf = (sumAll - sb) / wf
            val between = wb * wf * (mb - mf) * (mb - mf)
            if (between > best) {
                best = between; thr = t
            }
        }
        return thr
    }

    /** Lê o visor. gray = luminância (linha a linha), largura imgW, altura imgH. */
    fun read(gray: ByteArray, imgW: Int, imgH: Int, roi: Roi, ndig: Int = 2): Result {
        val x0 = roi.x0.coerceIn(0, imgW - 1)
        val y0 = roi.y0.coerceIn(0, imgH - 1)
        val x1 = roi.x1.coerceIn(x0 + 1, imgW)
        val y1 = roi.y1.coerceIn(y0 + 1, imgH)
        val w = x1 - x0
        val h = y1 - y0
        val v = IntArray(w * h)
        for (y in 0 until h) {
            val row = (y0 + y) * imgW + x0
            for (x in 0 until w) v[y * w + x] = gray[row + x].toInt() and 0xFF
        }
        val t0 = otsu(v)
        val thresholds = sortedSetOf(t0)
        var t = 60
        while (t <= 250) { thresholds.add(t); t += 15 }

        val votes = HashMap<Int, Int>()
        val mask = BooleanArray(w * h)
        for (th in thresholds) {
            for (i in v.indices) mask[i] = v[i] > th
            val r = readMask(mask, w, h, ndig)
            if (r != null) votes[r] = (votes[r] ?: 0) + 1
        }
        if (votes.isEmpty()) return Result(null, votes)
        val best = votes.maxByOrNull { it.value }!!
        val total = votes.values.sum()
        if (best.value < 2 || best.value < 0.6 * total) return Result(null, votes)
        return Result(best.key, votes)
    }

    private fun runs(profile: IntArray, minCount: Int, maxGap: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (i in profile.indices) {
            if (profile[i] < minCount) continue
            if (out.isNotEmpty() && i - out.last()[1] <= maxGap + 1) {
                out.last()[1] = i
            } else {
                out.add(intArrayOf(i, i))
            }
        }
        return out.map { intArrayOf(it[0], it[1] + 1) }
    }

    /** Devolve o número lido ou null. */
    fun readMask(mask: BooleanArray, W: Int, H: Int, ndig: Int): Int? {
        // 1) faixa de linhas dos dígitos
        val rowProf = IntArray(H)
        for (y in 0 until H) {
            var c = 0
            for (x in 0 until W) if (mask[y * W + x]) c++
            rowProf[y] = c
        }
        val rr = runs(rowProf, maxOf(2, W / 100), maxOf(1, H / 5))
        if (rr.isEmpty()) return null
        val band = rr.maxByOrNull { it[1] - it[0] }!!
        val ra = band[0]
        val rb = band[1]
        val bh = rb - ra

        // 2) blocos de colunas (dígitos)
        val colProf = IntArray(W)
        for (x in 0 until W) {
            var c = 0
            for (y in ra until rb) if (mask[y * W + x]) c++
            colProf[x] = c
        }
        val cr = runs(colProf, maxOf(2, bh / 25), maxOf(1, bh / 40))
        val blobs = ArrayList<IntArray>() // a, b, ya, yb (ya/yb absolutos)
        for (c in cr) {
            val a = c[0]
            val b = c[1]
            var first = -1
            var last = -1
            for (y in ra until rb) {
                var any = false
                for (x in a until b) if (mask[y * W + x]) { any = true; break }
                if (any) {
                    if (first < 0) first = y
                    last = y
                }
            }
            if (first < 0) continue
            val hh = last - first + 1
            if (hh >= 0.6 * bh) blobs.add(intArrayOf(a, b, first, last + 1))
        }
        if (blobs.size < ndig) return null

        var wref = 0
        for (i in 0 until ndig) wref = maxOf(wref, blobs[i][1] - blobs[i][0])

        var value = 0
        for (i in 0 until ndig) {
            val (a, b, ya, yb) = blobs[i].let { listOf(it[0], it[1], it[2], it[3]) }
            val w = b - a
            val h = yb - ya
            val digit: Int
            if (w < 0.45 * wref || w < 0.35 * h) {
                digit = 1
            } else {
                val on = StringBuilder()
                for (s in ORDER) {
                    val sg = SEGS[s]!!
                    val sx0 = (sg[0] * w).toInt()
                    val sx1 = maxOf((sg[1] * w).toInt(), sx0 + 1)
                    val sy0 = (sg[2] * h).toInt()
                    val sy1 = maxOf((sg[3] * h).toInt(), sy0 + 1)
                    var lit = 0
                    var tot = 0
                    for (yy in sy0 until minOf(sy1, h)) {
                        for (xx in sx0 until minOf(sx1, w)) {
                            tot++
                            if (mask[(ya + yy) * W + a + xx]) lit++
                        }
                    }
                    if (tot > 0 && lit.toDouble() / tot > 0.30) on.append(s)
                }
                val onStr = on.toString()
                var best = -1
                var bd = 99
                for ((k, segs) in DIGITS) {
                    var dist = 0
                    for (c in ORDER) if ((c in onStr) != (c in segs)) dist++
                    if (dist < bd) { best = k; bd = dist }
                }
                if (bd > 0) return null
                digit = best
            }
            value = value * 10 + digit
        }
        return value
    }

    /** Converte RGB (0xRRGGBB) em luminância — usado nos testes e em imagens Bitmap. */
    fun lumaFromRgb(rgb: IntArray): ByteArray {
        val out = ByteArray(rgb.size)
        for (i in rgb.indices) {
            val p = rgb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = (0.3 * r + 0.59 * g + 0.11 * b).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }
}
