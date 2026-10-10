package cash.z.ecc.android.sdk.ledger

import kotlin.coroutines.cancellation.CancellationException

/**
 * Thrown by a [LedgerApduTransport.exchange] that refused the exchange before sending anything — a
 * caller cancelled while it waited its turn behind another exchange on the same transport, say — so
 * the device never saw the command and nothing of its reply is outstanding.
 *
 * It is a [CancellationException]: the SDK's own commands report it to their caller as a cancellation.
 * What it changes is the cleanup: [LedgerDevice]'s commands and the signing ceremony close the
 * transport on a cancellation once a call holds the device, but leave it open for this one, since the
 * connection of whoever is using it is not theirs to close over a command that was never sent. A
 * transport implementation that admits one exchange at a time should throw this, rather than a plain
 * cancellation, when a waiting caller is cancelled before its exchange begins.
 */
class LedgerExchangeNotStartedException(
    cause: Throwable? = null
) : CancellationException("the transport refused the exchange before sending anything") {
    init {
        if (cause != null) {
            initCause(cause)
        }
    }
}
