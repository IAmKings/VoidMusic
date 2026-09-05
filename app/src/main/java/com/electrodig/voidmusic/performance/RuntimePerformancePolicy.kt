package com.electrodig.voidmusic.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.electrodig.voidmusic.persistence.PerformanceLevel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Runtime-only limits; these never overwrite the user's persisted preference. */
data class RuntimeConstraints(
    val powerSaveMode: Boolean = false,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
) {
    fun effectiveLevel(preferred: PerformanceLevel): PerformanceLevel =
        if (powerSaveMode || thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            PerformanceLevel.LOW
        } else {
            preferred
        }
}

/**
 * Observes power-save and thermal state with symmetric listener registration.
 * Removing a constraint immediately restores the user's preferred tier.
 */
class RuntimePerformancePolicy(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val closed = AtomicBoolean(false)
    private val mutableConstraints = MutableStateFlow(readCurrentConstraints())
    val constraints: StateFlow<RuntimeConstraints> = mutableConstraints.asStateFlow()

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) refreshPowerSave()
        }
    }

    private val thermalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status ->
            mutableConstraints.value = mutableConstraints.value.copy(thermalStatus = status)
        }
    } else {
        null
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.addThermalStatusListener(
                ContextCompat.getMainExecutor(appContext),
                thermalListener
            )
        }
    }

    private fun refreshPowerSave() {
        mutableConstraints.value = mutableConstraints.value.copy(
            powerSaveMode = powerManager?.isPowerSaveMode == true
        )
    }

    private fun readCurrentConstraints(): RuntimeConstraints = RuntimeConstraints(
        powerSaveMode = powerManager?.isPowerSaveMode == true,
        thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { appContext.unregisterReceiver(powerSaveReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.removeThermalStatusListener(thermalListener)
        }
    }
}
