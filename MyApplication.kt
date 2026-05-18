package com.example.myapplication

import android.app.Application
import android.util.Log
import com.example.myapplication.utils.ManifestIO

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("MyApplication", "onCreate - initializing ManifestIO")
        ManifestIO.init(applicationContext)
    }
}