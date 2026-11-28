package pt.casa.daikinocr

import java.util.Calendar

/**
 * Horário das fotos:
 *  - 23:30 → 06:09: de 2 em 2 horas (a última da noite às 06:09);
 *  - 05:21 → 08:09: fotos extra de 10 em 10 min, só se a bomba já estiver ligada (o HA decide);
 *  - 16:45: foto fixa (decide o pedido de autorização da bomba das 17:00);
 *  - 17:01 → 17:44: fotos extra de 10 em 10 min, só se a bomba já estiver ligada;
 *  - resto do dia: a cada [dayMin] minutos.
 */
object Schedule {
    const val NIGHT_MIN = 120
    private const val NIGHT_START = 23 * 60 + 30  // 23:30
    private const val NIGHT_LAST = 6 * 60 + 9     // 06:09 (inclusive)

    const val QUICK_MIN = 10
    private const val QUICK_START = 5 * 60 + 21   // 05:21
    private const val QUICK_END = 8 * 60 + 9      // 08:09 (inclusive)
    private const val QUICK2_START = 17 * 60 + 1  // 17:01
    private const val QUICK2_END = 17 * 60 + 44   // 17:44 (inclusive)
    private const val FIXED = 16 * 60 + 45        // 16:45 foto fixa

    private fun minuteOfDay(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun isNight(ms: Long): Boolean {
        val m = minuteOfDay(ms)
        return m >= NIGHT_START || m <= NIGHT_LAST
    }

    fun isQuick(ms: Long): Boolean {
        val m = minuteOfDay(ms)
        return m in QUICK_START..QUICK_END || m in QUICK2_START..QUICK2_END
    }

    /** Hora (ms) da próxima foto normal, contada a partir do início do ciclo anterior. */
    fun nextAt(startedMs: Long, dayMin: Int): Long {
        if (!isNight(startedMs)) {
            val next = startedMs + dayMin * 60_000L
            val fixed = Calendar.getInstance().apply {
                timeInMillis = startedMs
                set(Calendar.HOUR_OF_DAY, 16); set(Calendar.MINUTE, 45)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            // garante a foto das 16:45 (não salta por cima dela)
            return if (startedMs < fixed - 60_000L && next > fixed) fixed else next
        }
        val next = startedMs + NIGHT_MIN * 60_000L
        val end = Calendar.getInstance().apply {
            timeInMillis = startedMs
            if (minuteOfDay(startedMs) >= NIGHT_START) add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 9)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return if (end > startedMs) minOf(next, end) else startedMs + dayMin * 60_000L
    }

    /** Próximo acordar: a foto normal ou, dentro das janelas rápidas, a próxima foto rápida (se vier antes). */
    fun nextWake(nowMs: Long, nextRegularMs: Long): Long {
        var best = nextRegularMs
        val q = nowMs + QUICK_MIN * 60_000L
        if (q < nextRegularMs - 60_000L && isQuick(q)) best = q
        // início de uma janela rápida antes da próxima foto normal
        for (startMin in intArrayOf(QUICK_START, QUICK2_START)) {
            val w = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, startMin / 60); set(Calendar.MINUTE, startMin % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (w > nowMs + 60_000L && w < nextRegularMs - 60_000L && w < best) best = w
        }
        return best
    }

    fun label(dayMin: Int) = "a cada $dayMin min (23:30–06:09: 2 h; 16:45 fixa; 05:21–08:09 e 17:01–17:44: 10 min se a bomba estiver ligada)"
}
