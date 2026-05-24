package com.talq2me.baerenlock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight amplitude sampling during active reward sessions only.
 */
class AudioMonitor(
    private val context: Context,
    private val onSustainedLoudness: () -> Unit
) {
    private val tag = "AudioMonitor"
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var audioRecord: AudioRecord? = null
    private var running = false
    private var tickCount = 0

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var thresholdPercent: Int = DEFAULT_THRESHOLD
        set(value) {
            val clamped = value.coerceIn(0, 100)
            if (field != clamped) {
                field = clamped
                resetLoudnessTracking()
            }
        }

    private var loudSinceMs: Long = 0L
    private var quietSinceMs: Long = 0L
    private var monitorStartedAtMs: Long = 0L
    private var lastHeartbeatLogMs: Long = 0L
    private var lastPermissionWarnMs: Long = 0L
    private var lastRecorderWarnMs: Long = 0L
    private var consecutiveZeroPeakTicks = 0
    private var hadSignalThisSession = false
    private var lastStrongSignalMs: Long = 0L
    private var overloadMuteActive = false

    private val sampleRunnable: Runnable = Runnable { onSampleTick() }

    private data class SampleMetrics(
        val samplesRead: Int,
        val peakRaw: Int,
        val rmsRaw: Int,
        val peakPercent: Int,
        val rmsPercent: Int,
        val levelPercent: Int,
        val overloadMute: Boolean,
    )

    private fun onSampleTick() {
        if (!running) return
        tickCount++
        val now = System.currentTimeMillis()
        var pauseTriggered = false
        try {
            val sessionActive = RewardManager.isRewardSessionActive()
            if (!enabled || !sessionActive) {
                stopSamplingInternal()
                maybeLogHeartbeat(
                    now,
                    "idle enabled=$enabled session=$sessionActive perm=${hasRecordPermission()}"
                )
                return
            }
            if (!hasRecordPermission()) {
                if (shouldWarnNow(lastPermissionWarnMs)) {
                    lastPermissionWarnMs = SystemClock.elapsedRealtime()
                    Log.w(tag, "RECORD_AUDIO not granted; loudness monitor cannot sample")
                }
                maybeLogHeartbeat(now, "no mic permission")
                return
            }
            if (!ensureRecorder()) {
                maybeLogHeartbeat(now, "recorder init failed")
                return
            }
            val record = audioRecord ?: run {
                maybeLogHeartbeat(now, "no AudioRecord instance")
                return
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    record.startRecording()
                } catch (e: Exception) {
                    Log.w(tag, "startRecording failed: ${e.message}")
                    maybeLogHeartbeat(now, "startRecording failed")
                    return
                }
            }
            val metrics = measureLevel(record, now)
            if (metrics != null) {
                val effectiveLevel = if (metrics.overloadMute) {
                    max(metrics.levelPercent, OVERLOAD_SYNTHETIC_LEVEL_PERCENT)
                } else {
                    metrics.levelPercent
                }
                val inGracePeriod = now - monitorStartedAtMs < STARTUP_GRACE_MS
                maybeLogHeartbeat(
                    now,
                    "sample n=${metrics.samplesRead} peakRaw=${metrics.peakRaw} rmsRaw=${metrics.rmsRaw} " +
                        "peak=${metrics.peakPercent}% level=$effectiveLevel% threshold=$thresholdPercent% " +
                        "loudForMs=${loudDurationMs(now)} needMs=$SUSTAINED_LOUD_MS grace=$inGracePeriod"
                )
                if (!inGracePeriod) {
                    updateLoudnessState(now, effectiveLevel)
                    val loudMs = loudDurationMs(now)
                    // Must be loud right now, not only "was loud" during hysteresis tail.
                    if (loudMs >= SUSTAINED_LOUD_MS && effectiveLevel >= thresholdPercent) {
                        Log.w(
                            tag,
                            "Sustained loudness (level=$effectiveLevel% peakRaw=${metrics.peakRaw} " +
                                "loudMs=$loudMs >= threshold=$thresholdPercent%), triggering pause"
                        )
                        loudSinceMs = 0L
                        quietSinceMs = 0L
                        onSustainedLoudness()
                        pauseTriggered = true
                        stop()
                        return
                    }
                }
            } else {
                maybeLogHeartbeat(now, "no audio samples this tick")
            }
        } catch (e: Exception) {
            Log.e(tag, "Sample error on tick $tickCount", e)
            maybeLogHeartbeat(now, "error=${e.message}")
        } finally {
            if (!pauseTriggered && running) {
                handler?.postDelayed(sampleRunnable, SAMPLE_INTERVAL_MS)
            }
        }
    }

    /**
     * Uses a short rolling max so one loud tick isn't wiped by quiet ticks between 3s heartbeats.
     * Resets the loud timer only after [QUIET_RESET_MS] below threshold (hysteresis).
     */
    private fun updateLoudnessState(nowMs: Long, levelPercent: Int) {
        if (levelPercent >= thresholdPercent) {
            quietSinceMs = 0L
            if (loudSinceMs == 0L) loudSinceMs = nowMs
            return
        }
        if (quietSinceMs == 0L) quietSinceMs = nowMs
        if (nowMs - quietSinceMs >= QUIET_RESET_MS) {
            loudSinceMs = 0L
        }
    }

    private fun resetLoudnessTracking() {
        loudSinceMs = 0L
        quietSinceMs = 0L
        consecutiveZeroPeakTicks = 0
        hadSignalThisSession = false
        lastStrongSignalMs = 0L
        overloadMuteActive = false
    }

    /**
     * Some emulators/AGC paths return all-zero PCM when input is very loud (clipping/mute).
     * If we recently had real signal and then get full buffers of zeros, treat that as loud.
     */
    private fun detectOverloadMute(metrics: SampleMetrics, nowMs: Long): Boolean {
        if (!isLikelyEmulator()) {
            return false
        }
        val rawLevel = max(metrics.peakPercent, metrics.rmsPercent)
        if (metrics.peakRaw >= STRONG_PEAK_RAW || rawLevel >= STRONG_LEVEL_PERCENT) {
            hadSignalThisSession = true
            lastStrongSignalMs = nowMs
            consecutiveZeroPeakTicks = 0
            overloadMuteActive = false
            return false
        }
        if (metrics.samplesRead >= MIN_SAMPLES_FOR_OVERLOAD_CHECK && metrics.peakRaw == 0) {
            consecutiveZeroPeakTicks++
        } else {
            consecutiveZeroPeakTicks = 0
        }
        val recentStrongSignal = lastStrongSignalMs > 0L && (nowMs - lastStrongSignalMs) <= STRONG_SIGNAL_WINDOW_MS
        val suspected = hadSignalThisSession &&
            recentStrongSignal &&
            consecutiveZeroPeakTicks >= CONSECUTIVE_ZERO_PEAK_TICKS
        if (suspected && !overloadMuteActive) {
            Log.w(
                tag,
                "Suspected mic overload mute (zeros after recent signal); treating as loud for detection"
            )
        }
        overloadMuteActive = suspected
        return suspected
    }

    private fun measureLevel(record: AudioRecord, nowMs: Long): SampleMetrics? {
        val buffer = ShortArray(SAMPLE_SIZE)
        var totalPeak = 0
        var sumSq = 0.0
        var totalSamples = 0
        var reads = 0
        while (reads < MAX_READS_PER_TICK) {
            val n = readSamples(record, buffer)
            if (n <= 0) break
            reads++
            for (i in 0 until n) {
                val s = buffer[i].toInt()
                val amp = abs(s)
                if (amp > totalPeak) totalPeak = amp
                sumSq += (s * s).toDouble()
            }
            totalSamples += n
            if (n < buffer.size) break
        }
        if (totalSamples == 0) return null
        val rms = sqrt(sumSq / totalSamples)
        val rmsInt = rms.toInt().coerceAtLeast(0)
        val peakPct = amplitudeToPercent(totalPeak)
        val rmsPct = if (rmsInt > 0) amplitudeToPercent(rmsInt) else 0
        val level = peakPct
        val base = SampleMetrics(
            samplesRead = totalSamples,
            peakRaw = totalPeak,
            rmsRaw = rmsInt,
            peakPercent = peakPct,
            rmsPercent = rmsPct,
            levelPercent = level,
            overloadMute = false,
        )
        val overload = detectOverloadMute(base, nowMs)
        return base.copy(overloadMute = overload)
    }

    fun isRunning(): Boolean = running

    fun start() {
        if (running) return
        running = true
        resetLoudnessTracking()
        monitorStartedAtMs = System.currentTimeMillis()
        lastHeartbeatLogMs = 0L
        tickCount = 0
        handlerThread = HandlerThread("AudioMonitor").apply { start() }
        handler = Handler(handlerThread!!.looper)
        handler?.post(sampleRunnable)
        Log.i(
            tag,
            "Audio monitor started (enabled=$enabled, threshold=$thresholdPercent, " +
                "graceMs=$STARTUP_GRACE_MS, micPerm=${hasRecordPermission()}, " +
                "sessionActive=${RewardManager.isRewardSessionActive()})"
        )
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        stopSamplingInternal()
        loudSinceMs = 0L
        quietSinceMs = 0L
        Log.d(tag, "Audio monitor stopped")
    }

    /** Prefer blocking read — short buffer (~32ms) so we get real PCM, not stale zeros. */
    private fun readSamples(record: AudioRecord, buffer: ShortArray): Int {
        return record.read(buffer, 0, buffer.size)
    }

    private fun stopSamplingInternal() {
        try {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Log.w(tag, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
    }

    private fun ensureRecorder(): Boolean {
        audioRecord?.let { existing ->
            if (existing.state == AudioRecord.STATE_INITIALIZED) {
                return true
            }
            Log.w(tag, "AudioRecord bad state=${existing.state}, recreating")
            stopSamplingInternal()
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            if (shouldWarnNow(lastRecorderWarnMs)) {
                lastRecorderWarnMs = SystemClock.elapsedRealtime()
                Log.w(tag, "getMinBufferSize failed: $minBuf")
            }
            return false
        }
        val record = createAudioRecord(minBuf) ?: return false
        audioRecord = record
        return true
    }

    private fun createAudioRecord(minBuf: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val record = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(tag, "AudioRecord initialized with source=$source")
                return record
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
        }
        if (shouldWarnNow(lastRecorderWarnMs)) {
            lastRecorderWarnMs = SystemClock.elapsedRealtime()
            Log.w(tag, "AudioRecord failed to initialize for all sources")
        }
        return null
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun amplitudeToPercent(amplitude: Int): Int {
        if (amplitude <= 0) return 0
        val db = 20 * log10(amplitude / 32768.0)
        val normalized = ((db + 60) / 60.0 * 100).toInt()
        return normalized.coerceIn(0, 100)
    }

    private fun loudDurationMs(nowMs: Long): Long {
        return if (loudSinceMs > 0L) nowMs - loudSinceMs else 0L
    }

    private fun maybeLogHeartbeat(nowMs: Long, detail: String) {
        if (nowMs - lastHeartbeatLogMs < HEARTBEAT_LOG_INTERVAL_MS) return
        lastHeartbeatLogMs = nowMs
        val record = audioRecord
        Log.d(
            tag,
            "heartbeat tick=$tickCount $detail recordState=${record?.state} " +
                "recordingState=${record?.recordingState}"
        )
    }

    private fun shouldWarnNow(lastWarnMs: Long): Boolean {
        return SystemClock.elapsedRealtime() - lastWarnMs >= WARN_THROTTLE_MS
    }

    private fun isLikelyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        return fingerprint.contains("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("sdk_gphone") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SAMPLE_SIZE = 512
        private const val SAMPLE_INTERVAL_MS = 400L
        private const val SUSTAINED_LOUD_MS = 3_000L
        private const val STARTUP_GRACE_MS = 8_000L
        private const val QUIET_RESET_MS = 900L
        private const val HEARTBEAT_LOG_INTERVAL_MS = 3_000L
        private const val WARN_THROTTLE_MS = 15_000L
        private const val MAX_READS_PER_TICK = 4
        /** Emulator/host mic often mutes to all-zero PCM when input clips. */
        private const val MIN_SAMPLES_FOR_OVERLOAD_CHECK = 256
        private const val CONSECUTIVE_ZERO_PEAK_TICKS = 2
        private const val STRONG_PEAK_RAW = 200
        private const val STRONG_LEVEL_PERCENT = 8
        private const val STRONG_SIGNAL_WINDOW_MS = 4_000L
        private const val OVERLOAD_SYNTHETIC_LEVEL_PERCENT = 60
        const val DEFAULT_THRESHOLD = 75
    }
}
