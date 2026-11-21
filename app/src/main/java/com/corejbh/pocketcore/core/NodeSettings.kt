package com.corejbh.pocketcore.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
 * The RPC password is kept in [SecretStore]: AES-256-GCM under a Keystore key,
 * migrated from the plain SharedPreferences value of earlier builds.
 */
class NodeSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = SecretStore(prefs)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<NodeSettingsState> = _state

    private fun load(): NodeSettingsState {
        val user = prefs.getString(KEY_RPC_USER, null) ?: generateToken(16).also {
            prefs.edit().putString(KEY_RPC_USER, it).apply()
        }
        val password = secrets.read(KEY_RPC_PASSWORD) ?: generateToken(32).also {
            secrets.write(KEY_RPC_PASSWORD, it)
        }
        return NodeSettingsState(
            network = BitcoinNetwork.fromName(prefs.getString(KEY_NETWORK, null)),
            pruneMb = prefs.getInt(KEY_PRUNE, 550),
            wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
            chargingOnly = prefs.getBoolean(KEY_CHARGING_ONLY, false),
            rpcUser = user,
            rpcPassword = password,
            shouldRun = prefs.getBoolean(KEY_SHOULD_RUN, false),
            snapshotPath = prefs.getString(KEY_SNAPSHOT_PATH, null),
            snapshotLabel = prefs.getString(KEY_SNAPSHOT_LABEL, null),
            descriptorInput = prefs.getString(KEY_DESCRIPTOR_INPUT, "") ?: "",
            descriptorNormalized = prefs.getString(KEY_DESCRIPTOR_NORMALIZED, null)
        )
    }

    fun setNetwork(network: BitcoinNetwork) = edit { putString(KEY_NETWORK, network.name) }

    fun setPruneMb(value: Int) = edit { putInt(KEY_PRUNE, value) }

    fun setWifiOnly(value: Boolean) = edit { putBoolean(KEY_WIFI_ONLY, value) }

    fun setChargingOnly(value: Boolean) = edit { putBoolean(KEY_CHARGING_ONLY, value) }

    fun setShouldRun(value: Boolean) = edit { putBoolean(KEY_SHOULD_RUN, value) }

    fun setSnapshot(path: String?, label: String?) = edit {
        putString(KEY_SNAPSHOT_PATH, path)
        putString(KEY_SNAPSHOT_LABEL, label)
    }

    /** Remembered so the watch-only scan survives a restart. */
    fun setDescriptor(input: String, normalized: String?) = edit {
        putString(KEY_DESCRIPTOR_INPUT, input)
        putString(KEY_DESCRIPTOR_NORMALIZED, normalized)
    }

    /** A new credential pair, used when an RPC port had to be left to another app. */
    fun rotateCredentials() {
        prefs.edit().putString(KEY_RPC_USER, generateToken(16)).apply()
        secrets.write(KEY_RPC_PASSWORD, generateToken(32))
        _state.value = load()
    }

    /** Applies one settings change and republishes the whole state. */
    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = load()
    }

    companion object {
        const val PREFS = "pocketcore_settings"
        private const val KEY_NETWORK = "network"
        private const val KEY_PRUNE = "prune_mb"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_CHARGING_ONLY = "charging_only"
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
