package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeRecurrenceTest {
    @Test fun acceptsFiniteElementaryRules() {
        assertEquals("FREQ=DAILY;INTERVAL=1;COUNT=5",
            SafeRecurrence.parse("FREQ=DAILY;COUNT=5"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;COUNT=12;BYDAY=MO,FR",
            SafeRecurrence.parse("BYDAY=FR,MO;COUNT=12;INTERVAL=2;FREQ=WEEKLY"))
        assertEquals("FREQ=MONTHLY;INTERVAL=1;COUNT=12;BYMONTHDAY=15",
            SafeRecurrence.parse("FREQ=MONTHLY;COUNT=12;BYMONTHDAY=15"))
        assertEquals("FREQ=DAILY;INTERVAL=1;UNTIL=20270131T235959Z",
            SafeRecurrence.parse("FREQ=DAILY;UNTIL=20270131T235959Z"))
        assertEquals("PT3600S", SafeRecurrence.androidDuration(3600000, false))
        assertEquals("P2D", SafeRecurrence.androidDuration(2L * 86400000L, true))
    }
    @Test fun complexAndUnboundedRulesStayBlocked() {
        for (rule in listOf(
            "FREQ=DAILY", "FREQ=DAILY;COUNT=99999",
            "FREQ=DAILY;COUNT=1;UNTIL=20270101",
            "FREQ=YEARLY;COUNT=2", "FREQ=WEEKLY;COUNT=3;BYDAY=-1MO",
            "FREQ=MONTHLY;COUNT=2;BYSETPOS=-1;BYDAY=MO",
            "FREQ=MONTHLY;COUNT=4;BYMONTHDAY=31",
            "FREQ=WEEKLY;COUNT=3;BYDAY=TU,TU",
            "FREQ=DAILY;COUNT=1;COUNT=2", "FREQ=WEEKLY;COUNT=2;INTERVAL=0",
            "FREQ=DAILY;COUNT=5\nX-UNSAFE:YES"
        )) assertNull("unsafe rule $rule", SafeRecurrence.parse(rule))
        assertNull(SafeRecurrence.androidDuration(1L, false))
        assertNull(SafeRecurrence.androidDuration(1000L, true))
    }
}
