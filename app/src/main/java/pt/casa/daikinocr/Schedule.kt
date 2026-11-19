package pt.casa.daikinocr

import java.util.Calendar

/**
 * Horário das fotos: das 00:01 às 06:00 de 2 em 2 horas (a última da noite às 06:00);
 * no resto do dia a cada [dayMin] minutos (por defeito 50).
 */
object Schedule {
    const val NIGHT_MIN = 120
    private const val NIGHT_START = 1          // 00:01
    private const val NIGHT_END = 6 * 60       // 06:00

    fun isNight(cal: Calendar): Boolean {
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return m in NIGHT_START until NIGHT_END
    }

    /** Hora (ms) da próxima foto, contada a partir do início do ciclo anterior. */
    fun nextAt(startedMs: Long, dayMin: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = startedMs }
        if (!isNight(cal)) return startedMs + dayMin * 60_000L
        val next = startedMs + NIGHT_MIN * 60_000L
        val end = Calendar.getInstance().apply {
            timeInMillis = startedMs
            set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return minOf(next, end)
    }

    fun label(dayMin: Int) = "a cada $dayMin min (00:01–06:00: 2 h)"
}
