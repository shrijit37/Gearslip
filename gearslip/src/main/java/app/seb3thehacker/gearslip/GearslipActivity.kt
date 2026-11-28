package app.seb3thehacker.gearslip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import android.content.res.Configuration
import app.seb3thehacker.gearslip.audio.AudioCaptureService
import app.seb3thehacker.gearslip.audio.CarAudio
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarServices
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.Prefetch
import app.seb3thehacker.gearslip.protocol.TouchPoint
import app.seb3thehacker.gearslip.surface.HeadunitSurfaces
import app.seb3thehacker.gearslip.surface.PlaceholderSurface
import app.seb3thehacker.gearslip.surface.SurfaceTrust
import app.seb3thehacker.gearslip.surface.AppCommands
import app.seb3thehacker.gearslip.surface.api.SurfaceCommand
import app.seb3thehacker.gearslip.surface.SurfaceSelection
import app.seb3thehacker.gearslip.ui.GearslipApp
import app.seb3thehacker.gearslip.ui.GearslipTheme
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Hosts the phone UI and the car session itself.
 *
 * Launched by the system when the head unit switches the phone into accessory mode. The
 * session lives in this activity, so [android:configChanges] in the manifest keeps a
 * rotation or theme change from recreating it and dropping the connection. The AAP core
 * owns no UI: this activity only wires the session into phone-side permissions and state.
 */
class GearslipActivity : ComponentActivity() {

    private var descriptor: ParcelFileDescriptor? = null
    /** The wireless session's socket, when the car came over Wi-Fi instead of USB. */
    private var wirelessSocket: java.net.Socket? = null
    private var runner: GearslipRunner? = null
    private var worker: Thread? = null

    /** Media audio needs the microphone permission (a platform rule for playback capture) first. */
    private val audioPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) askAudioCapture()
        else if (!shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
            // Refused twice: Android now says no without showing anything, so the phone must.
            GearslipLog.w("audio: record permission blocked - Android no longer asks")
            CarAudio.micBlocked()
            micBlockedNotice.value = true
        } else {
            GearslipLog.w("audio: record permission declined")
            CarAudio.denied()
        }
    }

    private val audioConsent = registerForActivityResult(StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            AudioCaptureService.start(this, result.resultCode, data)
        } else {
            GearslipLog.w("audio: capture consent declined")
            CarAudio.denied()
            if (!CarSettings.audioChoiceAsked) offerBluetooth.value = true
        }
    }

    /** Raised when the microphone permission is blocked; see [MicBlockedDialog]. */
    private val micBlockedNotice = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Raised once, after the first "no" to the capture dialog; see [AudioChoiceDialog]. */
    private val offerBluetooth = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                GearslipLog.w("accessory detached")
                // A wireless session has no accessory to lose; leave it running.
                if (wirelessSocket == null) closeAccessory()
            }
        }
    }

    /** Android's one-time "Allow" for a car to connect by itself; see [WirelessAuto]. */
    private var approvingCar: String? = null

    private val carApproval = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val address = approvingCar ?: return@registerForActivityResult
        approvingCar = null
        if (result.resultCode == RESULT_OK) app.seb3thehacker.gearslip.wireless.WirelessAuto.approved(this, address)
        else app.seb3thehacker.gearslip.wireless.WirelessAuto.declined(this, address)
    }

    /** Setting Gearslip as the phone's caller ID and spam app - what lets it decline a ringing call. */
    private val callScreeningRole = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) GearslipLog.w("calls: call screening role declined")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Explicit composition-root registration: until a real renderer ships, the headless
        // placeholder is the installed surface. See docs/HEADUNIT_SURFACES.md.
        HeadunitSurfaces.installSurface(PlaceholderSurface, SurfaceTrust.TRUSTED)
        super.onCreate(savedInstanceState)
        // Location, calendar, the phone/contacts group, and the microphone are asked for one at
        // a time by PermissionsSetupScreen instead of here, each only after the driver taps
        // Allow on a screen explaining what it is for. Firing them all from onCreate, unprompted,
        // is both a bad first impression and unreliable: Android does not consistently show more
        // than one system permission dialog fired back-to-back, which was silently dropping some.
        enableEdgeToEdge()

        GearslipLog.init(this)
        GearslipLog.installCrashHandler()
        GearslipLog.i("Gearslip ready")
        app.seb3thehacker.gearslip.wireless.WirelessLink.onConnected = { socket ->
            runOnUiThread { startWireless(socket) }
        }
        app.seb3thehacker.gearslip.wireless.WirelessLink.sessionWaiting = { runner?.waitingForVersion == true }
        app.seb3thehacker.gearslip.wireless.WirelessAuto.showApproval = { chooser, address ->
            runOnUiThread {
                approvingCar = address
                carApproval.launch(androidx.activity.result.IntentSenderRequest.Builder(chooser).build())
            }
        }
        CarSettings.init(this)
        // The runner now binds the map and media apps itself on service discovery. Initialise the
        // services here, before any car connects: CarUi() is no longer composed on a real session,
        // so waiting for the deleted car screen to do it would leave autostart() with no context
        // and nothing would bind. CarBluetooth still needs an early init for issue #43.
        // CarEnvironment used to start in onSurfaceReady(), which no longer has a caller. Start it
        // here too so night mode, battery and appContext are ready before the first app binds.
        CarEnvironment.start(this)
        CarServices.init(this)
        app.seb3thehacker.gearslip.car.CarBluetooth.init(this)
        AppSettings.applyGearslipEnabled(this)
        // A capture that died without tidying up would leave notifications exposed to every
        // later screen share. Nothing is capturing if the service is not running, so undo it.
        if (!app.seb3thehacker.gearslip.audio.AudioCaptureService.running) {
            app.seb3thehacker.gearslip.audio.ScreenShareGuard.restore(this)
        }
        Prefetch.warm(this)
        CarEnvironment.setPhoneTheme(resources.configuration)

        setContent {
            GearslipTheme {
                GearslipApp(
                    onDisconnect = {
                        closeAccessory()
                        // An explicit disconnect is not a cable slip: runner.stop() closes the
                        // audio link, which starts a 30s grace timer. End the capture now so the
                        // phone's speaker and screen sharing come back at once.
                        CarAudio.release()
                    },
                    onRequestCallScreening = ::requestCallScreeningRole,
                )
                val offer by offerBluetooth.collectAsState()
                if (offer) {
                    fun close() {
                        CarSettings.markAudioChoiceAsked()
                        offerBluetooth.value = false
                    }
                    app.seb3thehacker.gearslip.ui.AudioChoiceDialog(
                        onUseBluetooth = {
                            AppCommands.uiSink.submit(SurfaceCommand.SetAudioTransport(false))
                            close()
                        },
                        onKeepAsking = ::close,
                    )
                }
                val micNotice by micBlockedNotice.collectAsState()
                if (micNotice) {
                    app.seb3thehacker.gearslip.ui.MicBlockedDialog(
                        onOpenSettings = {
                            micBlockedNotice.value = false
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                            )
                        },
                        onDismiss = { micBlockedNotice.value = false },
                    )
                }
            }
        }

        CarAudio.onConsentRequested = { runOnUiThread { requestAudioCapture() } }

        registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), RECEIVER_NOT_EXPORTED)
        handleIntent(intent)
    }

    // The manifest opts out of recreation on uiMode changes, so a light/dark switch on the phone
    // arrives here, and the car UI follows it.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CarEnvironment.setPhoneTheme(newConfig)
    }

    // Throttled inside Prefetch, so this only does work if the last run was a while ago.
    override fun onStart() {
        super.onStart()
        Prefetch.warm(this)
        // Back from the app's settings with the microphone turned on: try car audio again.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            CarAudio.micAllowed()
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the system page where the driver allowed installs from Gearslip: finish the
        // update that was waiting rather than leaving the downloaded APK on the card.
        app.seb3thehacker.gearslip.update.UpdateChecker.onAppResumed(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        app.seb3thehacker.gearslip.wireless.WirelessLink.onConnected = null
        app.seb3thehacker.gearslip.wireless.WirelessLink.sessionWaiting = null
        app.seb3thehacker.gearslip.wireless.WirelessAuto.showApproval = null
        runCatching { unregisterReceiver(detachReceiver) }
        CarAudio.onConsentRequested = null
        CarAudio.release()
        closeAccessory()
        CarServices.shutdown()
        CarEnvironment.stop()
        GearslipLog.flush()
    }

    /** Launches the system dialog that offers Gearslip the call-screening role, if it isn't held already. */

    /** Fail closed if a plug-in factory throws while being selected. */
    private fun selectSurface(): SurfaceSelection = runCatching { HeadunitSurfaces.select(this) }
        .getOrElse { e ->
            GearslipLog.w("surface: selection threw, running headless: ${e.message}")
            SurfaceSelection(null, HeadunitSurfaces.generation(), false)
        }


    private fun requestCallScreeningRole() {
        val roleManager = getSystemService(android.app.role.RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_CALL_SCREENING)) {
            GearslipLog.w("calls: call screening role unavailable on this phone")
            return
        }
        if (roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING)) return
        runCatching {
            callScreeningRole.launch(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING))
        }.onFailure { GearslipLog.e("could not ask for the call screening role", it) }
    }

    // --- Media audio --------------------------------------------------------------------

    private fun requestAudioCapture() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askAudioCapture()
        } else {
            audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun askAudioCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { audioConsent.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                GearslipLog.e("could not ask for audio capture", it)
                CarAudio.denied()
            }
    }

    private fun handleIntent(intent: Intent?) {
        // Debug builds only: opens the audio choice for a look, without turning down a real prompt.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("show_audio_choice", false) == true) offerBluetooth.value = true
        if (intent?.action == app.seb3thehacker.gearslip.wireless.WirelessAuto.ACTION_CAR_NEARBY) {
            connectToNearbyCar(intent.getStringExtra(app.seb3thehacker.gearslip.wireless.WirelessAuto.EXTRA_ADDRESS))
            return
        }
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        // Switched off in Settings: the system shouldn't route the car here at all, but if it
        // does, leave the accessory alone.
        if (!AppSettings.gearslipEnabled(this)) {
            GearslipLog.i("accessory attached, but Gearslip is switched off - not connecting")
            return
        }
        val accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        if (accessory == null) {
            GearslipLog.e("attach intent carried no accessory")
            SessionStatus.failed("Accessory missing", "The system reported a USB attach but did not include the accessory.")
            return
        }
        GearslipLog.i("accessory attached by the system - permission is implicit on this path")
        start(accessory)
    }

    /** A known car came up over Bluetooth: connect wirelessly, unless USB or another try is already on it. */
    private fun connectToNearbyCar(address: String?) {
        if (address == null) return
        if (!AppSettings.gearslipEnabled(this)) {
            GearslipLog.i("auto-connect: Gearslip is switched off - not connecting")
            return
        }
        if (descriptor != null) {
            GearslipLog.i("auto-connect: the car is on USB already - staying on USB")
            return
        }
        // Only a session that's still running counts. One the car ended (a ByeBye, a dropped
        // network) leaves its runner behind, since only Gearslip ending a session clears it, and
        // that used to make every later auto-connect quietly do nothing.
        if (worker?.isAlive == true || app.seb3thehacker.gearslip.wireless.WirelessLink.busy) {
            GearslipLog.i("auto-connect: a session is already running")
            return
        }
        if (runner != null) closeAccessory(stopWireless = false)
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            GearslipLog.w("auto-connect: needs the Nearby devices permission")
            return
        }
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val device = adapter?.let { runCatching { it.getRemoteDevice(address) }.getOrNull() }
        if (device == null) {
            GearslipLog.w("auto-connect: Bluetooth couldn't hand over the car")
            return
        }
        GearslipLog.i("auto-connect: connecting")
        app.seb3thehacker.gearslip.wireless.WirelessLink.start(this, device)
    }

    private fun start(accessory: UsbAccessory) {
        // A fresh ATTACHED intent means the system wants a new session, whatever state the old
        // one is in - closing first guarantees openAccessory() below never fights a descriptor
        // this same process forgot to release. The old worker thread often isn't alive by this
        // point anyway (its read loop already died when the head unit dropped the transport),
        // but the accessory it opened was never explicitly closed, which is what actually blocks
        // a re-open - a dead thread does not release the file descriptor on its own.
        closeAccessory()
        describe(accessory)

        val manager = getSystemService(UsbManager::class.java)
        val pfd = manager.openAccessory(accessory)
        if (pfd == null) {
            GearslipLog.e("openAccessory returned null - the session was claimed by someone else")
            SessionStatus.failed("Accessory unavailable", "Another app claimed the USB accessory before Gearslip could open it.")
            return
        }
        descriptor = pfd

        val selection = selectSurface()
        val runner = GearslipRunner(
            input = FileInputStream(pfd.fileDescriptor),
            output = FileOutputStream(pfd.fileDescriptor),
            identityProvider = { CertProvider.load(this) },
            vehicleProfileFor = { info -> VehicleProfiles.find(VehicleProfiles.load(this), info) },
            phoneIcons = { PhoneIcons.pngs(this) },
            surface = selection.surface,
            selectionGeneration = selection.generation,
            selectionTrusted = selection.trusted,
        )
        this.runner = runner
        CarServices.init(applicationContext)
        CarEnvironment.start(this)
        startSessionThread(runner)
        holdLocation()
    }

    /**
     * Runs the protocol read loop and tears the session down when it ends on its own.
     *
     * A rejected handshake or a car that hangs up never reaches the disconnect button: without
     * this the services started above (GPS, audio checks, hosted apps) and the held location
     * keep running until the activity dies. The identity check stops a late exit from closing
     * a newer session that has already replaced this runner.
     */
    private fun startSessionThread(runner: GearslipRunner) {
        worker = Thread {
            try {
                runner.run()
            } finally {
                runOnUiThread {
                    if (this.runner === runner) closeAccessory()
                }
            }
        }.apply { name = "gearslip"; start() }
    }

    /** A wireless session: the same runner, on the socket [WirelessLink] opened to the car. */
    private fun startWireless(socket: java.net.Socket) {
        closeAccessory(stopWireless = false)
        if (!AppSettings.gearslipEnabled(this)) {
            GearslipLog.i("wireless: Gearslip is switched off - not connecting")
            runCatching { socket.close() }
            return
        }
        GearslipLog.i("wireless: starting a session over Wi-Fi to ${socket.inetAddress?.hostAddress}:${socket.port}")
        wirelessSocket = socket
        val selection = selectSurface()
        val runner = GearslipRunner(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            identityProvider = { CertProvider.load(this) },
            vehicleProfileFor = { info -> VehicleProfiles.find(VehicleProfiles.load(this), info) },
            wireless = true,
            phoneIcons = { PhoneIcons.pngs(this) },
            surface = selection.surface,
            selectionGeneration = selection.generation,
            selectionTrusted = selection.trusted,
        )
        this.runner = runner
        CarServices.init(applicationContext)
        CarEnvironment.start(this)
        startSessionThread(runner)
        holdLocation()
    }

    /** Whether this session holds [app.seb3thehacker.gearslip.host.LocationKeepAlive]. */
    private var holdingLocation = false

    /**
     * Android lets a foreground service started from the background use location only in the few
     * seconds after one of the app's screens opens. The car just opened this one, so take the
     * location service now: a map bound later, with the phone already locked, got no position.
     */
    private fun holdLocation() {
        if (holdingLocation) return
        holdingLocation = true
        app.seb3thehacker.gearslip.host.LocationKeepAlive.acquire(this) {}
    }

    private fun describe(accessory: UsbAccessory) {
        GearslipLog.i("accessory: manufacturer=${accessory.manufacturer} model=${accessory.model}")
        GearslipLog.i("           version=${accessory.version} description=${accessory.description}")
    }

    private fun closeAccessory() = closeAccessory(stopWireless = true)

    /**
     * Ends the session, wired or wireless. [stopWireless] also drops the car's Bluetooth and Wi-Fi.
     *
     * This does not release audio capture: a natural worker exit or the cleanup before a new
     * connection must leave the grace window from [CarAudio.detach] intact so a brief disconnect
     * does not force another consent dialog. Explicit teardown calls [CarAudio.release] itself.
     */
    private fun closeAccessory(stopWireless: Boolean) {
        runner?.stop()
        // The car UI is no longer composed on a real session, so its disposal no longer shuts
        // the hosted apps down. Unbind them here while the old session is still identifiable.
        CarServices.disconnectHosts()
        CarEnvironment.stop()
        SessionReport.print()
        runCatching { descriptor?.close() }
        descriptor = null
        runCatching { wirelessSocket?.close() }
        wirelessSocket = null
        if (stopWireless) app.seb3thehacker.gearslip.wireless.WirelessLink.stop(this)
        runner = null
        if (holdingLocation) {
            holdingLocation = false
            app.seb3thehacker.gearslip.host.LocationKeepAlive.release(this)
        }
    }

}
