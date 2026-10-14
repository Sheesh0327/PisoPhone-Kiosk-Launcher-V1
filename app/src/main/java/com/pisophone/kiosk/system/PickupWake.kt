package com.pisophone.kiosk.system

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Wakes the screen when the phone is picked up, so the power button does not wear out. Armed while the screen is off:
 *  1. the phone's own pick-up sensor, when it has one (a low-power hardware sensor);
 *  2. else the "significant motion" sensor;
 *  3. else the accelerometer, watched for a lift: a few quick changes of the force on the phone.
 * [onPickup] is called once per arming (the screen turning off arms it again). Call from the main thread.
 */
class PickupWake(context: Context, private val onPickup: () -> Unit) {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var armed = false
    private var trigger: Sensor? = null
    private var accelerometer: Sensor? = null
    private var jolts = 0
    private var lastMagnitude = SensorManager.GRAVITY_EARTH

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            fire("pick-up or motion sensor")
        }
    }

    private val accelerometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
            // the force on a phone lying still is gravity (9.8); lifting it changes that by a few m/s2, quickly and more than once
            if (abs(magnitude - lastMagnitude) > LIFT_DELTA) {
                jolts++
            } else if (jolts > 0) {
                jolts--
            }
            lastMagnitude = magnitude
            if (jolts >= LIFT_JOLTS) fire("accelerometer")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun arm() {
        val sm = sensorManager ?: return
        if (armed) return
        armed = true
        jolts = 0
        lastMagnitude = SensorManager.GRAVITY_EARTH
        try {
            trigger = sm.getDefaultSensor(TYPE_PICK_UP_GESTURE, true) ?: sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION, true)
            val t = trigger
            if (t != null && sm.requestTriggerSensor(triggerListener, t)) return
            trigger = null
            accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let { sm.registerListener(accelerometerListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not arm the pick-up wake: ${e.message}")
            armed = false
        }
    }

    fun disarm() {
        val sm = sensorManager ?: return
        if (!armed) return
        armed = false
        try {
            trigger?.let { sm.cancelTriggerSensor(triggerListener, it) }
            sm.unregisterListener(accelerometerListener)
        } catch (e: Exception) {
            Log.w(TAG, "Could not disarm the pick-up wake: ${e.message}")
        }
        trigger = null
        accelerometer = null
    }

    private fun fire(source: String) {
        if (!armed) return
        Log.i(TAG, "Picked up ($source): waking the screen")
        disarm()
        onPickup()
    }

    private companion object {
        const val TAG = "PickupWake"

        /** Sensor.TYPE_PICK_UP_GESTURE: the number is public Android, only the constant is hidden from apps. */
        const val TYPE_PICK_UP_GESTURE = 25
        const val LIFT_DELTA = 2.5f
        const val LIFT_JOLTS = 4
    }
}
