package pt.casa.daikinocr

import java.util.Calendar

/**
 * Horário das fotos:
 *  - 23:30 → 06:09: de 2 em 2 horas (a última da noite às 06:09);
 *  - 06:11 → 08:31: fotos extra de 10 em 10 min, só se a bomba já estiver ligada (o HA decide);
 *  - resto do dia: a cada [dayMin] minutos.
 */
object Schedule {
    const val NIGHT_MIN = 120
    private const val NIGHT_START = 23 * 60 + 30  // 23:30
    private const val NIGHT_LAST = 6 * 60 + 9     // 06:09 (inclusive)

    const val QUICK_MIN = 10
    private const val QUICK_START = 6 * 60 + 11   // 06:11
    private const val QUICK_END = 8 * 60 + 31     // 08:31 (inclusive)

    private fun minuteOfDay(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun isNight(ms: Long): Boolean {
        val m = minuteOfDay(ms)
        return m >= NIGHT_START || m <= NIGHT_LAST
    }

    fun isQuick(ms: Long): Boolean = minuteOfDay(ms) in QUICK_START..QUICK_END

    /** Hora (ms) da próxima foto normal, contada a partir do início do ciclo anterior. */
    fun nextAt(startedMs: Long, dayMin: Int): Long {
        if (!isNight(startedMs)) return startedMs + dayMin * 60_000L
        val next = startedMs + NIGHT_MIN * 60_000L
        val end = Calendar.getInstance().apply {
            timeInMillis = startedMs
            if (minuteOfDay(startedMs) >= NIGHT_START) add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 9)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return if (end > startedMs) minOf(next, end) else startedMs + dayMin * 60_000L
    }

    /** Próximo acordar: a foto normal ou, dentro da janela 06:11–08:31, a próxima foto rápida (se vier antes). */
    fun nextWake(nowMs: Long, nextRegularMs: Long): Long {
        val q = nowMs + QUICK_MIN * 60_000L
        return if (q < nextRegularMs - 60_000L && isQuick(q)) q else nextRegularMs
    }

    fun label(dayMin: Int) = "a cada $dayMin min (23:30–06:09: 2 h; 06:11–08:31: 10 min se a bomba estiver ligada)"
}
