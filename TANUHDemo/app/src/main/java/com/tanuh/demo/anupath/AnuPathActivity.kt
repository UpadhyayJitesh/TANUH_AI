package com.tanuh.demo.anupath

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.tanuh.demo.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * POC: read results from an anuPath nano (A1C) over BLE.
 *
 * Flow: runtime Bluetooth permissions -> Bluetooth on -> scan filtered by advertised name
 * "anuPath nano" -> connect -> discover -> enable all notifications -> sync clock ->
 * send Data Control commands -> assemble the per-field string notifications into results.
 *
 * Every value received is also written to the raw log (text + hex) because this is a POC
 * against a vendor spec with a few gaps (field order, multi-byte record ids, status strings).
 */
@SuppressLint("MissingPermission") // every BLE call is gated by hasBlePermissions()
class AnuPathActivity : AppCompatActivity(), AnuPathBleClient.Listener {

    private lateinit var statusText: TextView
    private lateinit var deviceStatusText: TextView
    private lateinit var connectedDeviceText: TextView
    private var connectedName: String = AnuPathProtocol.DEVICE_NAME
    private var connectedAddress: String = ""
    private lateinit var scanButton: Button
    private lateinit var showAllCheck: CheckBox
    private lateinit var noDevicesText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultsLabel: TextView
    private lateinit var noResultsText: TextView
    private lateinit var recordIdInput: EditText
    private lateinit var rawHexInput: EditText
    private lateinit var logText: TextView
    private lateinit var commandButtons: List<Button>
    private lateinit var resetButton: Button

    private val main = Handler(Looper.getMainLooper())
    private lateinit var client: AnuPathBleClient
    private val assembler = RecordAssembler { onRecord(it) }

    private var scanning = false
    private val foundDevices = linkedMapOf<String, Pair<BluetoothDevice, TextView>>()
    private val records = linkedMapOf<String, Pair<TestRecord, View>>()
    private val logLines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ------------------------------------------------------------ permissions & BT enable

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // API <= 30: BLE scan results are only delivered with location permission.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBlePermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            log("Permission result: $result")
            if (result.values.all { it }) startScanFlow()
            else showStatus(getString(R.string.anupath_permission_denied))
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothAdapter?.isEnabled == true) startScanFlow()
            else showStatus(getString(R.string.anupath_bt_off))
        }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_anupath)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.anupathRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        statusText = findViewById(R.id.anupathStatus)
        deviceStatusText = findViewById(R.id.anupathDeviceStatus)
        connectedDeviceText = findViewById(R.id.anupathConnectedDevice)
        scanButton = findViewById(R.id.anupathScan)
        showAllCheck = findViewById(R.id.anupathShowAll)
        noDevicesText = findViewById(R.id.anupathNoDevices)
        deviceList = findViewById(R.id.anupathDeviceList)
        resultsContainer = findViewById(R.id.anupathResults)
        resultsLabel = findViewById(R.id.anupathResultsLabel)
        noResultsText = findViewById(R.id.anupathNoResults)
        recordIdInput = findViewById(R.id.anupathRecordId)
        logText = findViewById(R.id.anupathLog)

        client = AnuPathBleClient(applicationContext, this)

        scanButton.setOnClickListener { if (scanning) stopScan() else startScanFlow() }

        val lastRecord = findViewById<Button>(R.id.anupathLastRecord)
        val syncAll = findViewById<Button>(R.id.anupathSyncAll)
        val requestRecord = findViewById<Button>(R.id.anupathRequestRecord)
        val setTime = findViewById<Button>(R.id.anupathSetTime)
        val readCurrent = findViewById<Button>(R.id.anupathReadCurrent)
        val reset = findViewById<Button>(R.id.anupathReset)
        val disconnect = findViewById<Button>(R.id.anupathDisconnect)
        val sendRaw = findViewById<Button>(R.id.anupathSendRaw)
        rawHexInput = findViewById(R.id.anupathRawHex)
        commandButtons = listOf(lastRecord, syncAll, requestRecord, setTime, readCurrent, disconnect, sendRaw)
        resetButton = reset
        if (!RESET_ENABLED) reset.setText(R.string.anupath_reset_disabled)
        findViewById<View>(R.id.anupathRawRow).visibility = if (DEBUG_TOOLS) View.VISIBLE else View.GONE
        sendRaw.setOnClickListener {
            val bytes = AnuPathProtocol.parseHex(rawHexInput.text.toString())
            if (bytes == null) rawHexInput.error = getString(R.string.anupath_raw_invalid) else sendCommand(bytes)
        }

        lastRecord.setOnClickListener { sendCommand(AnuPathProtocol.CMD_LAST_RECORD) }
        syncAll.setOnClickListener { startFetchAll() }
        requestRecord.setOnClickListener { requestSpecificRecord() }
        setTime.setOnClickListener {
            val payload = AnuPathProtocol.dateTimePayload()
            client.write(AnuPathProtocol.SET_DATE_TIME, payload, AnuPathProtocol.decodeString(payload))
        }
        readCurrent.setOnClickListener {
            AnuPathProtocol.RECORD_CHARACTERISTICS.forEach { client.read(it) }
            client.read(AnuPathProtocol.STATUS)
        }
        reset.setOnClickListener { confirmReset() }
        disconnect.setOnClickListener { client.close() }

        findViewById<Button>(R.id.anupathClear).setOnClickListener {
            records.clear()
            resultsContainer.removeAllViews()
            assembler.reset()
            updateResultsHeader()
        }
        findViewById<Button>(R.id.anupathCopyLog).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("anuPath BLE log", logLines.joinToString("\n")))
            Toast.makeText(this, R.string.anupath_log_copied, Toast.LENGTH_SHORT).show()
        }

        setCommandsEnabled(false)
        updateResultsHeader()
        showStatus(
            if (bluetoothAdapter == null || !packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
                getString(R.string.anupath_no_ble) else getString(R.string.anupath_idle)
        )
    }

    override fun onDestroy() {
        stopScan()
        main.removeCallbacksAndMessages(null)
        if (hasBlePermissions()) client.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------ scan

    /** Permissions -> adapter on -> (API <= 30) location services on -> scan. */
    private fun startScanFlow() {
        val adapter = bluetoothAdapter ?: return showStatus(getString(R.string.anupath_no_ble))
        if (!hasBlePermissions()) {
            log("Requesting ${requiredPermissions.joinToString()}")
            permissionLauncher.launch(requiredPermissions)
            return
        }
        if (!adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val lm = getSystemService(LocationManager::class.java)
            if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.anupath_location_off)
                    .setPositiveButton(R.string.anupath_open_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return
            }
        }
        val scanner = adapter.bluetoothLeScanner ?: return showStatus(getString(R.string.anupath_bt_off))

        foundDevices.clear()
        deviceList.removeAllViews()
        noDevicesText.visibility = View.VISIBLE
        noDevicesText.setText(R.string.anupath_scanning)

        // No hardware ScanFilter: the name may sit in the scan response rather than the
        // advertisement, so we filter by name in the callback (guide §3).
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        scanning = true
        scanButton.setText(R.string.anupath_stop_scan)
        showStatus(getString(R.string.anupath_scanning))
        log("Scan started")
        main.postDelayed(scanTimeout, SCAN_DURATION_MS)
    }

    private val scanTimeout = Runnable { stopScan() }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        if (hasBlePermissions()) bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanButton.setText(R.string.anupath_scan)
        if (foundDevices.isEmpty()) noDevicesText.setText(R.string.anupath_no_devices)
        if (client.state == AnuPathBleClient.State.DISCONNECTED) {
            showStatus(getString(R.string.anupath_scan_done, foundDevices.size))
        }
        log("Scan stopped; ${foundDevices.size} device(s)")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            val isAnuPath = name?.contains(AnuPathProtocol.DEVICE_NAME, ignoreCase = true) == true
            if (!isAnuPath && !showAllCheck.isChecked) return
            addOrUpdateDevice(result.device, name, result.rssi, isAnuPath)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            scanButton.setText(R.string.anupath_scan)
            // 2 = APPLICATION_REGISTRATION_FAILED, often caused by >5 scan starts in 30 s.
            showStatus(getString(R.string.anupath_scan_failed, errorCode))
            log("Scan failed: $errorCode")
        }
    }

    private fun addOrUpdateDevice(device: BluetoothDevice, name: String?, rssi: Int, isAnuPath: Boolean) {
        val label = "${name ?: "(no name)"}   ·   ID ${AnuPathProtocol.shortId(device.address)}\n" +
            "MAC ${device.address}   ·   RSSI $rssi dBm"
        foundDevices[device.address]?.let { (_, row) ->
            row.text = label
            return
        }
        val row = TextView(this).apply {
            text = label
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTypeface(typeface, if (isAnuPath) Typeface.BOLD else Typeface.NORMAL)
            setBackgroundResource(selectableBackground())
            setOnClickListener { connectTo(device, name) }
        }
        foundDevices[device.address] = device to row
        deviceList.addView(row)
        noDevicesText.visibility = View.GONE
        if (isAnuPath) log("Found $name ${device.address} rssi=$rssi")
    }

    private fun connectTo(device: BluetoothDevice, name: String?) {
        if (!hasBlePermissions()) return startScanFlow()
        stopScan()
        connectedName = name ?: AnuPathProtocol.DEVICE_NAME
        connectedAddress = device.address
        assembler.reset()
        client.connect(device)
    }

    // ------------------------------------------------------------ commands

    private fun sendCommand(bytes: ByteArray) {
        assembler.flush()
        client.write(AnuPathProtocol.DATA_CONTROL, bytes, AnuPathProtocol.describeCommand(bytes))
    }

    private fun requestSpecificRecord() {
        val id = recordIdInput.text.toString().toIntOrNull()
        if (id == null || id !in AnuPathProtocol.MIN_RECORD_ID..AnuPathProtocol.MAX_RECORD_ID) {
            recordIdInput.error = getString(R.string.anupath_record_id_invalid)
            return
        }
        sendCommand(AnuPathProtocol.requestRecord(id))
    }

    private fun confirmReset() {
        if (!RESET_ENABLED) return
        AlertDialog.Builder(this)
            .setTitle(R.string.anupath_reset)
            .setMessage(R.string.anupath_reset_confirm)
            .setPositiveButton(R.string.anupath_reset) { _, _ -> sendCommand(AnuPathProtocol.CMD_RESET_MEMORY) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ BLE client callbacks (main thread)

    override fun onStateChanged(state: AnuPathBleClient.State, detail: String) {
        showStatus("${state.name.lowercase().replaceFirstChar { it.uppercase() }}: $detail")
        setCommandsEnabled(state == AnuPathBleClient.State.READY)
        if (state == AnuPathBleClient.State.READY) {
            // Pull everything already stored on the device; new tests then arrive as notifications.
            startFetchAll()
        } else {
            stopFetchAll(null)
        }
        if (state == AnuPathBleClient.State.DISCONNECTED) {
            assembler.flush()
            deviceStatusText.visibility = View.GONE
            connectedDeviceText.visibility = View.GONE
        } else {
            connectedDeviceText.visibility = View.VISIBLE
            connectedDeviceText.text = getString(
                if (state == AnuPathBleClient.State.READY) R.string.anupath_connected_device
                else R.string.anupath_connecting_device,
                connectedName, AnuPathProtocol.shortId(connectedAddress), connectedAddress,
            )
        }
    }

    override fun onValue(uuid: UUID, value: ByteArray, source: AnuPathBleClient.Source) {
        val text = AnuPathProtocol.decodeString(value)
        val tag = if (source == AnuPathBleClient.Source.NOTIFY) "NOTIFY" else "READ  "
        log("$tag ${AnuPathProtocol.nameOf(uuid)} = \"$text\" [${AnuPathProtocol.toHex(value)}]")
        when (uuid) {
            AnuPathProtocol.STATUS -> {
                if (fetching) onFetchStatus(text)
                deviceStatusText.visibility = View.VISIBLE
                deviceStatusText.text = getString(R.string.anupath_device_status, AnuPathProtocol.describeStatus(text))
                if (text != "STATUS_OK") showStatus(getString(R.string.anupath_device_status, AnuPathProtocol.describeStatus(text)))
            }
            AnuPathProtocol.DATA_CONTROL -> Unit // command acknowledgement; logged above
            else -> {
                assembler.onField(uuid, text)
                // Emit a partial record if the remaining fields never arrive.
                main.removeCallbacks(flushPending)
                if (assembler.hasPending) main.postDelayed(flushPending, FIELD_IDLE_FLUSH_MS)
            }
        }
    }

    private val flushPending = Runnable { assembler.flush() }

    private var recordsReceived = 0

    // ------------------------------------------------------------ fetch all stored records

    /**
     * FIELD FINDING (PS-AN001-02): Sync all (03 00) is accepted but never sends anything, so
     * "sync all" is implemented by requesting records 1, 2, 3 … one at a time (ASCII id, see
     * AnuPathProtocol.requestRecord). Each reply ends with a Status notification:
     *   STATUS_OK            -> record delivered, ask for the next one;
     *   STATUS_INVALID_COUNT -> past the last stored record, done.
     * Sequential requests also keep the device awake (it sleeps ~15 s after the last command).
     */
    private var fetching = false
    private var fetchNextId = 0
    private var fetchedCount = 0

    private val fetchTimeout = Runnable {
        log("Fetch: no reply for record #$fetchNextId within ${FETCH_STEP_TIMEOUT_MS} ms")
        stopFetchAll(getString(R.string.anupath_fetch_done, fetchedCount))
    }

    private fun startFetchAll() {
        if (client.state != AnuPathBleClient.State.READY) return
        stopFetchAll(null)
        log("Fetch all: requesting stored records one by one")
        fetchedCount = 0
        fetching = true
        fetchNextId = AnuPathProtocol.MIN_RECORD_ID
        requestNextForFetch()
    }

    private fun requestNextForFetch() {
        showStatus(getString(R.string.anupath_fetch_progress, fetchNextId))
        sendCommand(AnuPathProtocol.requestRecord(fetchNextId))
        main.removeCallbacks(fetchTimeout)
        main.postDelayed(fetchTimeout, FETCH_STEP_TIMEOUT_MS)
    }

    private fun onFetchStatus(status: String) {
        main.removeCallbacks(fetchTimeout)
        when {
            status == AnuPathProtocol.STATUS_OK && fetchNextId < AnuPathProtocol.MAX_RECORD_ID -> {
                assembler.flush()
                fetchedCount++
                fetchNextId++
                main.postDelayed({ if (fetching) requestNextForFetch() }, FETCH_STEP_GAP_MS)
            }
            status == AnuPathProtocol.STATUS_OK -> {
                fetchedCount++
                stopFetchAll(getString(R.string.anupath_fetch_done, fetchedCount))
            }
            // Ids may be 0- or 1-based: if id 0 is rejected, carry on from id 1.
            status == AnuPathProtocol.STATUS_INVALID_COUNT && fetchNextId == AnuPathProtocol.MIN_RECORD_ID -> {
                log("Fetch: id 0 rejected, continuing from id 1")
                fetchNextId++
                main.postDelayed({ if (fetching) requestNextForFetch() }, FETCH_STEP_GAP_MS)
            }
            status == AnuPathProtocol.STATUS_INVALID_COUNT ->
                stopFetchAll(getString(R.string.anupath_fetch_done, fetchedCount))
            else -> stopFetchAll(getString(R.string.anupath_device_status, AnuPathProtocol.describeStatus(status)))
        }
    }

    private fun stopFetchAll(message: String?) {
        main.removeCallbacks(fetchTimeout)
        if (!fetching) return
        fetching = false
        assembler.flush()
        if (message != null) {
            log("Fetch all finished: $fetchedCount record(s)")
            showStatus(message)
            // Safety net: always include the newest result, whatever the id numbering is.
            if (client.state == AnuPathBleClient.State.READY) sendCommand(AnuPathProtocol.CMD_LAST_RECORD)
        }
    }

    override fun onLog(message: String) = log(message)

    // ------------------------------------------------------------ results

    private fun onRecord(incoming: TestRecord) {
        val record = incoming.copy(deviceAddress = connectedAddress)
        recordsReceived++
        log("RECORD #${record.sequence} ${record.name} ${record.value} ${record.unit} @ ${record.dateTime}" +
            if (record.isComplete) "" else " (partial)")
        val card = records[record.key]?.second ?: buildRecordCard()
        bindRecordCard(card, record)
        records[record.key] = record to card
        // Keep the list ordered by test date (newest first), whatever order the device sends in.
        val ordered = records.values.sortedWith(compareBy(AnuPathProtocol.NEWEST_FIRST) { it.first })
        ordered.forEachIndexed { index, (_, view) ->
            if (resultsContainer.getChildAt(index) !== view) {
                (view.parent as? LinearLayout)?.removeView(view)
                resultsContainer.addView(view, index)
            }
        }
        updateResultsHeader()
    }

    private fun buildRecordCard(): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        inner.addView(TextView(this).apply { id = R.id.anupathCardTitle; textSize = 14f })
        inner.addView(TextView(this).apply {
            id = R.id.anupathCardValue; textSize = 30f; setTypeface(typeface, Typeface.BOLD)
        })
        inner.addView(TextView(this).apply { id = R.id.anupathCardMeta; textSize = 12f })
        card.addView(inner)
        return card
    }

    private fun bindRecordCard(card: View, r: TestRecord) {
        card.findViewById<TextView>(R.id.anupathCardTitle).text = r.name.ifEmpty { "Unknown test" }
        card.findViewById<TextView>(R.id.anupathCardValue).text =
            listOf(r.value.ifEmpty { "–" }, r.unit).filter { it.isNotEmpty() }.joinToString(" ")
        card.findViewById<TextView>(R.id.anupathCardMeta).text = buildString {
            append("Record #").append(r.sequence.ifEmpty { "?" })
            append("   ·   Tested ").append(r.dateTime.ifEmpty { "?" })
            append("\nDevice ID ").append(AnuPathProtocol.shortId(r.deviceAddress))
            append("   ·   Received ").append(timeFormat.format(Date(r.receivedAtMillis)))
            if (!r.isComplete) append("   ·   partial")
        }
    }

    private fun updateResultsHeader() {
        resultsLabel.text = getString(R.string.anupath_results_count, records.size)
        noResultsText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ helpers

    private fun setCommandsEnabled(enabled: Boolean) {
        commandButtons.forEach { it.isEnabled = enabled }
        resetButton.isEnabled = enabled && RESET_ENABLED
        recordIdInput.isEnabled = enabled
        rawHexInput.isEnabled = enabled
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        logLines.addLast("${timeFormat.format(Date())}  $message")
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        logText.text = logLines.joinToString("\n")
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun selectableBackground(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    companion object {
        private const val TAG = "AnuPathActivity"
        private const val SCAN_DURATION_MS = 15_000L
        private const val FIELD_IDLE_FLUSH_MS = 700L
        private const val FETCH_STEP_TIMEOUT_MS = 3_000L
        private const val FETCH_STEP_GAP_MS = 150L
        /** Shows the raw-hex command row (used to reverse-engineer the firmware). */
        private const val DEBUG_TOOLS = false

        /** Reset memory (04 00) erases every result on the device; disabled until it is needed and tested. */
        private const val RESET_ENABLED = false
        private const val MAX_LOG_LINES = 300
    }
}
