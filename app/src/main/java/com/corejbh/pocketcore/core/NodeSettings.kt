package com.corejbh.pocketcore.core

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

/** The three Bitcoin networks a node can run on. */
enum class BitcoinNetwork(
    val label: String,
    val chainArg: String,
    val rpcPort: Int,
    val p2pPort: Int,
    val dataSubdir: String
) {
    MAINNET("Mainnet", "main", 8332, 8333, ""),
    SIGNET("Signet", "signet", 38332, 38333, "signet"),
    TESTNET4("Testnet4", "testnet4", 48332, 48333, "testnet4");

    companion object {
        fun fromName(value: String?): BitcoinNetwork =
            entries.firstOrNull { it.name == value } ?: MAINNET
    }
}

/** Everything the user can change, mirrored in memory for the UI. */
data class NodeSettingsState(
    val network: BitcoinNetwork = BitcoinNetwork.MAINNET,
    val pruneMb: Int = 550,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    /**
     * Peer connections through Orbot's SOCKS5 proxy at 127.0.0.1:9050.
     *
     * A node that answers its own DNS and connects straight out hands its
     * address to every peer it meets; the proxy is the phone's way of not doing
     * that. It is off by default because the node cannot reach a peer at all
     * while Orbot is not running.
     */
    val torProxy: Boolean = false,
    val rpcUser: String = "",
    val rpcPassword: String = "",
    val shouldRun: Boolean = false,
    val snapshotPath: String? = null,
    val snapshotLabel: String? = null,
    val descriptorInput: String = "",
    val descriptorNormalized: String? = null
)

/**
 * Small persisted settings store. Values are read once and then kept in a
 * StateFlow so the UI and the foreground service always agree.
 *
 * The secrets - the RPC password and the watch-only descriptor - are kept in
 * [SecretStore] (AES-256-GCM under a Keystore key), migrated from the plaintext
 * SharedPreferences values of earlier builds.
 *
 * **Reading is not the constructor's job.** The constructor touches nothing: the
 * state starts at the defaults and [load] fills it in. Reading this store on a
 * cold start means a preference file and, for the secrets, Keystore cipher
 * operations, and the eighth review found exactly that running on the main
 * thread inside `NodeController.init` - on the frame where the activity or the
 * service starts. [NodeController] therefore calls [load] once on
 * `Dispatchers.IO` and holds every action that depends on the settings until it
 * has finished, so no start can be built from the defaults.
 *
 * The setters, [setDescriptor] and [rotateCredentials] are `@Synchronized`: the
 * start path rotates the credentials while the UI can be saving a descriptor,
 * and both end with `_state.value = read()`, so two unlocked writers would
 * publish a state built from a half-applied pair of values. They are short
 * preference writes; one lock on this object is enough.
 *
 * Every write is [SharedPreferences.Editor.commit]. These values decide what the
 * next start runs and what the running daemon authenticates with, so a write
 * that returns has to be on disk: `apply()` returns first and writes later, and
 * a process that dies in that window would lose the rotated RPC pair or the
 * network choice it just announced.
 */
@SuppressLint("ApplySharedPref")
class NodeSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = SecretStore(prefs)
    private val _state = MutableStateFlow(NodeSettingsState())
    val state: StateFlow<NodeSettingsState> = _state

    /**
     * True once [load] has read what this process has stored.
     *
     * Until then [state] holds the defaults, which are not what the user chose:
     * a start built from them would run mainnet for someone who selected signet.
     */
    @Volatile
    var loaded: Boolean = false
        private set

    /**
     * Reads the stored settings and publishes them. Runs off the main thread
     * (`Dispatchers.IO`) from [NodeController.init].
     */
    @Synchronized
    fun load() {
        _state.value = read()
        loaded = true
    }

    private fun read(): NodeSettingsState {
        val user = prefs.getString(KEY_RPC_USER, null) ?: generateToken(16).also {
            prefs.edit().putString(KEY_RPC_USER, it).commit()
        }
        val password = secrets.read(KEY_RPC_PASSWORD) ?: generateToken(32).also {
            secrets.write(KEY_RPC_PASSWORD, it)
        }
        return NodeSettingsState(
            network = BitcoinNetwork.fromName(prefs.getString(KEY_NETWORK, null)),
            pruneMb = prefs.getInt(KEY_PRUNE, 550),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
            chargingOnly = prefs.getBoolean(KEY_CHARGING_ONLY, false),
            torProxy = prefs.getBoolean(KEY_TOR_PROXY, false),
            rpcUser = user,
            rpcPassword = password,
            shouldRun = prefs.getBoolean(KEY_SHOULD_RUN, false),
            snapshotPath = prefs.getString(KEY_SNAPSHOT_PATH, null),
            snapshotLabel = prefs.getString(KEY_SNAPSHOT_LABEL, null),
            descriptorInput = secrets.read(KEY_DESCRIPTOR_INPUT) ?: "",
            descriptorNormalized = secrets.read(KEY_DESCRIPTOR_NORMALIZED)
        )
    }

    fun setNetwork(network: BitcoinNetwork) = edit { putString(KEY_NETWORK, network.name) }

    fun setPruneMb(value: Int) = edit { putInt(KEY_PRUNE, value) }

    fun setWifiOnly(value: Boolean) = edit { putBoolean(KEY_WIFI_ONLY, value) }

    fun setChargingOnly(value: Boolean) = edit { putBoolean(KEY_CHARGING_ONLY, value) }

    fun setTorProxy(value: Boolean) = edit { putBoolean(KEY_TOR_PROXY, value) }

    fun setShouldRun(value: Boolean) = edit { putBoolean(KEY_SHOULD_RUN, value) }

    fun setSnapshot(path: String?, label: String?) = edit {
        putString(KEY_SNAPSHOT_PATH, path)
        putString(KEY_SNAPSHOT_LABEL, label)
    }

    /**
     * Remembered so the watch-only scan survives a restart.
     *
     * A descriptor describes the user's addresses - which coins are theirs - so
     * it goes through [SecretStore] instead of a plain SharedPreferences entry.
     * A blank value is removed rather than stored, and the plaintext keys of
     * earlier builds are dropped by [SecretStore.write] on the first save.
     */
    @Synchronized
    fun setDescriptor(input: String, normalized: String?) {
        if (input.isBlank()) secrets.remove(KEY_DESCRIPTOR_INPUT) else secrets.write(KEY_DESCRIPTOR_INPUT, input)
        if (normalized.isNullOrBlank()) {
            secrets.remove(KEY_DESCRIPTOR_NORMALIZED)
        } else {
            secrets.write(KEY_DESCRIPTOR_NORMALIZED, normalized)
        }
        // Nothing is left for the plain keys of builds before versionCode 13.
        prefs.edit().remove(KEY_DESCRIPTOR_INPUT).remove(KEY_DESCRIPTOR_NORMALIZED).commit()
        _state.value = read()
    }

    /** A new credential pair, used when an RPC port had to be left to another app. */
    @Synchronized
    fun rotateCredentials() {
        prefs.edit().putString(KEY_RPC_USER, generateToken(16)).commit()
        secrets.write(KEY_RPC_PASSWORD, generateToken(32))
        _state.value = read()
    }

    /** Applies one settings change and republishes the whole state. */
    @Synchronized
    private fun edit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(change).commit()
        _state.value = read()
    }

    companion object {
        const val PREFS = "pocketcore_settings"
        private const val KEY_NETWORK = "network"
        private const val KEY_PRUNE = "prune_mb"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_CHARGING_ONLY = "charging_only"
        private const val KEY_TOR_PROXY = "tor_proxy"
        private const val KEY_RPC_USER = "rpc_user"
        private const val KEY_RPC_PASSWORD = "rpc_password"
        private const val KEY_SHOULD_RUN = "should_run"
        private const val KEY_SNAPSHOT_PATH = "snapshot_path"
        private const val KEY_SNAPSHOT_LABEL = "snapshot_label"
        private const val KEY_DESCRIPTOR_INPUT = "descriptor_input"
        private const val KEY_DESCRIPTOR_NORMALIZED = "descriptor_normalized"

        private const val ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun generateToken(length: Int): String {
            val random = SecureRandom()
            val builder = StringBuilder(length)
            repeat(length) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            return builder.toString()
        }
    }
}
