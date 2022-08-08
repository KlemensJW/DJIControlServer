package com.rrc.djiControlServer

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity

import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.*
import android.net.wifi.WifiManager
import android.os.*
import android.text.format.Formatter
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.flightcontroller.virtualstick.*
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import io.ktor.application.*
import io.ktor.gson.*
import io.ktor.routing.*
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min
import kotlin.math.pow

data class CommandCompleted(val completed: Boolean, val errorDescription: String?)

data class DroneState<T>(val state:T)

data class IMUState(val velX: Float, val velY: Float, val velZ: Float)

class DJIControlException(message:String): Exception(message)

enum class VelocityProfile {
    CONSTANT, TRAPEZOIDAL, S_CURVE
}

enum class Direction {
    FORWARD, BACKWARD, LEFT, RIGHT, UP, DOWN, CLOCKWISE, COUNTER_CLOCKWISE
}

class MainActivity : AppCompatActivity(), DJISDKManager.SDKManagerCallback {

    companion object {
        private const val PORT: Int = 8080
        private const val TAG = "MainActivity"
        private const val FLAG_CONNECTION_CHANGE = "dji_sdk_connection_change"
        private lateinit var mHandler: Handler
        private val REQUIRED_PERMISSION_LIST: Array<String> = arrayOf(
            Manifest.permission.VIBRATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_PHONE_STATE,
        )
    }
    private lateinit var sdkManager: DJISDKManager

    private lateinit var ipText: TextView
    private lateinit var regText: TextView

    private lateinit var droneNameText: TextView
    private lateinit var batteryText: TextView

    private var drone: Aircraft? = null

    private var maxSpeed = 1.0f // 1m/s
    private var maxAngularSpeed = 30.0f // 30 deg/s
    private var maxAcceleration = 0.8f // 0.8 m/s^2
    private var maxAngularAcceleration = 15.0f // 15 deg/s^2
    private var maxJerk = 1.0f // 1.0 m/s^3
    private var maxAngularJerk = 30.0f // 30 deg/s^3
    private var flightCommandInterval = 40L // 40 ms = 25Hz

    private var imuStates = mutableListOf<IMUState?>()
    private var imuStatePostRunnable: Runnable? = null
    private var readIMUState = false

    private var velocityProfile: VelocityProfile = VelocityProfile.TRAPEZOIDAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipText = findViewById(R.id.ipText)
        regText = findViewById(R.id.regText)
        droneNameText = findViewById(R.id.droneNameText)
        batteryText = findViewById(R.id.batteryText)

        displayIP()
        startServer()

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSION_LIST, 1)

        mHandler = Handler(Looper.getMainLooper())
        sdkManager = DJISDKManager.getInstance()
        sdkManager.setCallbackRunInUIThread(true)

        regText.text = getString(R.string.registering)

        sdkManager.registerApp(this, this)
    }

    // Embedded Server Functions
    private fun displayIP() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipString = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        ipText.text = getString(R.string.ip_format, ipString, PORT)
    }

    private fun startServer() {
        embeddedServer(Netty, PORT) {
            install(WebSockets)
            install(CallLogging)
            install(ContentNegotiation) {
                gson {  }
            }

            routing {
                meta()
                getState()
                setState()
                takeoffAndLandControl()
                throttleControl()
                yawControl()
                rollPitchControl()
            }
        }.start(wait = false)
    }

    // Server Routes
    private fun Route.meta() {
        get("/") {
            call.respondText ( text="Connected", contentType = ContentType.Text.Plain )
        }

        get("/startCollectingIMUState/{interval}") {
            if(drone != null) {

                try {
                    val imuReadInterval = if(call.parameters["interval"] != null)
                        call.parameters["interval"]!!.toLong()
                    else
                        1000L // Default once a second

                    val handler  = Handler(Looper.getMainLooper())

                    readIMUState = true

                    imuStatePostRunnable = object: Runnable {
                        override fun run() {
                            val currIMUState = drone?.flightController?.state
                            val currVelocities = if(currIMUState != null)
                                IMUState(currIMUState.velocityX, currIMUState.velocityY, currIMUState.velocityZ)
                            else
                                null
                            imuStates.add(currVelocities)
                            if(readIMUState)
                                handler.postDelayed(this, imuReadInterval)
                        }
                    }

                    handler.postDelayed(imuStatePostRunnable as Runnable, imuReadInterval)

                    call.respond(CommandCompleted(true, null))
                }
                catch(e:NumberFormatException) {
                    call.respond(CommandCompleted(false, "Invalid value for interval. Must be a positive integer."))
                }
                catch(e: Exception) {
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

        get("/stopCollectingIMUState") {
            if(drone != null) {
                try {
                    readIMUState = false
                    val handler = Handler(Looper.getMainLooper())
                    handler.removeCallbacks(imuStatePostRunnable as Runnable)
                    call.respond(CommandCompleted(true, null))
                }
                catch(e: Exception) {
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

        get("/getCollectedIMUStates") {
            try {
                call.respond(DroneState(imuStates))
            }
            catch(e: Exception) {
                call.respond(CommandCompleted(false, e.message))
            }
        }

        get("/clearCollectedIMUStates") {
            try {
                imuStates.clear()
                call.respond(CommandCompleted(true, null))
            }
            catch(e: Exception) {
                call.respond(CommandCompleted(false, e.message))
            }
        }
    }

    private fun Route.getState() {

        get("/isLandingProtectionEnabled") {
            if(drone != null) {
                val isLandingProtectionEnabled = suspendCoroutine { cont ->
                    drone?.flightController?.flightAssistant?.getLandingProtectionEnabled(object: CommonCallbacks.CompletionCallbackWith<Boolean> {
                        override fun onSuccess(p0: Boolean?) {
                            cont.resume(p0)
                        }

                        override fun onFailure(p0: DJIError?) {
                            cont.resume(null)
                        }

                    })
                }
                if(isLandingProtectionEnabled != null)
                    call.respond(DroneState<Boolean?>(isLandingProtectionEnabled))
                else
                    call.respond(CommandCompleted(false, "Unable to fetch state"))
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))

        }

        get("/isVirtualStickControlEnabled") {
            if(drone != null)
                call.respond(DroneState(drone?.flightController?.isVirtualStickControlModeAvailable))
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))

        }

        get("/getMaxSpeed") {
            call.respond(DroneState(maxSpeed))
        }

        get("/getVelocityProfile") {
            val profileName = when(velocityProfile) {
                VelocityProfile.CONSTANT -> "CONSTANT"
                VelocityProfile.TRAPEZOIDAL -> "TRAPEZOIDAL"
                VelocityProfile.S_CURVE -> "S_CURVE"
            }
            call.respond(DroneState(profileName))
        }

        get("/getHeading") {
            if(drone != null) {
                try{
                    call.respond(DroneState(drone?.flightController?.compass!!.heading))
                }
                catch(e: AssertionError) {
                    call.respond(CommandCompleted(false, "Cannot find compass component. Unable to fetch heading"))
                }
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))
        }

    }

    private fun Route.setState() {

        get("/enableLandingProtection") {
            if(drone != null) {
                val stateChangeError = suspendCoroutine<DJIError?> { cont ->
                    drone?.flightController?.flightAssistant?.setLandingProtectionEnabled(true) { error ->
                        cont.resume(error)
                    }
                }

                call.respond(CommandCompleted(stateChangeError == null, stateChangeError?.description))
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))

        }

        get("/disableLandingProtection") {
            if(drone != null) {
                val stateChangeError = suspendCoroutine<DJIError?> { cont ->
                    drone?.flightController?.flightAssistant?.setLandingProtectionEnabled(false) { error ->
                        cont.resume(error)
                    }
                }

                call.respond(CommandCompleted(stateChangeError == null, stateChangeError?.description))
            }
            else
                call.respond(CommandCompleted(false, "Drone Not Available"))

        }

        get("/setMaxSpeed/{speed}") {
            if(call.parameters["speed"] != null) {
                try {
                    val speed = call.parameters["speed"]!!.toFloat()

                    if(speed <= 0)
                        throw NumberFormatException("Speed must be a positive float")

                    maxSpeed = speed

                    call.respond(CommandCompleted(true, null))
                }
                catch(e: Exception){
                    call.respond(CommandCompleted(false, e.message))
                }
            }
            else
                call.respond(CommandCompleted(false, "No speed provided"))
        }

        get("/setVelocityProfile/{profile}") {
            if(call.parameters["profile"] != null) {
                val profile = call.parameters["profile"].toString().uppercase()
                var validProfile = true
                when(profile) {
                    "CONSTANT" -> velocityProfile = VelocityProfile.CONSTANT
                    "TRAPEZOIDAL" -> velocityProfile = VelocityProfile.TRAPEZOIDAL
                    "S_CURVE" -> velocityProfile = VelocityProfile.S_CURVE
                    else -> validProfile = false
                }

                if(validProfile)
                    call.respond(CommandCompleted(true, null))
                else
                    call.respond(CommandCompleted(false, "Profile must be either 'CONSTANT', 'TRAPEZOIDAL' or 'S_CURVE'"))
            }
            else
                call.respond(CommandCompleted(false, "No profile provided"))
        }
    }

    private fun Route.takeoffAndLandControl() {

        get("/takeoff") {
                if(drone != null) {
                    val takeoffError = suspendCoroutine<DJIError?> { cont ->
                        drone?.flightController?.startTakeoff { takeoffError ->
                            cont.resume(takeoffError)
                        }
                    }
                    call.respond(CommandCompleted(takeoffError == null, takeoffError?.description))
                }
            else {
                call.respond(CommandCompleted(false, "Drone Not Available"))
            }
        }

        get("/land") {
            if(drone != null){
                val descentError = suspendCoroutine<DJIError?> { cont ->
                    drone?.flightController?.startLanding { descendError ->
                        cont.resume(descendError)
                    }
                }
                call.respond(CommandCompleted(descentError == null, descentError?.description))
            }
            else {
                call.respond(CommandCompleted(false, "Drone Not Available"))
            }
        }

        get("/confirmLanding") {
            if (drone != null) {
                val landingError = suspendCoroutine<DJIError?> { cont ->
                    drone?.flightController?.confirmLanding { landingError ->
                        cont.resume(landingError)
                    }
                }
                call.respond(CommandCompleted(landingError == null, landingError?.description))
            } else {
                call.respond(CommandCompleted(false, "Drone not Available"))
            }
        }

    }

    private fun Route.throttleControl() {

        get("/moveUp/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.UP)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))

        }

        get("/moveDown/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.DOWN)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))

        }

    }

    private fun Route.yawControl() {
        get("/rotateClockwise/{angle}") {
            if(drone != null) {
                if(call.parameters["angle"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        val angle = getNumVal(call.parameters["angle"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(angle, Direction.CLOCKWISE)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }

        get("/rotateCounterClockwise/{angle}") {
            if(drone != null) {
                if(call.parameters["angle"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        val angle = getNumVal(call.parameters["angle"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(angle, Direction.COUNTER_CLOCKWISE)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }
    }

    private fun Route.rollPitchControl() {
        get("/moveForward/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                       createMotionPlanAndExecute(dist, Direction.FORWARD)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }

        get("/moveBackward/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.BACKWARD)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }

        get("/moveRight/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.RIGHT)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }

        get("/moveLeft/{dist}") {
            if(drone != null) {
                if(call.parameters["dist"] != null) {
                    val currentControlModes = getCurrentControlModes()
                    try {
                        val dist = getNumVal(call.parameters["dist"].toString())
                        setVirtualSticks(true)
                        setCurrentControlModes(Triple(VerticalControlMode.VELOCITY, YawControlMode.ANGULAR_VELOCITY, RollPitchControlMode.VELOCITY))

                        createMotionPlanAndExecute(dist, Direction.LEFT)

                        setVirtualSticks(false)
                        call.respond(CommandCompleted(true, null))
                    }
                    catch (e: NumberFormatException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    catch(e: DJIControlException) {
                        call.respond(CommandCompleted(false, e.message))
                    }
                    finally {
                        setCurrentControlModes(currentControlModes)
                    }

                }
                else
                    call.respond(CommandCompleted(false, "Missing distance value"))
            }
            else
                call.respond(CommandCompleted(false, "Drone not Available"))
        }
    }

    // Motion planning functions
    private suspend fun createMotionPlanAndExecute(dist: Float, direction: Direction){
        val commands = when (velocityProfile) {
            VelocityProfile.CONSTANT -> generateConstantVelocityProfile(dist, direction)
            VelocityProfile.TRAPEZOIDAL -> generateTrapezoidalVelocityProfile(dist, direction)
            VelocityProfile.S_CURVE -> generateSCurveVelocityProfile(dist, direction)
        }
        for(command in commands) {
            drone?.flightController?.sendVirtualStickFlightControlData(command, null)
            delay(flightCommandInterval)
        }
    }

    private fun generateVelocitiesFromFunction(v: (Float)->Float, tTotal: Float, direction: Direction): MutableList<FlightControlData> {
        val tSteps = arange(0f, tTotal, flightCommandInterval/1000f)

        val velocities = mutableListOf<FlightControlData>()

        for(t in tSteps) {
            val command = when(direction) {
                Direction.FORWARD -> FlightControlData(0f, v(t), 0f, 0f)
                Direction.BACKWARD -> FlightControlData(0f, -v(t), 0f, 0f)
                Direction.RIGHT -> FlightControlData(v(t), 0f, 0f, 0f)
                Direction.LEFT -> FlightControlData(-v(t), 0f, 0f, 0f)
                Direction.UP -> FlightControlData(0f, 0f, 0f, v(t))
                Direction.DOWN -> FlightControlData(0f, 0f, 0f, -v(t))
                Direction.CLOCKWISE -> FlightControlData(0f, 0f, v(t), 0f)
                Direction.COUNTER_CLOCKWISE -> FlightControlData(0f, 0f, -v(t), 0f)
            }
            velocities.add(command)
        }
        velocities.add(FlightControlData(0f, 0f, 0f, 0f))
        return velocities
    }

    private fun generateConstantVelocityProfile(D: Float, direction: Direction) : MutableList<FlightControlData>{
        val v = if (direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularSpeed else maxSpeed
        val tTotal = D/v

        val vFn = fun(_:Float): Float {
            return v
        }

        return generateVelocitiesFromFunction(vFn, tTotal, direction)
    }

    private fun generateTrapezoidalVelocityProfile(D: Float, direction: Direction): MutableList<FlightControlData> {

        // Constraints
        val a = if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularAcceleration else maxAcceleration
        val vMax = min(D/2, if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularSpeed else maxSpeed )

        // Time taken at each phase
        val tp1 = vMax/a
        val tp2 = (a*D - (vMax.pow(2)))/(a*vMax)
        val tp3 = vMax/a

        // Total time taken
        val tTotal = tp1 + tp2 + tp3

        val vFn = fun(t:Float):Float {
            return if(t <= tp1)
                a*t
            else if(t <= tp1 + tp2)
                vMax
            else
                vMax - a*(t - tp1 - tp2)
        }

        return generateVelocitiesFromFunction(vFn, tTotal, direction)
    }

    private fun generateSCurveVelocityProfile(D:Float, direction: Direction): MutableList<FlightControlData> {

        // Constraints
        val j = if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularJerk else maxJerk
        val vMax = min(D/2, if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularSpeed else maxSpeed )
        val aMax = min(0.75f*vMax, if(direction == Direction.CLOCKWISE || direction == Direction.COUNTER_CLOCKWISE) maxAngularAcceleration else maxAcceleration )

        // Final velocity for each phase
        val vf1 = aMax.pow(2)/(2*j)
        val vf2 = vMax - aMax.pow(2)/(2*j)
        val vf3 = vMax
        val vf4 = vMax
        val vf5 = vMax - aMax.pow(2)/(2*j)
        val vf6 = aMax.pow(2)/(2*j)

        // Time taken for each phase
        val tp1 = aMax/j
        val tp2 = (vf2 - vf1)/aMax
        val tp3 = aMax/j
        val tp4 = ((aMax * j * D) - (vMax * aMax.pow(2)) - (j*vMax.pow(2)))/(j*aMax*vMax)
        val tp5 = aMax/j
        val tp6 = (vf5- vf6)/aMax
        val tp7 = aMax/j

        // Total time taken
        val tTotal = tp1 + tp2 + tp3 + tp4 + tp5 + tp6 + tp7

        val vFn = fun (t: Float): Float {
            if(t <= tp1)
            {
                return j * t.pow(2) / 2
            }
            else if(t <= tp1 + tp2){
                val tCurr = t - tp1
                return vf1 + (aMax * tCurr)
            }
            else if(t <= tp1 + tp2 + tp3) {
                val tCurr = t - tp1 - tp2
                return vf2 + aMax*tCurr - (j*tCurr.pow(2))/2
            }
            else if(t <= tp1 + tp2 + tp3 + tp4) {
                return vf3
            }
            else if(t <= tp1 + tp2 + tp3 + tp4 + tp5) {
                val tCurr = t - tp1 - tp2 - tp3 - tp4
                return vf4 - (j*tCurr.pow(2))/2
            }
            else if(t <= tp1 + tp2 + tp3 + tp4 + tp5 + tp6) {
                val tCurr = t - tp1 - tp2 - tp3 - tp4 - tp5
                return vf5 - aMax*tCurr
            }
            else{
                val tCurr = t - tp1 - tp2 - tp3 - tp4 - tp5 - tp6
                return vf6 - aMax*tCurr + (j*tCurr.pow(2))/2
            }
        }
        return generateVelocitiesFromFunction(vFn, tTotal, direction)
    }

    // Numeric Utility
    private fun getNumVal(numStr: String): Float {
        val num = numStr.toFloat()
        if (num <= 0)
            throw NumberFormatException("Non-Positive Float not allowed")
        return num
    }

    private fun arange(start:Float, stop:Float, step:Float) : MutableList<Float> {
        val a = mutableListOf<Float>()
        var i = start
        while(i < stop) {
            a.add(i)
            i += step
        }
        return a
    }

    // Flight controller state utility
    private suspend fun setVirtualSticks(enable: Boolean) {
        val virtualSticksError = suspendCoroutine<DJIError?> { cont ->
            drone?.flightController?.setVirtualStickModeEnabled(enable) {
                cont.resume(it)
            }
        }
        val action = if (enable) "Enable" else "Disable"

        if(virtualSticksError != null)
            throw DJIControlException("Cannot $action Virtual Sicks")
    }

    private fun getCurrentControlModes(): Triple<VerticalControlMode?, YawControlMode?, RollPitchControlMode?> {
        val vcm = drone?.flightController?.verticalControlMode
        val ycm = drone?.flightController?.yawControlMode
        val rpcm = drone?.flightController?.rollPitchControlMode

        return Triple(vcm, ycm, rpcm)
    }

    private fun setCurrentControlModes(desiredControlModes: Triple<VerticalControlMode?, YawControlMode?, RollPitchControlMode?>) {
        drone?.flightController?.verticalControlMode = desiredControlModes.first
        drone?.flightController?.yawControlMode = desiredControlModes.second
        drone?.flightController?.rollPitchControlMode = desiredControlModes.third
    }

    // UI Handling Functions
    private fun updateDroneDetails() {

        drone?.also { drone ->

            if(drone.isConnected) {
                drone.getName(object: CommonCallbacks.CompletionCallbackWith<String> {
                    override fun onSuccess(droneName: String?) {
                        droneNameText.text = droneName
                    }

                    override fun onFailure(error: DJIError?) {
                        droneNameText.text = getString(R.string.unknown_drone)
                        if(error != null)
                            showToast(error.description)
                    }
                })

                drone.battery.also { battery ->
                  battery.setStateCallback {
                      batteryText.text = getString(R.string.battery_percentage, it.chargeRemainingInPercent, "%")
                  }
                }
            }
            else {
                droneNameText.text = getString(R.string.drone_disconnected)
                batteryText.text = ""
                removeComponentCallbacks()
            }
        }?: run {
            droneNameText.text = getString(R.string.none_connected)
            batteryText.text = ""
        }
    }

    private fun removeComponentCallbacks() {
        drone?.battery?.setStateCallback(null)
    }

    // SDK Callback Functions
    override fun onRegister(error: DJIError?) {
        if(error == DJISDKError.REGISTRATION_SUCCESS)
            regText.text = getString(R.string.registered, sdkManager.sdkVersion)
        else{
            regText.text = getString(R.string.register_failed)
            Log.i(TAG, "onRegister Failed: ${error?.description}")
        }
    }

    override fun onProductConnect(product: BaseProduct?) {
        drone = product as Aircraft?

        drone?.flightController?.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

        updateDroneDetails()
        notifyStatusChanged()
    }

    override fun onProductChanged(product: BaseProduct?) {
        drone = product as Aircraft?

        drone?.flightController?.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY

        updateDroneDetails()
        notifyStatusChanged()
    }

    override fun onProductDisconnect() {
        removeComponentCallbacks()
        drone = null
        updateDroneDetails()
        notifyStatusChanged()
    }

    override fun onComponentChange(
        p0: BaseProduct.ComponentKey?,
        p1: BaseComponent?,
        p2: BaseComponent?
    ) {}

    override fun onInitProcess(p0: DJISDKInitEvent?, p1: Int) {}

    override fun onDatabaseDownloadProgress(p0: Long, p1: Long) {}

    // UI Utility Functions
    private fun notifyStatusChanged() {
        mHandler.removeCallbacks(updateRunnable)
        mHandler.postDelayed(updateRunnable, 500)
    }

    private val updateRunnable = Runnable {
        val intent = Intent(FLAG_CONNECTION_CHANGE)
        sendBroadcast(intent)
    }

    private fun showToast(text: String) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }
}