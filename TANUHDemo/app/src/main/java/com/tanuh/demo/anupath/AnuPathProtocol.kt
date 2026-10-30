package com.tanuh.demo.anupath

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Constants and encoders for the anuPath nano BLE protocol.
 *
 * Source: "anuPath nano – Device Integration Guide for App Developers", V1.2 (2025-07-30).
 *  - Advertised name: "anuPath nano". No pairing/bonding.
 *  - One custom service; every result field is a separate UTF-8 string characteristic.
 *  - Notifications must be enabled on every Notify characteristic.
 *  - Commands are byte arrays written to the Data Control characteristic.
 *
 * Kept free of android.* so it can be unit tested on the JVM.
 */
object AnuPathProtocol {
    const val DEVICE_NAME = "anuPath nano"

    val SERVICE: UUID = UUID.fromString("c737c93c-4cda-4ecc-af5d-43b7984cf4ac")

    val TEST_SEQUENCE: UUID = UUID.fromString("248c5967-2bb1-4162-a1ee-a8b5ab53e3b3")
    val TEST_NAME: UUID = UUID.fromString("49e28d0e-90e7-471d-86dc-5e9b37c0e02e")
    val TEST_VALUE: UUID = UUID.fromString("6ea02fec-186a-4d42-9397-9d5c31fc77a6")
    val TEST_UNIT: UUID = UUID.fromString("f1e5dce8-fad5-41b1-9489-a8e5e853ac05")
    val TEST_DATE_TIME: UUID = UUID.fromString("3fede1d5-37e2-4d9b-aa81-3b3858e77fc6")
    val STATUS: UUID = UUID.fromString("28e3cac5-fe0d-4172-b426-3bb2061e2c90")
    val DATA_CONTROL: UUID = UUID.fromString("5c01bad3-d19c-4d57-b336-715900874b2f")
    val SET_DATE_TIME: UUID = UUID.fromString("d9b0b175-98c6-4a22-ba86-988639b6c06e")

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Every characteristic with the Notify property (guide §5.2: enabling all of them is mandatory). */
    val NOTIFY_CHARACTERISTICS: List<UUID> =
        listOf(TEST_SEQUENCE, TEST_NAME, TEST_VALUE, TEST_UNIT, TEST_DATE_TIME, STATUS, DATA_CONTROL)

    /** The five characteristics that together make one test result. */
    val RECORD_CHARACTERISTICS: List<UUID> =
        listOf(TEST_SEQUENCE, TEST_NAME, TEST_VALUE, TEST_UNIT, TEST_DATE_TIME)

    // ---- Control commands (guide §6.1) ----
    val CMD_LAST_RECORD = byteArrayOf(0x01, 0x00)
    val CMD_SYNC_ALL = byteArrayOf(0x03, 0x00)
    val CMD_RESET_MEMORY = byteArrayOf(0x04, 0x00)

    /** Device sequence numbers appear to be 0-based (4 stored results, latest is sequence "3"). */
    const val MIN_RECORD_ID = 0
    const val MAX_RECORD_ID = 500

    /**
     * Request a specific record.
     *
     * FIELD FINDING (PS-AN001-02): the guide's binary example `[0x02, 0x00, 0x05]` is wrong for this
     * firmware — every binary id returns STATUS_INVALID_COUNT. The id must be sent as ASCII digits
     * after `02 00`: record 1 = `02 00 31` (verified on device). Multi-digit ids are sent as
     * consecutive digits (record 12 = `02 00 31 32`) — ASSUMPTION, verify on a device with >9 records.
     */
    fun requestRecord(id: Int): ByteArray {
        require(id in MIN_RECORD_ID..MAX_RECORD_ID) { "Record id must be $MIN_RECORD_ID..$MAX_RECORD_ID" }
        return byteArrayOf(0x02, 0x00) + id.toString().toByteArray(StandardCharsets.US_ASCII)
    }

    const val STATUS_OK = "STATUS_OK"
    const val STATUS_INVALID_COUNT = "STATUS_INVALID_COUNT"

    /** Set Date Time payload (guide §6.2): UTF-8 "yyyy|MM|dd|HH|mm|ss", 24-hour clock. */
    fun dateTimePayload(date: Date = Date()): ByteArray =
        SimpleDateFormat("yyyy|MM|dd|HH|mm|ss", Locale.US).format(date).toByteArray(StandardCharsets.UTF_8)

    /** Characteristic values are strings; strip NUL padding / whitespace some firmwares append. */
    fun decodeString(bytes: ByteArray?): String =
        if (bytes == null) "" else String(bytes, StandardCharsets.UTF_8).trim { it <= ' ' || it == '\u0000' }

    private val TEST_DATE_FORMATS = listOf("dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm")

    /** Parses the Test Date Time string. Formats are tried strictly so "12:13" never matches ":ss". */
    fun parseTestDateTime(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in TEST_DATE_FORMATS) {
            val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            val pos = java.text.ParsePosition(0)
            val date = format.parse(trimmed, pos)
            if (date != null && pos.index == trimmed.length) return date.time
        }
        return null
    }

    /**
     * Display order for results: newest test first. Records whose date can't be parsed go last,
     * then ties are broken by sequence number (higher = newer).
     */
    val NEWEST_FIRST: Comparator<TestRecord> = compareByDescending<TestRecord> { it.testedAtMillis ?: Long.MIN_VALUE }
        .thenByDescending { it.sequence.toIntOrNull() ?: Int.MIN_VALUE }
        .thenByDescending { it.receivedAtMillis }

    /** Parses "02 00 01", "020001", "0x02,0x00,0x01" into bytes; null if malformed or empty. */
    fun parseHex(text: String): ByteArray? {
        val clean = text.replace(Regex("0[xX]"), "").replace(Regex("[\\s,:\\-]"), "")
        if (clean.isEmpty() || clean.length % 2 != 0 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Short, human-friendly device ID: last two bytes of the MAC, e.g. "63:26" — matches a sticker label. */
    fun shortId(address: String): String = address.takeLast(5)

    fun toHex(bytes: ByteArray?): String =
        bytes?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: ""

    fun nameOf(uuid: UUID): String = when (uuid) {
        SERVICE -> "Main Service"
        TEST_SEQUENCE -> "Test Sequence"
        TEST_NAME -> "Test Name"
        TEST_VALUE -> "Test Value"
        TEST_UNIT -> "Test Unit"
        TEST_DATE_TIME -> "Test Date Time"
        STATUS -> "Status"
        DATA_CONTROL -> "Data Control"
        SET_DATE_TIME -> "Set Date Time"
        CCCD -> "CCCD"
        else -> "Undocumented ${uuid.toString().substring(0, 8)}"
    }

    /** Status strings observed on PS-AN001-02 firmware (not in the guide). */
    fun describeStatus(status: String): String = when (status) {
        "STATUS_OK" -> "OK"
        "STATUS_INVALID_COUNT" -> "Invalid count – device rejected the record number / count"
        "STATUS_INVALID_CMD" -> "Invalid command – byte format not recognised"
        else -> status
    }

    fun describeCommand(bytes: ByteArray): String = when {
        bytes.contentEquals(CMD_LAST_RECORD) -> "Last record"
        bytes.contentEquals(CMD_SYNC_ALL) -> "Sync all records"
        bytes.contentEquals(CMD_RESET_MEMORY) -> "Reset device memory"
        bytes.size > 2 && bytes[0] == 0x02.toByte() && bytes[1] == 0x00.toByte() &&
            bytes.drop(2).all { it in 0x30..0x39 } ->
            "Request record #${String(bytes, 2, bytes.size - 2, StandardCharsets.US_ASCII)}"
        else -> "Command ${toHex(bytes)}"
    }
}

/** One test result as reported by the device. Any field may be blank if it never arrived. */
data class TestRecord(
    val sequence: String,
    val name: String,
    val value: String,
    val unit: String,
    val dateTime: String,
    val receivedAtMillis: Long,
    /** MAC address of the device the result came from (set by the app, not sent by the device). */
    val deviceAddress: String = "",
) {
    val isComplete: Boolean
        get() = sequence.isNotEmpty() && name.isNotEmpty() && value.isNotEmpty() &&
            unit.isNotEmpty() && dateTime.isNotEmpty()

    /**
     * Key used to de-duplicate records when the same result is re-sent (every connect runs
     * Sync all). Sequence + test time, so a sequence number reused after Reset memory is not
     * mistaken for an older result.
     */
    val key: String get() = if (sequence.isEmpty()) "t$receivedAtMillis" else "$deviceAddress|$sequence|$dateTime"

    /** Test time parsed from the device string (guide example "22-09-2025 12:13"); null if unparseable. */
    val testedAtMillis: Long? get() = AnuPathProtocol.parseTestDateTime(dateTime)

    val isHbA1c: Boolean get() = name.replace(" ", "").equals("HbA1c", ignoreCase = true)
}

/**
 * The device delivers a result as five independent string notifications (sequence, name, value,
 * unit, date-time); the guide does not specify their order, nor whether unchanged values are
 * re-notified. This mirrors the device's characteristic state and turns it into records:
 *  - every incoming field updates the "last known" value and is added to the current batch;
 *  - a batch is emitted as soon as all five fields have been updated;
 *  - if a field already in the batch arrives again with a different value, the batch is
 *    emitted first (a new record has started);
 *  - [flush] emits the pending batch, filling fields that were not re-sent with their last
 *    known value — the caller invokes it after a short idle period.
 */
class RecordAssembler(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRecord: (TestRecord) -> Unit,
) {
    private val lastKnown = mutableMapOf<UUID, String>()
    private val batch = mutableSetOf<UUID>()

    val hasPending: Boolean get() = batch.isNotEmpty()

    fun onField(uuid: UUID, text: String) {
        if (uuid !in AnuPathProtocol.RECORD_CHARACTERISTICS) return
        if (uuid in batch) {
            if (lastKnown[uuid] == text) return // duplicate notification
            flush()
        }
        lastKnown[uuid] = text
        batch += uuid
        if (batch.size == AnuPathProtocol.RECORD_CHARACTERISTICS.size) flush()
    }

    fun flush() {
        if (batch.isEmpty()) return
        batch.clear()
        onRecord(
            TestRecord(
                sequence = lastKnown[AnuPathProtocol.TEST_SEQUENCE].orEmpty(),
                name = lastKnown[AnuPathProtocol.TEST_NAME].orEmpty(),
                value = lastKnown[AnuPathProtocol.TEST_VALUE].orEmpty(),
                unit = lastKnown[AnuPathProtocol.TEST_UNIT].orEmpty(),
                dateTime = lastKnown[AnuPathProtocol.TEST_DATE_TIME].orEmpty(),
                receivedAtMillis = clock(),
            )
        )
    }

    fun reset() {
        lastKnown.clear()
        batch.clear()
    }
}
