package com.remmi.browser.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.remmi.adblock.BlockExtension
import com.remmi.browser.BuildConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight in-memory process memory telemetry & cosmetic audit tracker.
 * Samples every 5 seconds while active without disk I/O.
 */
object ProcessMemoryTelemetry {
  private const val TAG = "ProcessMemoryTelemetry"
  private const val MAX_RING_BUFFER_SAMPLES = 360 // 30 minutes at 5s intervals
  private const val SAMPLE_INTERVAL_SECONDS = 5L

  data class MemorySnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val uptimeMs: Long = SystemClock.uptimeMillis(),
    val rssBytes: Long = 0L,
    val pssBytes: Long = 0L,
    val javaHeapUsedBytes: Long = 0L,
    val javaHeapMaxBytes: Long = 0L,
    val nativeHeapAllocatedBytes: Long = 0L,
    val runningDecisionWorkers: Int = 0,
    val queuedCosmeticTasks: Int = 0,
    val activeCosmeticRequests: Int = 0,
    val cosmeticCacheEntries: Int = 0,
    val cosmeticCacheBytes: Long = 0L,
    val inflightDecisionCount: Int = 0,
    val inflightCosmeticCount: Int = 0,
    val droppedCosmeticCount: Long = 0L,
    val droppedNetworkCount: Long = 0L
  )

  private val ringBuffer = ConcurrentLinkedQueue<MemorySnapshot>()
  private val sampleCount = AtomicInteger(0)
  private val isSampling = AtomicBoolean(false)
  private val activeSubscribers = AtomicInteger(0)

  private val telemetryExecutor = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "RemmiMemTelemetry").apply {
      isDaemon = true
      priority = Thread.MIN_PRIORITY
    }
  }

  private var scheduledFuture: ScheduledFuture<*>? = null
  private var appContext: Context? = null

  @Volatile var peakRssBytes: Long = 0L
  @Volatile var peakPssBytes: Long = 0L
  @Volatile var peakJavaHeapBytes: Long = 0L
  @Volatile var peakNativeHeapBytes: Long = 0L
  @Volatile var maxCosmeticQueueDepth: Int = 0
  @Volatile var maxCosmeticConcurrency: Int = 0
  @Volatile var maxWorkerCount: Int = 0
  @Volatile var maxCosmeticCacheEntries: Int = 0
  @Volatile var maxCosmeticCacheBytes: Long = 0L

  fun init(context: Context) {
    appContext = context.applicationContext
  }

  fun startSampling() {
    val count = activeSubscribers.incrementAndGet()
    if (count == 1) {
      synchronized(this) {
        if (isSampling.compareAndSet(false, true)) {
          scheduledFuture = telemetryExecutor.scheduleWithFixedDelay(
            { sampleMemory() },
            0L,
            SAMPLE_INTERVAL_SECONDS,
            TimeUnit.SECONDS
          )
          Log.d(TAG, "[TELEMETRY_START] Process memory sampling active (5s interval)")
        }
      }
    }
  }

  fun stopSampling() {
    val count = activeSubscribers.decrementAndGet()
    if (count <= 0) {
      activeSubscribers.set(0)
      synchronized(this) {
        if (isSampling.compareAndSet(true, false)) {
          scheduledFuture?.cancel(false)
          scheduledFuture = null
          Log.d(TAG, "[TELEMETRY_STOP] Process memory sampling paused")
        }
      }
    }
  }

  fun sampleMemory(): MemorySnapshot {
    val snapshot = captureSnapshot(appContext)
    
    // Update peaks
    if (snapshot.rssBytes > peakRssBytes) peakRssBytes = snapshot.rssBytes
    if (snapshot.pssBytes > peakPssBytes) peakPssBytes = snapshot.pssBytes
    if (snapshot.javaHeapUsedBytes > peakJavaHeapBytes) peakJavaHeapBytes = snapshot.javaHeapUsedBytes
    if (snapshot.nativeHeapAllocatedBytes > peakNativeHeapBytes) peakNativeHeapBytes = snapshot.nativeHeapAllocatedBytes
    if (snapshot.queuedCosmeticTasks > maxCosmeticQueueDepth) maxCosmeticQueueDepth = snapshot.queuedCosmeticTasks
    if (snapshot.activeCosmeticRequests > maxCosmeticConcurrency) maxCosmeticConcurrency = snapshot.activeCosmeticRequests
    if (snapshot.runningDecisionWorkers > maxWorkerCount) maxWorkerCount = snapshot.runningDecisionWorkers
    if (snapshot.cosmeticCacheEntries > maxCosmeticCacheEntries) maxCosmeticCacheEntries = snapshot.cosmeticCacheEntries
    if (snapshot.cosmeticCacheBytes > maxCosmeticCacheBytes) maxCosmeticCacheBytes = snapshot.cosmeticCacheBytes

    ringBuffer.add(snapshot)
    while (ringBuffer.size > MAX_RING_BUFFER_SAMPLES) {
      ringBuffer.poll()
    }

    val currentSample = sampleCount.incrementAndGet()
    // Periodic light log every 30s (6 samples) in debug builds only
    if (BuildConfig.DEBUG && currentSample % 6 == 0) {
      val rssMb = snapshot.rssBytes / (1024 * 1024)
      val pssMb = snapshot.pssBytes / (1024 * 1024)
      val javaMb = snapshot.javaHeapUsedBytes / (1024 * 1024)
      val nativeMb = snapshot.nativeHeapAllocatedBytes / (1024 * 1024)
      Log.d(
        TAG,
        "[MEM_TELEMETRY] RSS=${rssMb}MB PSS=${pssMb}MB Java=${javaMb}MB Native=${nativeMb}MB " +
          "Workers=${snapshot.runningDecisionWorkers} Q_Cosm=${snapshot.queuedCosmeticTasks} " +
          "Act_Cosm=${snapshot.activeCosmeticRequests} Cache_Cosm=${snapshot.cosmeticCacheEntries} " +
          "Inflight_Net=${snapshot.inflightDecisionCount} Inflight_Cosm=${snapshot.inflightCosmeticCount}"
      )
    }

    return snapshot
  }

  fun captureSnapshot(context: Context? = null): MemorySnapshot {
    val rt = Runtime.getRuntime()
    val javaUsed = rt.totalMemory() - rt.freeMemory()
    val javaMax = rt.maxMemory()
    val nativeAlloc = try {
      Debug.getNativeHeapAllocatedSize()
    } catch (_: Throwable) {
      0L
    }

    val rss = readProcessRssBytes()
    val pss = queryProcessPssBytes(context)

    val workerCount = BlockExtension.getActiveWorkerCount()
    val queuedCosmetic = BlockExtension.getQueuedCosmeticCount()
    val activeCosmetic = BlockExtension.getActiveCosmeticCount()
    val cacheEntries = BlockExtension.getCosmeticCacheEntries()
    val cacheBytes = BlockExtension.getCosmeticCacheBytes()
    val inflightDecisions = BlockExtension.getInflightDecisionCount()
    val inflightCosmetic = BlockExtension.getInflightCosmeticCount()
    val droppedCosmetic = BlockExtension.getCosmeticDroppedCount()
    val droppedNetwork = BlockExtension.getNetworkDroppedCount()

    return MemorySnapshot(
      timestamp = System.currentTimeMillis(),
      uptimeMs = SystemClock.uptimeMillis(),
      rssBytes = rss,
      pssBytes = pss,
      javaHeapUsedBytes = javaUsed,
      javaHeapMaxBytes = javaMax,
      nativeHeapAllocatedBytes = nativeAlloc,
      runningDecisionWorkers = workerCount,
      queuedCosmeticTasks = queuedCosmetic,
      activeCosmeticRequests = activeCosmetic,
      cosmeticCacheEntries = cacheEntries,
      cosmeticCacheBytes = cacheBytes,
      inflightDecisionCount = inflightDecisions,
      inflightCosmeticCount = inflightCosmetic,
      droppedCosmeticCount = droppedCosmetic,
      droppedNetworkCount = droppedNetwork
    )
  }

  private fun readProcessRssBytes(): Long {
    return try {
      val statm = File("/proc/self/statm")
      if (statm.exists() && statm.canRead()) {
        val line = statm.readText().trim()
        val parts = line.split("\\s+".toRegex())
        if (parts.size >= 2) {
          val residentPages = parts[1].toLongOrNull() ?: 0L
          // Standard Linux page size is 4096 bytes
          return residentPages * 4096L
        }
      }
      // Fallback
      val status = File("/proc/self/status")
      if (status.exists() && status.canRead()) {
        val text = status.readText()
        val match = Regex("""VmRSS:\s+(\d+)\s+kB""").find(text)
        if (match != null) {
          val kb = match.groupValues[1].toLongOrNull() ?: 0L
          return kb * 1024L
        }
      }
      0L
    } catch (_: Throwable) {
      0L
    }
  }

  private fun queryProcessPssBytes(context: Context?): Long {
    val ctx = context ?: appContext
    if (ctx != null) {
      try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
          val pid = Process.myPid()
          val memInfos = am.getProcessMemoryInfo(intArrayOf(pid))
          if (memInfos.isNotEmpty()) {
            return memInfos[0].totalPss * 1024L
          }
        }
      } catch (_: Throwable) {}
    }
    return try {
      Debug.getPss() * 1024L
    } catch (_: Throwable) {
      0L
    }
  }

  fun getRecentSnapshots(): List<MemorySnapshot> {
    return ringBuffer.toList()
  }

  fun get30MinuteTrend(): String {
    val list = ringBuffer.toList()
    if (list.size < 12) return "UNKNOWN (insufficient sample window)"
    
    val firstQuarter = list.take(list.size / 4)
    val lastQuarter = list.takeLast(list.size / 4)
    
    val firstAvgRss = firstQuarter.map { it.rssBytes }.average()
    val lastAvgRss = lastQuarter.map { it.rssBytes }.average()
    val firstAvgJava = firstQuarter.map { it.javaHeapUsedBytes }.average()
    val lastAvgJava = lastQuarter.map { it.javaHeapUsedBytes }.average()

    val rssGrowthRatio = if (firstAvgRss > 0) (lastAvgRss - firstAvgRss) / firstAvgRss else 0.0
    val javaGrowthRatio = if (firstAvgJava > 0) (lastAvgJava - firstAvgJava) / firstAvgJava else 0.0

    return when {
      rssGrowthRatio > 0.35 || javaGrowthRatio > 0.50 -> "GROWING"
      else -> "STABLE"
    }
  }

  fun getForensicSummary(): String {
    val latest = getRecentSnapshots().lastOrNull() ?: captureSnapshot()
    val trend = get30MinuteTrend()
    val peakRssMb = peakRssBytes / (1024 * 1024)
    val peakPssMb = peakPssBytes / (1024 * 1024)
    val peakJavaMb = peakJavaHeapBytes / (1024 * 1024)
    val peakNativeMb = peakNativeHeapBytes / (1024 * 1024)
    val currRssMb = latest.rssBytes / (1024 * 1024)
    val currPssMb = latest.pssBytes / (1024 * 1024)
    val currJavaMb = latest.javaHeapUsedBytes / (1024 * 1024)
    val currNativeMb = latest.nativeHeapAllocatedBytes / (1024 * 1024)

    return """
MEMORY & COSMETIC FORENSICS SUMMARY:
- Current RSS: ${currRssMb} MB (Peak: ${peakRssMb} MB)
- Current PSS: ${currPssMb} MB (Peak: ${peakPssMb} MB)
- Java Heap: ${currJavaMb} MB / ${latest.javaHeapMaxBytes / (1024 * 1024)} MB (Peak: ${peakJavaMb} MB)
- Native Heap: ${currNativeMb} MB (Peak: ${peakNativeMb} MB)
- 30-Minute Memory Trend: $trend
- Maximum Cosmetic Queue Depth: $maxCosmeticQueueDepth
- Maximum Active Cosmetic Tasks: $maxCosmeticConcurrency
- Maximum Worker Thread Concurrency: $maxWorkerCount
- Maximum Cosmetic Cache Entries: $maxCosmeticCacheEntries
- Maximum Cosmetic Cache Bytes: $maxCosmeticCacheBytes
- Dropped/Coalesced Cosmetic Requests: ${latest.droppedCosmeticCount}
- Dropped Network SHOULD_BLOCK Requests: ${latest.droppedNetworkCount} (INVARIANT: 0)
    """.trimIndent()
  }
}
