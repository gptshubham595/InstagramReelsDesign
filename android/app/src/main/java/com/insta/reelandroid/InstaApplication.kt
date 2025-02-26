package com.insta.reelandroid

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class InstaApplication: Application() {
    override fun onCreate() {
        super.onCreate()
    }
}