package com.tanuh.demo.anupath

import com.tanuh.demo.anupath.AnuPathProtocol.TEST_DATE_TIME
import com.tanuh.demo.anupath.AnuPathProtocol.TEST_NAME
import com.tanuh.demo.anupath.AnuPathProtocol.TEST_SEQUENCE
import com.tanuh.demo.anupath.AnuPathProtocol.TEST_UNIT
import com.tanuh.demo.anupath.AnuPathProtocol.TEST_VALUE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AnuPathProtocolTest {

    @Test
    fun commandsMatchIntegrationGuide() {
        assertArrayEquals(byteArrayOf(0x01, 0x00), AnuPathProtocol.CMD_LAST_RECORD)
        assertArrayEquals(byteArrayOf(0x03, 0x00), AnuPathProtocol.CMD_SYNC_ALL)
        assertArrayEquals(byteArrayOf(0x04, 0x00), AnuPathProtocol.CMD_RESET_MEMORY)
        // Field-verified on PS-AN001-02: record id is ASCII after 02 00 (record 1 -> 02 00 31).
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x30), AnuPathProtocol.requestRecord(0))
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x31), AnuPathProtocol.requestRecord(1))
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x31, 0x32), AnuPathProtocol.requestRecord(12))
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x35, 0x30, 0x30), AnuPathProtocol.requestRecord(500))
        assertEquals("Request record #300", AnuPathProtocol.describeCommand(AnuPathProtocol.requestRecord(300)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeRecordId() {
        AnuPathProtocol.requestRecord(501)
    }

    @Test
    fun dateTimePayloadUsesPipeFormat() {
        val cal = Calendar.getInstance().apply { set(2025, Calendar.AUGUST, 5, 9, 47, 35) }
        assertEquals("2025|08|05|09|47|35", AnuPathProtocol.decodeString(AnuPathProtocol.dateTimePayload(cal.time)))
    }

    @Test
    fun decodeStripsNulPadding() {
        assertEquals("6.2", AnuPathProtocol.decodeString(byteArrayOf(0x36, 0x2E, 0x32, 0x00, 0x00)))
    }

    @Test
    fun assemblesRecordRegardlessOfFieldOrder() {
        val out = mutableListOf<TestRecord>()
        val a = RecordAssembler(clock = { 1L }) { out += it }
        a.onField(TEST_VALUE, "6.2")
        a.onField(TEST_NAME, "HbA1c")
        a.onField(TEST_DATE_TIME, "22-09-2025 12:13")
        a.onField(TEST_UNIT, "%")
        assertTrue(out.isEmpty())
        a.onField(TEST_SEQUENCE, "12")
        assertEquals(1, out.size)
        val r = out.single()
        assertEquals(TestRecord("12", "HbA1c", "6.2", "%", "22-09-2025 12:13", 1L), r)
        assertTrue(r.isComplete)
        assertTrue(r.isHbA1c)
    }

    @Test
    fun syncAllProducesOneRecordPerSequence() {
        val out = mutableListOf<TestRecord>()
        val a = RecordAssembler { out += it }
        for (seq in 1..3) {
            a.onField(TEST_SEQUENCE, "$seq")
            a.onField(TEST_NAME, "HbA1c")
            a.onField(TEST_VALUE, "6.$seq")
            a.onField(TEST_UNIT, "%")
            a.onField(TEST_DATE_TIME, "0$seq-10-2026 10:00")
        }
        assertEquals(listOf("1", "2", "3"), out.map { it.sequence })
        assertEquals(listOf("6.1", "6.2", "6.3"), out.map { it.value })
    }

    @Test
    fun unchangedFieldsCarryForwardWhenNotRenotified() {
        val out = mutableListOf<TestRecord>()
        val a = RecordAssembler { out += it }
        listOf(TEST_SEQUENCE to "1", TEST_NAME to "HbA1c", TEST_VALUE to "6.1", TEST_UNIT to "%",
            TEST_DATE_TIME to "d1").forEach { (u, v) -> a.onField(u, v) }
        // Firmware only notifies what changed for record 2.
        a.onField(TEST_SEQUENCE, "2")
        a.onField(TEST_VALUE, "7.4")
        a.onField(TEST_DATE_TIME, "d2")
        a.onField(TEST_SEQUENCE, "3") // record 3 starts -> record 2 emitted
        assertEquals(TestRecord("2", "HbA1c", "7.4", "%", "d2", out[1].receivedAtMillis), out[1])
        a.flush()
        assertEquals("3", out[2].sequence)
    }

    @Test
    fun duplicateNotificationIsIgnoredAndPartialIsFlagged() {
        val out = mutableListOf<TestRecord>()
        val a = RecordAssembler { out += it }
        a.onField(TEST_NAME, "HbA1c")
        a.onField(TEST_NAME, "HbA1c")
        a.onField(TEST_VALUE, "5.9")
        a.flush()
        assertEquals(1, out.size)
        assertFalse(out.single().isComplete)
    }

    @Test
    fun parsesGuideDateFormat() {
        val expected = Calendar.getInstance().apply { clear(); set(2025, Calendar.SEPTEMBER, 22, 12, 13) }.timeInMillis
        assertEquals(expected, AnuPathProtocol.parseTestDateTime("22-09-2025 12:13"))
        assertEquals(null, AnuPathProtocol.parseTestDateTime("garbage"))
    }

    @Test
    fun ordersByTestDateNewestFirst() {
        fun r(seq: String, dt: String) = TestRecord(seq, "HbA1c", "6.0", "%", dt, 0L)
        val sorted = listOf(
            r("1", "01-10-2026 09:00"),
            r("3", "15-10-2026 08:30"),
            r("2", "15-10-2026 07:45"),
            r("9", "bad date"),
        ).sortedWith(AnuPathProtocol.NEWEST_FIRST)
        assertEquals(listOf("3", "2", "1", "9"), sorted.map { it.sequence })
    }

    @Test
    fun resyncedRecordHasSameKey() {
        val a = TestRecord("4", "HbA1c", "6.1", "%", "01-10-2026 09:00", 1L)
        assertEquals(a.key, a.copy(receivedAtMillis = 99L).key)
        // Same sequence after Reset memory but a different test time -> different record.
        assertTrue(a.key != a.copy(dateTime = "05-10-2026 10:00").key)
    }

    @Test
    fun parsesHexInVariousForms() {
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x01), AnuPathProtocol.parseHex("02 00 01"))
        assertArrayEquals(byteArrayOf(0x02, 0x00, 0x01), AnuPathProtocol.parseHex("0x02,0x00,0x01"))
        assertArrayEquals(byteArrayOf(0x02, 0x01), AnuPathProtocol.parseHex("0201"))
        assertEquals(null, AnuPathProtocol.parseHex("2 0 1"))
        assertEquals(null, AnuPathProtocol.parseHex("zz"))
    }
}
