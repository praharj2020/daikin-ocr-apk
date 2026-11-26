package pt.casa.daikinocr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ScheduleTest {
    private fun at(h: Int, m: Int, d: Int = 23): Long = Calendar.getInstance().apply {
        set(2026, 10, d, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun hm(ms: Long): String { val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)) }

    @Test fun dia50() { assertEquals("10:50", hm(Schedule.nextAt(at(10, 0), 50))) }
    @Test fun antesDaNoite() { assertEquals("23:50", hm(Schedule.nextAt(at(23, 0), 50))) }
    @Test fun inicioNoite() { assertEquals("01:40", hm(Schedule.nextAt(at(23, 40), 50))) }
    @Test fun noite2h() { assertEquals("03:40", hm(Schedule.nextAt(at(1, 40), 50))) }
    @Test fun fimDaNoite() { assertEquals("06:09", hm(Schedule.nextAt(at(5, 40), 50))) }
    @Test fun depoisDaNoite() { assertEquals("06:59", hm(Schedule.nextAt(at(6, 9), 50))) }
    @Test fun rapidaNaJanela() { assertEquals("06:00", hm(Schedule.nextWake(at(5, 50), at(6, 9)))) }
    @Test fun rapidaAntesDaNormal() { assertEquals("07:50", hm(Schedule.nextWake(at(7, 45), at(7, 50)))) }
    @Test fun inicioJanela() { assertEquals("05:51", hm(Schedule.nextWake(at(5, 41), at(6, 9)))) }
    @Test fun ultimaRapida() { assertEquals("08:19", hm(Schedule.nextWake(at(8, 9), at(9, 10)))) }
    @Test fun fimJanela() { assertEquals("09:10", hm(Schedule.nextWake(at(8, 19), at(9, 10)))) }
    @Test fun foraDaJanela() { assertEquals("10:50", hm(Schedule.nextWake(at(10, 0), at(10, 50)))) }
}
