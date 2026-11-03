package io.github.purplepalm.birdair.phone

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ScooterRepo.init(this)
        Notifications.channel(this)
    }
}
