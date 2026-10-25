package dev.owen.ubuntu_remote

import android.Manifest
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.*
import android.os.*
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.math.*

/** Standard Bluetooth HID peripheral: no Ubuntu companion daemon is needed. */
class MainActivity : FlutterActivity(), SensorEventListener {
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var opening = false
    private var registering = false
    private var status = "Enable Bluetooth, then start the remote"
    private val handler = Handler(Looper.getMainLooper())
    private val adapter get() = getSystemService(BluetoothManager::class.java).adapter
    private val sensors get() = getSystemService(SensorManager::class.java)
    private var pointing = false
    private var lastTime = 0L
    private var residualX = 0.0
    private var residualY = 0.0
    private var generation = 0
    private var pendingKeys = 0
    private var buttons = 0
    private var sensitivity = 900.0
    private val motion = MotionPointer()
    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(device: BluetoothDevice?, active: Boolean) {
            registered = active; registering = false
            if (active) status = "Ready — pair Ubuntu, then connect below"
            else { host = null; status = "Remote stopped — tap Start"; generation++; pendingKeys=0 }
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device; status = "Connected to ${device.name ?: device.address}"
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                host = null; buttons = 0; generation++; pendingKeys=0; stopPointing()
                status = "Disconnected — select Ubuntu to reconnect"
            } else status = "Connecting…"
        }
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, size: Int) {
            if (id.toInt() == 1 || id.toInt() == 2)
                hid?.replyReport(device, type, id, ByteArray(if(id.toInt()==1) 8 else 4))
            else hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // Keyboard LED output reports need no local action.
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            opening = false; hid = proxy as BluetoothHidDevice
            registerHid()
        }
        override fun onServiceDisconnected(profile: Int) {
            opening = false; registering=false; hid = null; registered = false; host = null
            status = "Bluetooth service unavailable — tap Start"
        }
    }
    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MethodChannel(engine.dartExecutor.binaryMessenger, "dev.owen.remote/hid").setMethodCallHandler { call, result ->
            try {
                when(call.method) {
                    "state" -> result.success(mapOf("status" to status, "registered" to registered,
                        "connected" to (host != null), "gyro" to (sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!=null),
                        "devices" to if(hasPermission()) adapter?.bondedDevices?.map { mapOf("name" to (it.name ?: "Bluetooth host"), "address" to it.address) } else emptyList<Any>()))
                    "start" -> { startRemote(); result.success(null) }
                    "discoverable" -> { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120)); result.success(null) }
                    "connect" -> {
                        check(registered) { "Tap Start and wait for Ready before connecting" }
                        val device=adapter.getRemoteDevice(call.argument<String>("address"))
                        check(hid?.connect(device)==true) { "Connection request rejected; pair Ubuntu first" }
                        status="Connecting to ${device.name}…"
                        handler.postDelayed({ if(host==null) status="Not connected — if paired before starting the remote, remove the pairing on Ubuntu and pair again while Ready" },12000)
                        result.success(null)
                    }
                    "disconnect" -> { releaseInputs(); host?.let { hid?.disconnect(it) }; result.success(null) }
                    "mouse" -> { mouse((call.argument<Number>("dx")?:0).toInt(),(call.argument<Number>("dy")?:0).toInt(),(call.argument<Number>("wheel")?:0).toInt(),call.argument<Int>("buttons")?:buttons); result.success(null) }
                    "key" -> { queueKeys(listOf(Pair(call.argument<Int>("usage")?:0,call.argument<Int>("modifier")?:0))); result.success(null) }
                    "text" -> {
                        val text=call.argument<String>("text")?:""
                        require(text.length<=500) { "Send at most 500 characters at once" }
                        val keys=text.map { keyFor(it) ?: throw IllegalArgumentException("Basic typing supports US-layout ASCII only; unsupported: $it") }
                        queueKeys(keys); result.success(null)
                    }
                    "point" -> {
                        if(call.argument<Boolean>("enabled")==true) {
                            check(host!=null) { "Connect Ubuntu first" }
                            val gyro=sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: error("No gyroscope on this phone")
                            sensitivity=(call.argument<Number>("sensitivity")?:900).toDouble()
                            lastTime=0; residualX=0.0; residualY=0.0; pointing=true
                            motion.reset()
                            val gravitySensor=sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
                                ?: error("This phone needs a gravity sensor for motion aiming")
                            sensors.registerListener(this,gravitySensor,SensorManager.SENSOR_DELAY_GAME)
                            sensors.registerListener(this,gyro,SensorManager.SENSOR_DELAY_GAME)
                        } else stopPointing()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            } catch(e: Exception) { result.error("HID",e.message,null) }
        }
    }
    override fun onResume() {
        super.onResume()
        handler.postDelayed({ if(hasPermission() && hid!=null && !registered && !registering) try { registerHid() } catch(e:Exception) { status=e.message?:"Registration failed" } },500)
    }
    override fun onRequestPermissionsResult(requestCode:Int, permissions:Array<out String>, grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==1 && grantResults.isNotEmpty() && grantResults.all { it==PackageManager.PERMISSION_GRANTED }) {
            try { startRemote() } catch(e:Exception) { status=e.message?:"Bluetooth start failed" }
        }
    }
    private fun hasPermission() = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    private fun startRemote() {
        if(Build.VERSION.SDK_INT>=31 && (!hasPermission() || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE),1)
            status="Grant Nearby devices permission, then tap Start"; return
        }
        check(adapter!=null) { "Bluetooth hardware unavailable" }
        if(!adapter.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        if(registered) return
        if(hid!=null) registerHid()
        else if(!opening) { opening=adapter.getProfileProxy(this,listener,BluetoothProfile.HID_DEVICE); check(opening) { "Phone does not expose Bluetooth HID Device" }; status="Starting Bluetooth HID…" }
    }
    private fun registerHid() {
        if (registered || registering) return
        val descriptor=intArrayOf(
            0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x01,
            0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,
            0x95,0x01,0x75,0x08,0x81,0x01,
            0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,0x91,0x02,
            0x95,0x01,0x75,0x03,0x91,0x01,
            0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81,0x00,0xC0,
            0x05,0x01,0x09,0x02,0xA1,0x01,0x85,0x02,0x09,0x01,0xA1,0x00,
            0x05,0x09,0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,0x95,0x03,0x75,0x01,0x81,0x02,
            0x95,0x01,0x75,0x05,0x81,0x01,
            0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x81,0x25,0x7F,0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0
        ).map{it.toByte()}.toByteArray()
        val sdp=BluetoothHidDeviceAppSdpSettings("Ubuntu Remote","Mouse, keyboard and motion presenter","Owen",BluetoothHidDevice.SUBCLASS1_COMBO,descriptor)
        check(hid?.registerApp(sdp,null,null,mainExecutor,callback)==true) { "HID registration rejected; close other Bluetooth keyboard apps" }
        registering=true; status="Registering Bluetooth keyboard and mouse…"
    }
    private fun mouse(dx:Int,dy:Int,wheel:Int,b:Int) {
        val device=host ?: error("Connect Ubuntu first")
        buttons=b and 7
        var x=dx; var y=dy; var w=wheel
        do {
            val sx=x.coerceIn(-127,127); val sy=y.coerceIn(-127,127); val sw=w.coerceIn(-127,127)
            check(hid?.sendReport(device,2,byteArrayOf(buttons.toByte(),sx.toByte(),sy.toByte(),sw.toByte()))==true) { "Mouse report rejected" }
            x-=sx; y-=sy; w-=sw
        } while(x!=0 || y!=0 || w!=0)
    }
    private fun queueKeys(keys:List<Pair<Int,Int>>) {
        val device=host ?: error("Connect Ubuntu first")
        require(pendingKeys+keys.size<=600) { "Typing queue full; wait before sending more" }
        val current=generation
        keys.forEach { (key,modifier) ->
            val delay=pendingKeys++*36L
            handler.postDelayed({ if(current==generation && host==device) hid?.sendReport(device,1,byteArrayOf(modifier.toByte(),0,key.toByte(),0,0,0,0,0)) },delay)
            handler.postDelayed({ if(current==generation) { hid?.sendReport(device,1,ByteArray(8)); pendingKeys-- } },delay+18)
        }
    }
    private fun keyFor(c:Char): Pair<Int,Int>? {
        if(c in 'a'..'z') return Pair(c-'a'+4,0)
        if(c in 'A'..'Z') return Pair(c-'A'+4,2)
        if(c in '1'..'9') return Pair(c-'1'+30,0)
        if(c=='0') return Pair(39,0)
        val normal="\n\u001b\b\t -=[]\\;'`,./"
        val codes=intArrayOf(40,41,42,43,44,45,46,47,48,49,51,52,53,54,55,56)
        val i=normal.indexOf(c); if(i>=0) return Pair(codes[i],0)
        val shifted="!@#$%^&*()_+{}|~:\"<>?"
        val shiftedCodes=intArrayOf(30,31,32,33,34,35,36,37,38,39,45,46,47,48,49,53,51,52,54,55,56)
        val j=shifted.indexOf(c); return if(j>=0) Pair(shiftedCodes[j],2) else null
    }
    private fun stopPointing() { pointing=false; lastTime=0; sensors.unregisterListener(this) }
    override fun onSensorChanged(event:SensorEvent) {
        if(!pointing || host==null) return
        if(event.sensor.type==Sensor.TYPE_GRAVITY) { motion.setGravity(event.values); return }
        if(event.sensor.type!=Sensor.TYPE_GYROSCOPE) return
        val (x,y)=motion.step(event.values,event.timestamp,sensitivity)
        if(x!=0 || y!=0) try { mouse(x,y,0,buttons) } catch(_:Exception) { stopPointing() }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
    private fun releaseInputs() { generation++; pendingKeys=0; stopPointing(); host?.let { hid?.sendReport(it,1,ByteArray(8)); hid?.sendReport(it,2,ByteArray(4)) }; buttons=0 }
    override fun onPause() { releaseInputs(); super.onPause() }
    override fun onDestroy() { releaseInputs(); hid?.unregisterApp(); hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE,it) }; super.onDestroy() }
}
