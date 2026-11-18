package eu.x45.cal45.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChangeStatusTest {
    @Test fun calendarLocalChangesNeverGrantUpload() {
        assertEquals("Lokal verändert – Upload noch deaktiviert",
            LocalChangeStatus.calendar("old","new",false,false))
        assertEquals("Lokal gelöscht – Upload gesperrt",
            LocalChangeStatus.calendar("old","new",true,false))
        assertEquals("Terminserie – Upload gesperrt",
            LocalChangeStatus.calendar("old","new",false,true))
        assertEquals("Älterer Import – Upload gesperrt",
            LocalChangeStatus.calendar(null,"new",false,false))
        assertEquals("Kein Unterschied zu importierten Feldern",
            LocalChangeStatus.calendar("same","same",false,false))
    }
    @Test fun contactsWithUnknownFieldsOrNoSnapshotStayProtected() {
        assertEquals("Lokal verändert – Upload noch deaktiviert",
            LocalChangeStatus.contact(true,true,false,false,false))
        assertEquals("Zusätzliche Felder – Upload gesperrt",
            LocalChangeStatus.contact(true,true,true,false,false))
        assertEquals("Zusätzliche Felder – Upload gesperrt",
            LocalChangeStatus.contact(true,true,false,true,false))
        assertEquals("Lokal gelöscht – Upload gesperrt",
            LocalChangeStatus.contact(true,true,false,false,true))
        assertEquals("Älterer Import – Upload gesperrt",
            LocalChangeStatus.contact(false,true,false,false,false))
    }
}
