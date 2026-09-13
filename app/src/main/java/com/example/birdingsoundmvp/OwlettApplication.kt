package com.example.birdingsoundmvp

import android.app.Application
import com.example.birdingsoundmvp.transfer.LocalDataTransfer

class OwlettApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Recover before any Activity, service, or ViewModel opens user data.
        if (java.io.File(noBackupFilesDir, "transfer-importing").exists()) {
            LocalDataTransfer(this).let { transfer -> try { transfer.recover() } finally { transfer.close() } }
        }
    }
}
