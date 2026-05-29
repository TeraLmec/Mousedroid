package com.darusc.mousedroid.viewmodels

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.darusc.mousedroid.getDeviceDetails
import com.darusc.mousedroid.networking.Connection
import com.darusc.mousedroid.networking.ConnectionManager
import com.darusc.mousedroid.networking.DiscoveredServer
import com.darusc.mousedroid.networking.ServerDiscovery
import kotlinx.coroutines.launch

/**
 * @param devices The list of bluetooth devices
 * @param sharedPreferences Shared preferences for saved WIFI devices
 */
class DeviceListViewModel(
    private val mode: Connection.Mode,
    private val devices: List<Pair<String, String>>?,
    private val sharedPreferences: SharedPreferences?
): BaseViewModel<DeviceListViewModel.State, DeviceListViewModel.Event>(State(emptyList())) {

    sealed class Event: BaseViewModel.Event()
    data class State(val devices: List<Pair<String, String>>): BaseViewModel.State()

    private val connectionManager = ConnectionManager.getInstance()
    private val discoveredServers = linkedMapOf<String, DiscoveredServer>()

    class Factory: ViewModelProvider.Factory {

        private val devices: List<Pair<String, String>>?
        private val sharedPreferences: SharedPreferences?

        /**
         * Create the viewmodel corresponding for bluetooth mode.
         * @param devices The list of paired bluetooth devices
         */
        @SuppressLint("MissingPermission")
        constructor(devices: Set<BluetoothDevice>) {
            this.devices = devices.map {
                Pair(it.name?: "Unknown", it.address)
            }
            this.sharedPreferences = null
        }

        /**
         * Create the viewmodel corresponding for wifi mode
         * @param sharedPreferences The shared preferences containing the stored WIFI devices
         */
        constructor(sharedPreferences: SharedPreferences?) {
            this.devices = null
            this.sharedPreferences = sharedPreferences
        }

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if(modelClass.isAssignableFrom(DeviceListViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return if(this.devices != null) {
                    DeviceListViewModel(Connection.Mode.BLUETOOTH, devices, null) as T
                } else {
                    DeviceListViewModel(Connection.Mode.WIFI, null, sharedPreferences) as T
                }
            }
            throw IllegalArgumentException("Unknown viewmodel class")
        }
    }

    init {
        updateState()
    }

    fun add(name: String, address: String) {
        sharedPreferences?.edit { putString(name, address) }
        updateState()
    }

    fun add(server: DiscoveredServer) {
        add(server.name, server.address)
    }

    fun remove(name: String) {
        sharedPreferences?.edit { remove(name) }
        updateState()
    }

    fun refreshDiscovery() {
        if (mode != Connection.Mode.WIFI) return

        viewModelScope.launch {
            ServerDiscovery.discover().forEach {
                discoveredServers["${it.address}:${it.port}"] = it
            }
            updateState()
        }
    }

    fun addPairingPayload(payload: String): Boolean {
        val server = ServerDiscovery.parsePairingPayload(payload) ?: return false
        add(server)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun onDeviceClick(context: Context, name: String, address: String) {
        if(mode == Connection.Mode.WIFI) {
            val details = getDeviceDetails(context, Connection.Mode.WIFI)
            val port = discoveredServers.values.firstOrNull { it.address == address }?.port ?: ServerDiscovery.DEFAULT_PORT
            connectionManager.connectWIFI(address, port, details)
        } else {
            connectionManager.connectBluetooth(address)
        }
    }

    private fun updateState() {
        if(mode == Connection.Mode.BLUETOOTH) {
            setState(State(devices!!))
        } else {
            val devices = mutableListOf<Pair<String, String>>()
            discoveredServers.values.forEach { devices.add(Pair(it.name, it.address)) }
            sharedPreferences!!.all.let {
                for((name, address) in it) {
                    val pair = Pair(name, address as String)
                    if (devices.none { device -> device.second == pair.second }) {
                        devices.add(pair)
                    }
                }
            }
            setState(State(devices))
        }
    }
}
