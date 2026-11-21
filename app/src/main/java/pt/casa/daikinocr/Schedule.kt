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

    /** 07:01–08:49: fotos extra de 10 em 10 min, só se a bomba já estiver ligada (o HA decide). */
    const val QUICK_MIN = 10
    private const val QUICK_START = 7 * 60 + 1   // 07:01
    private const val QUICK_END = 8 * 60 + 49    // 08:49 (inclusive)

    fun isQuick(ms: Long): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return m in QUICK_START..QUICK_END
    }

    /** Próximo acordar: a foto normal ou, dentro da janela 07:01–08:49, a próxima foto rápida (se vier antes). */
    fun nextWake(nowMs: Long, nextRegularMs: Long): Long {
        val q = nowMs + QUICK_MIN * 60_000L
        return if (q < nextRegularMs - 60_000L && isQuick(q)) q else nextRegularMs
    }

    fun label(dayMin: Int) = "a cada $dayMin min (00:01–06:00: 2 h; 07:01–08:49: 10 min se a bomba estiver ligada)"
}
