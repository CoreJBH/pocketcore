package com.corejbh.pocketcore.core

/**
 * The facts a sensitive RPC is allowed on, read at the moment it is sent.
 *
 * A sensitive RPC is one that rewrites what this app considers the chainstate -
 * `loadtxoutset` (replaces the UTXO set the node serves), `dumptxoutset` (walks
 * it for minutes and writes it out) and `scantxoutset` (walks it for the watch-
 * only scan). They are minutes-long requests to a process whose life the user
 * can end with one button, so the one-time [NodeController] listener proof is
 * not enough on its own (versionCode 21):
 *
 *  - [status] and [listenerVerified] are the same facts [clientAllowed] reads,
 *    and the reason every ordinary request waits for them;
 *  - [alive] is the daemon process, checked again here because a start can be
 *    verified and then lose its process to Android's low-memory killer;
 *  - [shouldRun] is what the user last asked for: a node the user stopped is not
 *    a node to hand a chainstate request, even in the window before STOP's own
 *    coroutine has published the new status;
 *  - [generation] against [currentGeneration] proves that no start or stop
 *    happened since the caller decided to make the call, so a long request is
 *    never sent on behalf of a daemon that has since been replaced;
 *  - [owner] is the port's ownership read **now** (`BitcoindNode.listenerOwner`)
 *    rather than the cached flag, which is what closes the window between the
 *    one proof and a port that changed hands afterwards.
 *
 * Fail-closed, and pure so every combination is unit tested (`SensitiveRpcTest`).
 */
internal data class SensitiveRpcFacts(
    val status: NodeStatus,
    val listenerVerified: Boolean,
    val alive: Boolean,
    val shouldRun: Boolean,
    val generation: Long,
    val currentGeneration: Long,
    val owner: SocketOwner
)

/** True only when every fact above holds at the same time. */
internal fun sensitiveRpcAllowed(facts: SensitiveRpcFacts): Boolean =
    clientAllowed(facts.status, facts.listenerVerified, facts.alive) &&
        facts.shouldRun &&
        facts.generation == facts.currentGeneration &&
        credentialsAllowed(facts.owner)

/**
 * Why a sensitive RPC was refused, in the card's words.
 *
 * The order is the order a reader can act on: a dead process first, then a node
 * that is not running, then a port that is no longer confirmed as this node's,
 * and only then the user's own stop. The last line is the fallback, so a reason
 * is always there to show.
 */
internal fun sensitiveRpcRefusal(facts: SensitiveRpcFacts): String = when {
    !facts.alive ->
        "The node process is gone, so the request was not sent. Start the node and try again."
    facts.status != NodeStatus.RUNNING ->
        "The node is not running, so the request was not sent. Start the node and try again."
    facts.owner != SocketOwner.OURS ->
        "The RPC port is no longer confirmed to be this node's, so the request was not sent. " +
            "Start the node again."
    !facts.shouldRun || facts.generation != facts.currentGeneration ->
        "The node was stopped or restarted while the request was being prepared, so it was not sent."
    !facts.listenerVerified ->
        "The RPC listener has not been verified as this node's, so the request was not sent."
    else -> "The node is not ready for this request, so it was not sent."
}

/**
 * A gated client and the lifecycle generation it was opened for.
 *
 * The caller keeps the generation and asks [NodeController.sensitiveReplyProblem]
 * once the long reply is back: a result that arrives after a stop or a new start
 * describes a chainstate that is no longer the one on screen, so it is reported
 * as a lost result instead of being published as this node's state.
 */
internal class SensitiveCall(val client: RpcClient, val generation: Long)

/** A sensitive client, or the reason there is none. */
internal class SensitiveOpen(val call: SensitiveCall?, val refusal: String?)
