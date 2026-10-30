# anuPath nano A1C – BLE POC

Launcher option **3. anuPath nano A1C (Bluetooth)** opens `anupath.AnuPathActivity`.
Spec: *anuPath nano Device Integration Guide for App Developers V1.2* (2025-07-30).

## Flow

1. **Permissions** – Android 12+: runtime `BLUETOOTH_SCAN` (`neverForLocation`) + `BLUETOOTH_CONNECT`.
   Android 11 and below: legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` (install-time) + runtime
   `ACCESS_FINE_LOCATION`, and Location services must be on for scan results.
2. **Bluetooth on** – prompts `ACTION_REQUEST_ENABLE` if the adapter is off.
3. **Scan** (15 s, low latency) – keeps devices whose advertised name contains `anuPath nano`
   (filtered in the callback because the name may be in the scan response). "Show all BLE devices"
   lists everything for debugging.
4. **Connect** (tap a row) – `connectGatt(TRANSPORT_LE)`, no pairing/bonding.
5. **Setup** (serial GATT queue) – discover services → MTU 185 → enable notifications on all 7
   Notify characteristics (mandatory per guide) → write current clock to *Set Date Time*
   (`yyyy|MM|dd|HH|mm|ss`) → read *Status* → READY → **automatic Sync all** (`03 00`) so every
   result already stored on the device is read.
6. **Commands** (Data Control `5c01bad3-…`): Last record `01 00`, Sync all `03 00`,
   Request record `02 hi lo`, Reset memory `04 00` (confirm dialog). "Read values" reads the five
   result characteristics directly (useful if notifications don't fire).
7. **Results** – the device sends sequence / name / value / unit / date-time as separate string
   notifications. `RecordAssembler` groups them into one `TestRecord` (any order; emits when all five
   arrive, when a field repeats with a new value, or after 700 ms idle). Cards are de-duplicated by
   sequence + test time (re-syncing never duplicates) and kept **sorted by test date, newest first**.
   A test run while connected is notified by the device and slotted into place by its date.
   Results live in memory only – they're re-read from the device on every connect. Works for HbA1c (%), Blood Glucose (mg/dL) and Hemoglobin (g/dL).
8. **Raw BLE log** – every notification/read with text + hex, copyable. Logcat tags: `AnuPathBle`,
   `AnuPathActivity`.

## Files

| File | Role |
|---|---|
| `anupath/AnuPathProtocol.kt` | UUIDs, command bytes, date-time payload, `TestRecord`, `RecordAssembler` (pure Kotlin) |
| `anupath/AnuPathBleClient.kt` | GATT connection + one-at-a-time operation queue with 5 s timeouts |
| `anupath/AnuPathActivity.kt` | Permissions, scan, device list, commands, result cards, log |
| `res/layout/activity_anupath.xml`, `res/values/ids_anupath.xml` | UI |
| `test/.../anupath/AnuPathProtocolTest.kt` | JVM tests for commands and record assembly |

## Open questions for the vendor

- Request-record id encoding for ids > 255 (we send big-endian `02 hi lo`; matches the documented
  `02 00 05` example).
- Order of the five result notifications, and whether unchanged fields are re-notified
  (the assembler handles both, carrying forward last-known values).
- Possible *Status* strings and what Data Control notifies back after a command.
- Whether *Sync all* signals end-of-transfer.

## Test checklist

- [ ] Android 12+ phone: permission prompt → scan finds `anuPath nano` → connect → READY.
- [ ] Android 10/11 phone: location permission + location-off dialog.
- [ ] Last record shows the latest HbA1c card; Sync all lists every stored record once.
- [ ] Run a fresh A1C test while connected → card appears without pressing anything.
- [ ] Device clock matches phone after connect.
- [ ] Turn device off mid-session → state goes to Disconnected, commands disabled; reconnect works.

## Field findings (PS-AN001-02, 2026-10-29)

- Scan/connect/notify work; MTU negotiates to 247. No pairing needed.
- **Last record `01 00`** works: fields arrive in order Sequence → Name → Value → Unit → Date Time,
  then `Status = STATUS_OK`. Date format confirmed as `dd-MM-yyyy HH:mm`.
- **Sync all `03 00`**: write accepted, but nothing arrives on any documented characteristic.
- **Request record `02 00 01`** → `Status = STATUS_INVALID_COUNT`.
- Firmware exposes two **undocumented Read/Notify characteristics**: `7c7220c1-9250-4b17-bb83-792395eb6505`
  and `2a2185ba-3160-4b5a-8687-db53b1606096`. The app now subscribes to and reads them, since Sync all
  replies may be sent there.
- Link drops with status 8 (supervision timeout) ~20–40 s after the last activity, most likely the
  device's auto power-off.
- Ask vendor: the Sync all reply channel, what the undocumented characteristics hold, the meaning of
  `STATUS_INVALID_COUNT` and the exact request-record format, and the idle power-off time.

### Round 2 (undocumented characteristics subscribed)

- Both undocumented characteristics read empty and never notify; Sync all is still silent.
- `02 00 01/02/03` → `STATUS_INVALID_COUNT`; `02 01 00` and `02 01` → `STATUS_INVALID_CMD`.
  So `02 00` is the recognised command prefix and only the parameter is wrong or out of range.
- Working hypothesis: the stored-records memory is empty (sequence "3" is a test counter, and Last
  record reads a separate last-result buffer), so Sync all has nothing to send. Alternatively the
  parameter is 16-bit (`02 00 00 01` / `02 00 01 00`).
- App behaviour: if Sync all yields no record within 3 s of connecting, the app requests Last record
  automatically.

### Round 3

- Request record variants `02 00 00`, `02 00 00 01`, `02 00 01 00` → all `STATUS_INVALID_COUNT`.
  Every parameter is rejected and Sync all stays silent, which strongly suggests the record memory is
  empty (Last record reads a separate last-result buffer). Needs vendor confirmation.
- Bug fixed: Android sometimes fires `onServicesDiscovered` twice on one connection (once with an
  empty list). The client now runs setup once per connection and re-discovers if the anuPath service
  is missing, instead of disconnecting.

### Round 4 – request-record format found

- **`02 00 31` returns a record.** The record id is sent as **ASCII digits** after `02 00`
  (record 1 = `02 00 31`), not as a binary byte as the guide's example `02 00 05` implies.
- The device's own memory held 4 results (Hemoglobin + 3 × Glucose), so the earlier "empty memory"
  hypothesis was wrong. Sync all `03 00` is simply silent on this firmware.
- **App now syncs by iterating Request record 1, 2, 3 …** on connect (and on the Sync all button):
  each reply ends with `STATUS_OK` → next id; `STATUS_INVALID_COUNT` → end of memory; 3 s with no
  reply → stop. Results are de-duplicated and sorted by test date.
- Still to verify: multi-digit ids (`02 00 31 32` for record 12), whether id 1 is the oldest or newest,
  and the device's memory capacity.

### Round 5 – confirmed

- Record ids are **0-based**: the sync requests `02 00 30`, `02 00 31`, `02 00 32`, `02 00 33` … and
  all 4 stored results come back. `STATUS_INVALID_COUNT` marks the end of memory.
- Debug probe removed; the raw-hex row is hidden (set `DEBUG_TOOLS = true` in `AnuPathActivity`
  to show it again).
- Reset memory is shown but disabled (`RESET_ENABLED = false` in `AnuPathActivity`) to avoid
  erasing results on the device during the POC.
- Device identity shown in the UI: each scan row shows `ID <last 2 MAC bytes>` (e.g. `ID 63:26`) plus
  the full MAC and RSSI; a green banner shows the connected device's name, ID and MAC; each result card
  shows the ID of the device it came from. Results are de-duplicated per device.
  (Device address is random-static type `C0:…` – confirm with vendor it is fixed for the unit's life.)
