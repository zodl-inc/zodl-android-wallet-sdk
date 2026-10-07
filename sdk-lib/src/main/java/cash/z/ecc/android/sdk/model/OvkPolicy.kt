package cash.z.ecc.android.sdk.model

/**
 * Which outgoing viewing key (OVK) a created transaction's shielded outputs are encrypted to.
 *
 * Whoever holds the OVK can decrypt those outputs later, learning their recipient addresses,
 * values and memos.
 */
enum class OvkPolicy {
    /**
     * Use the sending account's own OVK, so the wallet (and anyone who restores it from its
     * seed) can show what it sent. This is the default for ordinary sends.
     */
    Sender,

    /**
     * Use no OVK. Nobody holding the sending account's keys can later learn the recipients,
     * values or memos of the outputs, including the sending wallet itself, which then cannot
     * recover them from the chain if its local data is lost.
     *
     * Use this when the spending key is shared with someone the recipient must stay hidden
     * from, such as when sweeping a gift card, whose issuer can rederive the card's key.
     */
    Discard
}
