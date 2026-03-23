package com.airpods.controller

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AirPodsApp : Application() {

    companion object {
        const val CHANNEL_ID_SERVICE   = "airpods_service"
        const val CHANNEL_ID_ALERTS    = "airpods_alerts"
        const val CHANNEL_ID_FINDMY    = "airpods_findmy"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_SERVICE,
                "AirPods Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Persistent BLE monitoring service" })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_ALERTS,
                "AirPods Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Ear detection and battery alerts" })

            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID_FINDMY,
                "Find My Buds",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Find My Buds sound alerts" })
        }
    }
}
