package pt.casa.daikinocr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ScheduleTest {
    private fun at(h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(2026, 10, 19, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun hm(ms: Long): String { val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }

    @Test fun dia50() { assertEquals("10:50", hm(Schedule.nextAt(at(10, 0), 50))) }
    @Test fun antesDaNoite() { assertEquals("00:30", hm(Schedule.nextAt(at(23, 40), 50))) }
    @Test fun noite2h() { assertEquals("02:30", hm(Schedule.nextAt(at(0, 30), 50))) }
    @Test fun fimDaNoite() { assertEquals("06:00", hm(Schedule.nextAt(at(4, 30), 50))) }
    @Test fun depoisDas6() { assertEquals("06:50", hm(Schedule.nextAt(at(6, 0), 50))) }
    @Test fun meiaNoite() { assertEquals("00:50", hm(Schedule.nextAt(at(0, 0), 50))) }
}
