package com.macsstop.dialer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.AuthInfo
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Player
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the Linphone SIP engine (UDP / TCP / TLS) and talks to the web UI through JSON events.
 * All Linphone calls run on the main thread.
 */
object SipManager {
    private const val TAG = "MacsSip"

    private var core: Core? = null
    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())

    /** Receives JSON events for the web UI. */
    var listener: ((String) -> Unit)? = null
    var onIncoming: ((String) -> Unit)? = null
    var onCallsChanged: (() -> Unit)? = null
    var onRegChanged: ((String) -> Unit)? = null

    private var regState = "None"
    private var domain = ""
    private val calls = LinkedHashMap<String, Call>()
    private val incomingIds = HashSet<String>()

    // hold audio
    private var holdLoop = true
    private var holdListen = false
    private var holdCallId: String? = null
    private var holdPlayer: Player? = null
    private var localPlayer: MediaPlayer? = null
    private var loopTask: Runnable? = null
    private val muteBeforeHold = HashMap<String, Boolean>()

    private fun holdFile() = File(appCtx.filesDir, "hold.wav")

    fun init(ctx: Context) {
        if (core != null) return
        appCtx = ctx.applicationContext
        val factory = Factory.instance()
        factory.setDebugMode(false, TAG)
        val c = factory.createCore(null, null, appCtx)
        setFlag(c, true, "setNativeRingingEnabled", "enableNativeRinging")
        setFlag(c, true, "setKeepAliveEnabled", "enableKeepAlive")
        setFlag(c, true, "setVibrationOnIncomingCallEnabled", "enableVibrationOnIncomingCall")
        c.addListener(coreListener)
        c.start()
        core = c
    }

    // ---------------------------------------------------------------- listener

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core, account: Account, state: RegistrationState?, message: String
        ) {
            regState = state?.name ?: "None"
            emit(JSONObject().put("type", "reg").put("state", regState).put("message", message))
            onRegChanged?.invoke(regState)
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            val st = state ?: return
            val id = idOf(call)
            when (st) {
                Call.State.IncomingReceived -> {
                    calls[id] = call
                    incomingIds.add(id)
                    emit(
                        JSONObject().put("type", "incoming").put("id", id)
                            .put("user", call.remoteAddress.username ?: "")
                            .put("display", call.remoteAddress.displayName ?: "")
                    )
                    onIncoming?.invoke(displayOf(call))
                }
                Call.State.OutgoingInit -> calls[id] = call
                Call.State.Connected, Call.State.StreamsRunning -> incomingIds.remove(id)
                Call.State.End, Call.State.Error, Call.State.Released -> {
                    if (holdCallId == id) stopHoldInternal(id, call, restoreMic = false)
                    calls.remove(id)
                    incomingIds.remove(id)
                }
                else -> {}
            }
            emit(JSONObject().put("type", "call").put("id", id).put("state", st.name).put("message", message))
            onCallsChanged?.invoke()
        }
    }

    private fun idOf(call: Call): String = call.callLog.callId ?: ("c" + System.identityHashCode(call))
    private fun displayOf(call: Call): String {
        val a = call.remoteAddress
        return a.displayName?.takeIf { it.isNotBlank() } ?: a.username ?: "Unknown caller"
    }

    fun hasRingingCall(): Boolean = calls.any { (id, c) -> id in incomingIds && c.state == Call.State.IncomingReceived }

    private fun emit(o: JSONObject) {
        val s = o.toString()
        main.post { listener?.invoke(s) }
    }

    // ---------------------------------------------------------------- helpers

    private fun <T> onMain(default: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try { block() } catch (e: Exception) { Log.e(TAG, "error", e); default }
        }
        var result = default
        val latch = CountDownLatch(1)
        main.post {
            try { result = block() } catch (e: Exception) { Log.e(TAG, "error", e) } finally { latch.countDown() }
        }
        latch.await(6, TimeUnit.SECONDS)
        return result
    }

    /** Calls a method by name (used where the method name differs between SDK versions). */
    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size }
            ?: throw NoSuchMethodException(name)
        return m.invoke(target, *args)
    }

    private fun tryInvoke(target: Any, name: String, vararg args: Any?): Boolean =
        try { invoke(target, name, *args); true } catch (e: Exception) { false }

    private fun setFlag(target: Any, v: Boolean, vararg names: String) {
        for (n in names) if (tryInvoke(target, n, v)) return
        Log.w(TAG, "No method found for ${names.joinToString()}")
    }

    private fun createAuthInfo(f: Factory, user: String, userId: String, pass: String, dom: String): AuthInfo? {
        val methods = f.javaClass.methods.filter { it.name == "createAuthInfo" }.sortedByDescending { it.parameterTypes.size }
        for (m in methods) {
            val args: Array<Any?> = when (m.parameterTypes.size) {
                7 -> arrayOf(user, userId, pass, null, null, dom, null)
                6 -> arrayOf(user, userId, pass, null, null, dom)
                else -> continue
            }
            try { return m.invoke(f, *args) as AuthInfo } catch (e: Exception) { Log.w(TAG, "createAuthInfo", e) }
        }
        return null
    }

    private fun failReg(msg: String) {
        regState = "Failed"
        emit(JSONObject().put("type", "reg").put("state", "Failed").put("message", msg))
        onRegChanged?.invoke("Failed")
    }

    // ---------------------------------------------------------------- account

    fun register(json: String) = onMain(Unit) {
        val c = core ?: return@onMain
        val o = JSONObject(json)
        val user = o.optString("user").trim()
        val pass = o.optString("pass")
        val authUser = o.optString("auth").trim().ifBlank { user }
        domain = o.optString("domain").trim().removePrefix("sip:").removePrefix("sips:").trimEnd('/')
        val port = o.optString("port").trim()
        val transport = when (o.optString("transport").lowercase()) {
            "tcp" -> TransportType.Tcp
            "tls" -> TransportType.Tls
            else -> TransportType.Udp
        }
        if (user.isEmpty() || domain.isEmpty()) { failReg("Enter the username and domain"); return@onMain }

        c.clearAccounts()
        c.clearAllAuthInfo()

        val f = Factory.instance()
        val auth = createAuthInfo(f, user, authUser, pass, domain) ?: run { failReg("Could not create login"); return@onMain }
        c.addAuthInfo(auth)

        val identity = f.createAddress("sip:$user@$domain") ?: run { failReg("Invalid username or domain"); return@onMain }
        val displayName = o.optString("displayName").trim()
        if (displayName.isNotEmpty()) identity.setDisplayName(displayName)

        val server = f.createAddress("sip:$domain" + (if (port.isNotEmpty()) ":$port" else ""))
            ?: run { failReg("Invalid domain or port"); return@onMain }
        server.setTransport(transport)

        val params = c.createAccountParams()
        params.setIdentityAddress(identity)
        if (!tryInvoke(params, "setServerAddress", server)) {
            tryInvoke(params, "setServerAddr", server.asStringUriOnly())
        }
        setFlag(params, true, "setRegisterEnabled", "enableRegister")

        val stun = o.optString("stun").trim().removePrefix("stun:").removePrefix("stuns:")
        if (stun.isNotEmpty()) {
            val nat = c.createNatPolicy()
            tryInvoke(nat, "setStunServer", stun)
            setFlag(nat, true, "setStunEnabled", "enableStun")
            if (!tryInvoke(params, "setNatPolicy", nat)) tryInvoke(c, "setNatPolicy", nat)
        }

        val account = c.createAccount(params)
        c.addAccount(account)
        tryInvoke(c, "setDefaultAccount", account)
        regState = "Progress"
        emit(JSONObject().put("type", "reg").put("state", "Progress").put("message", ""))
        onRegChanged?.invoke("Progress")
    }

    fun unregister() = onMain(Unit) {
        val c = core ?: return@onMain
        c.clearAccounts()
        c.clearAllAuthInfo()
        regState = "Cleared"
        emit(JSONObject().put("type", "reg").put("state", "Cleared").put("message", ""))
        onRegChanged?.invoke("Cleared")
    }

    // ---------------------------------------------------------------- calls

    fun call(target: String): String = onMain("") {
        val c = core ?: return@onMain ""
        val f = Factory.instance()
        val addr = f.createAddress(target) ?: return@onMain ""
        val params = c.createCallParams(null)
        val call: Call? = when {
            params != null && c.javaClass.methods.any { it.name == "inviteAddressWithParams" && it.parameterTypes.size == 2 } ->
                invoke(c, "inviteAddressWithParams", addr, params) as Call?
            else -> invoke(c, "inviteAddress", addr) as Call?
        }
        if (call == null) return@onMain ""
        val id = idOf(call)
        calls[id] = call
        id
    }

    fun answer(id: String) = onMain(Unit) { calls[id]?.accept() }
    fun decline(id: String) = onMain(Unit) { calls[id]?.decline(Reason.Declined) }
    fun hangup(id: String) = onMain(Unit) { calls[id]?.terminate() }
    fun setMute(id: String, on: Boolean) = onMain(Unit) {
        val call = calls[id] ?: return@onMain
        if (holdCallId == id) muteBeforeHold[id] = on else call.microphoneMuted = on
    }
    fun dtmf(id: String, d: String) = onMain(Unit) { if (d.isNotEmpty()) calls[id]?.sendDtmf(d[0]) }

    fun transfer(id: String, t: String) = onMain(Unit) {
        val call = calls[id] ?: return@onMain
        val uri = if (t.startsWith("sip:")) t else "sip:${t.replace(" ", "")}@$domain"
        val addr = Factory.instance().createAddress(uri) ?: return@onMain
        call.transferTo(addr)
    }

    fun setSpeaker(on: Boolean) = onMain(Unit) {
        val c = core ?: return@onMain
        val call = c.currentCall ?: calls.values.lastOrNull() ?: return@onMain
        val want = if (on) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece
        val dev = c.audioDevices.firstOrNull { it.type == want } ?: return@onMain
        call.setOutputAudioDevice(dev)
    }

    // ---------------------------------------------------------------- hold audio

    fun saveHoldWav(b64: String, loop: Boolean) {
        holdLoop = loop
        try {
            holdFile().writeBytes(Base64.decode(b64, Base64.DEFAULT))
        } catch (e: Exception) { Log.e(TAG, "saveHoldWav", e) }
    }

    fun clearHold() { try { holdFile().delete() } catch (e: Exception) {} }

    /** Mutes the mic and streams the hold file to the caller. The call itself stays connected. */
    fun holdStart(id: String, listen: Boolean): Boolean = onMain(false) {
        val call = calls[id] ?: return@onMain false
        if (holdCallId != null) stopHoldInternal(holdCallId!!, calls[holdCallId!!], restoreMic = true)
        muteBeforeHold[id] = call.microphoneMuted
        call.microphoneMuted = true
        holdCallId = id
        holdListen = listen
        val file = holdFile()
        if (!file.exists() || file.length() < 100) return@onMain false

        val p = call.player
        if (p != null) {
            p.open(file.absolutePath)
            p.start()
            holdPlayer = p
            val task = object : Runnable {
                override fun run() {
                    if (holdCallId != id) return
                    try {
                        val dur = p.duration
                        val ended = p.state.name != "Playing" || (dur > 0 && p.currentPosition >= dur - 250)
                        if (ended && holdLoop) { p.seek(0); p.start() }
                    } catch (e: Exception) {}
                    main.postDelayed(this, 400)
                }
            }
            loopTask = task
            main.postDelayed(task, 800)
        }

        try {
            localPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                isLooping = holdLoop
                prepare()
                val v = if (holdListen) 1f else 0f
                setVolume(v, v)
                start()
            }
        } catch (e: Exception) { Log.w(TAG, "local hold player", e) }
        true
    }

    fun holdStop(id: String) = onMain(Unit) {
        if (holdCallId == id) stopHoldInternal(id, calls[id], restoreMic = true)
    }

    fun setHoldListen(on: Boolean) = onMain(Unit) {
        holdListen = on
        val v = if (on) 1f else 0f
        try { localPlayer?.setVolume(v, v) } catch (e: Exception) {}
    }

    private fun stopHoldInternal(id: String, call: Call?, restoreMic: Boolean) {
        loopTask?.let { main.removeCallbacks(it) }
        loopTask = null
        try { holdPlayer?.pause() } catch (e: Exception) {}
        try { holdPlayer?.close() } catch (e: Exception) {}
        holdPlayer = null
        try { localPlayer?.stop() } catch (e: Exception) {}
        try { localPlayer?.release() } catch (e: Exception) {}
        localPlayer = null
        val prev = muteBeforeHold.remove(id) ?: false
        if (restoreMic && call != null) {
            try { call.microphoneMuted = prev } catch (e: Exception) {}
        }
        holdCallId = null
    }

    // ---------------------------------------------------------------- sync

    fun ready() = main.post {
        val arr = JSONArray()
        for ((id, c) in calls) {
            arr.put(
                JSONObject().put("id", id)
                    .put("user", c.remoteAddress.username ?: "")
                    .put("display", c.remoteAddress.displayName ?: "")
                    .put("incoming", id in incomingIds || c.dir == Call.Dir.Incoming)
                    .put("state", c.state.name)
            )
        }
        listener?.invoke(JSONObject().put("type", "sync").put("reg", regState).put("calls", arr).toString())
    }
}
