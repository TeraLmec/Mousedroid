package com.darusc.mousedroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.darusc.mousedroid.mkinput.InputEvent
import com.darusc.mousedroid.networking.ConnectionManager
import com.darusc.mousedroid.networking.bluetooth.BluetoothAdapterWrapper

class MainActivity : AppCompatActivity() {

    private val TAG = "Mousedroid"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1000)
            }
        }

        BluetoothAdapterWrapper.initialize(applicationContext)
        //BatteryMonitor.getInstance().start(applicationContext)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode == 1000) {
            if(grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this)
                    .setTitle("Bluetooth Permission Required")
                    .setMessage("Please enable bluetooth permission in settings and restart the app.")
                    .setPositiveButton("Go to settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                BluetoothAdapterWrapper.initialize(applicationContext)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val connectionManager = ConnectionManager.getInstance()
        if (
            AppSettings.volumeButtonsControlPc(this) &&
            connectionManager.isConnected() &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val action = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    InputEvent.MediaAction.VOLUME_UP
                } else {
                    InputEvent.MediaAction.VOLUME_DOWN
                }
                connectionManager.send(InputEvent.MediaEvent(action))
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

}
