package com.tanuh.edgesdk.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Debug

/**
 * Cheap memory snapshots for comparing loading strategies.
 *
 *  - javaUsedMb  : JVM heap in use. A ByteArray model copy shows up here (and can OOM).
 *  - javaMaxMb   : the per-app Java heap ceiling (Runtime.maxMemory) - what an OOM is measured against.
 *  - nativeMb    : malloc'd native heap. ORT session weights / OpenCV Mats / Bitmaps (API 26+) land here.
 *  - pssMb       : proportional set size of the whole process (includes mmapped file pages that are
 *                  currently resident). Optional because Debug.getPss() takes a few ms.
 */
data class MemorySnapshot(val javaUsedMb: Double, val javaMaxMb: Double, val nativeMb: Double, val pssMb: Double?) {
    fun deltaFrom(before: MemorySnapshot) = "Δjava=%+.1f MB  Δnative=%+.1f MB%s".format(
        javaUsedMb - before.javaUsedMb, nativeMb - before.nativeMb,
        if (pssMb != null && before.pssMb != null) "  ΔPSS=%+.1f MB".format(pssMb - before.pssMb) else "",
    )

    override fun toString() = "java=%.1f/%.0f MB  native=%.1f MB%s".format(
        javaUsedMb, javaMaxMb, nativeMb, pssMb?.let { "  PSS=%.1f MB".format(it) } ?: "",
    )
}

object MemoryProbe {
    private const val MB = 1024.0 * 1024.0

    fun snapshot(withPss: Boolean): MemorySnapshot {
        val rt = Runtime.getRuntime()
        return MemorySnapshot(
            javaUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB,
            javaMaxMb = rt.maxMemory() / MB,
            nativeMb = Debug.getNativeHeapAllocatedSize() / MB,
            pssMb = if (withPss) Debug.getPss() / 1024.0 else null,
        )
    }

    fun deviceSummary(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "device RAM=%.0f MB avail=%.0f MB lowRam=%s | heap class=%d MB (large=%d MB) | cores=%d".format(
            mi.totalMem / MB, mi.availMem / MB, am.isLowRamDevice, am.memoryClass, am.largeMemoryClass,
            Runtime.getRuntime().availableProcessors(),
        )
    }
}
