# Ledger hardware wallets

The SDK signs transactions with a Ledger device running the Zcash app (3.6.0 or later; 3.9.3 is the
version the engine is pinned to) over Bluetooth LE. The protocol engine is
[`pczt_ledger`](https://github.com/zodl-inc/pczt-ledger), compiled into the SDK's native library. It
performs no I/O: the SDK drives it over a `LedgerApduTransport`, a request/response channel to one
device — `LedgerBluetoothTransport`, or a channel of the app's own.

All Ledger types are in `cash.z.ecc.android.sdk.ledger`. Failures are
`cash.z.ecc.android.sdk.exception.LedgerException`s, with the exceptions listed under
[Errors](#errors).

## Connecting over Bluetooth LE

`LedgerBluetoothScanner` finds devices and `LedgerBluetoothTransport` connects to one; USB is not
supported. Nano X, Stax, Flex and Nano Gen5 speak Bluetooth LE.

```kotlin
val scanner = LedgerBluetoothScanner(context)
val device = scanner.devices().first { it.isNotEmpty() }.first() // or collect and let the user pick
val transport = scanner.connect(device)
try {
    // LedgerDevice.new(transport, network) and/or synchronizer.signPcztWithLedger(..., transport)
} finally {
    transport.close()
}
```

The first connection bonds the phone with the device: Android shows its pairing prompt and the device
shows a code for the user to confirm. A declined pairing is `LedgerException.PairingRefused`; if the
device was reset or paired with another phone, the user has to remove it from the phone's Bluetooth
settings first. A device that is connected to another phone or app does not advertise and is not found.

### What the app has to declare and request

The SDK's manifest declares no Bluetooth permission, so apps that never use a Ledger do not acquire
them. An app that does declares:

```xml
<!-- API 31+ -->
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="s" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<!-- API 30 and earlier -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

and requests `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (API 31+) or `ACCESS_FINE_LOCATION` (API 30 and
earlier) at runtime before scanning. `neverForLocation` is accurate: scan results are used only to find
Ledger devices. Missing permissions fail with `LedgerException.BluetoothUnauthorized`
(`missingPermissions` names them); Bluetooth being off is `LedgerException.BluetoothDisabled`, and a
phone without Bluetooth LE is `LedgerException.BluetoothUnavailable`.

### Transport rules

One exchange runs at a time. An exchange that fails — a timeout, a disconnect, a reply that does not
reassemble, a cancellation — closes the transport, and every later exchange fails with
`LedgerException.Disconnected`: the device's reply to the failed command may still arrive, and must not
be taken for the answer to the next one. Connect again to continue. The device's identifier (its
Bluetooth address) is a stable hardware identifier; do not log it or send it anywhere.

## Opening the Zcash app

A device returns to its dashboard after its first Bluetooth pairing with the phone, and every Zcash
command then fails with `LedgerException.WrongApp`. Call `LedgerZcashApp.ensureZcashAppOpen(transport,
reconnect)` before `LedgerDevice.new`: it does nothing beyond one query when the Zcash app is already
open, and otherwise closes any other app and asks the device to open the Zcash app, which the user
confirms on the device.

The Bluetooth link may drop while the device switches apps, and `ensureZcashAppOpen` then replaces it
with a transport from `reconnect`. Build on the transport it **returns**, not on the one passed in: the
original may already be closed after a link failure.

```kotlin
val connected = scanner.connect(ledger)
val transport =
    try {
        LedgerZcashApp.ensureZcashAppOpen(connected) { scanner.connect(ledger) }
    } catch (e: LedgerException) {
        connected.close()
        throw e
    }
val device = LedgerDevice.new(transport, synchronizer.network)
```

The app closes the transport returned, and the one it passed in if the call fails; a transport opened
through `reconnect` is closed by the SDK when the call fails. While the device switches, each
reconnect has 10 seconds, and a failed one is tried again after 500 ms, then after twice the previous
wait (at most 2 s), for 10 seconds from the first failure. The call itself has no overall timeout: the
open command waits for the user's confirmation on the device, so bound the whole call with a timeout of
the app's own if it needs one.

## Pairing an account

```kotlin
val device = LedgerDevice.new(transport, synchronizer.network)
val pairing = device.pairAccount(Zip32AccountIndex.new(0)) // the user approves the export on the device

// A spending account with no ZIP 32 derivation, under Account.LEDGER_KEY_SOURCE.
val account = synchronizer.importAccountByUfvk(pairing.accountImportSetup("Ledger", birthday))

// Persist next to the account:
val deviceIdentity = pairing.binding.deviceIdentity.encoding
val zip32AccountIndex = pairing.binding.zip32AccountIndex.index
```

The reads before the export (the app version and the device's identity) answer at once on a healthy
link, so each gets `readTimeout` (default `LedgerDevice.DEFAULT_PAIRING_READ_TIMEOUT`, 10 s) rather than
the engine's two minutes. Pass `reconnect`, a function that opens a fresh connection to the same device,
and a read that fails on the connection (`Timeout`, `Disconnected`, `ConnectionFailed`,
`DeviceNotFound`) is asked once more over a new transport before anything else is sent; a second failure
propagates. Nothing is retried once the export command has been sent.

```kotlin
val device = LedgerDevice.new(scanner.connect(ledger), synchronizer.network)
try {
    val pairing = device.pairAccount(Zip32AccountIndex.new(0), reconnect = { scanner.connect(ledger) })
} finally {
    device.transport.close() // the reconnected transport, if pairAccount reconnected
}
```

The app owns every transport, the reconnected ones included: `device.transport` is the one the device
talks over now, and the app closes each transport it opened (closing is idempotent; the SDK has already
closed any whose exchange failed).

`pairAccount` reads the device's identity, and asks the user to approve the viewing key export on the
device. The identity is read once, before the export: the Zcash app leaves a status screen up after
the export and drops the next command until the user dismisses it, and a transport speaks to exactly
one peripheral, so the device that answered the probe is the device that exported the key. It refuses an app that cannot sign PCZTs (`LedgerException.AppTooOld`) before
exporting anything.

The device identity is a hash of the public key at `m/44'/coin'/0'/0/0`. It is not secret, but it is
linkable: once that address has spent on chain, anyone can match the identity to it. Store it as you
store the account's addresses and never log it. Restore it with `LedgerDeviceIdentity.new(encoding)`.

An account imported this way has no ZIP 32 derivation in the wallet: the device never reveals its seed
fingerprint. The SDK supplies the derivation paths the device needs from the binding's account index
and the wallet's own address metadata.

## Signing

```
proposal -> createPcztFromProposal -> pczt
  |- addProofsToPczt(pczt)                                          -> pcztWithProofs
  `- signPcztWithLedger(pczt, account.accountUuid, binding, transport) -> pcztWithSignatures
createTransactionFromPczt(pcztWithProofs, pcztWithSignatures) -> submit
```

Proving and signing work on the same PCZT and can run concurrently. The Ledger-signed PCZT carries
more than signatures (derivation paths for every action, the transparent change marking, the
`hash160` preimages of transparent inputs); the PCZT Combiner inside `createTransactionFromPczt` merges
all of it with the proven PCZT.

Before anything of the transaction is sent, the ceremony:

1. reads the Zcash app version and builds the signing session for it;
2. checks, with the account's viewing keys from the wallet, everything the device would refuse —
   Sapling funds, too many outputs to review, unsupported scripts — and fails with
   `LedgerException.TransactionNotSignable` naming the rule;
3. confirms the connected device's identity is the binding's (`LedgerException.DeviceMismatch`
   otherwise).

Then the transaction is streamed, the user reviews it on the device (`LedgerSigningProgress.AwaitingReviewOnDevice`;
no timeout applies from here), and the device signs. Every signature is verified before the PCZT is
returned.

The device receives the whole transaction — recipients, amounts, memos and the randomness of every
shielded action — and shows the user its outputs. It never leaves the phone for any other destination.

### Change outputs

The Ledger Zcash app signs exactly one change output per transaction. A PCZT with two or more change
outputs fails with `LedgerException.TransactionNotSignable` before anything is sent to the device.
`Synchronizer.proposeTransfer`, `Synchronizer.proposeFulfillingPaymentUri` and
`Synchronizer.proposeShielding` therefore build at most one change output for an account whose key
source is `Account.LEDGER_KEY_SOURCE` (compared case-insensitively). Every other account keeps
splitting change into up to four outputs of at least 0.1 ZEC.

### Failures

- A failed exchange (timeout, disconnect) or a cancelled call closes the transport; open a new one.
- A refusal by the device leaves the transport open. When `LedgerException.isRestartable` is true (the
  user rejected the review, a transient device condition such as a locked device), run the ceremony
  again with the same PCZT.
- `LedgerException.DerivationBudgetExhausted`: the Zcash app limits Orchard key derivations per run;
  the user has to close and reopen the app.

## Errors

Every `LedgerException` carries `isRestartable`: whether starting the whole operation again (a new
pairing, a new signing session over the same PCZT) can succeed. It never means "resend the last
command". A refusal by the device leaves the transport open; a failure on the transport closes it.

| Exception | When | Restartable | Suggested app action |
|---|---|---|---|
| `UserRejected` | The user declined on the device: the viewing key export, the transaction review, or the address. | As reported | Say it was declined; when restartable, offer to try again over the same transport. |
| `WrongApp` | The Zcash app is not running: the device shows its dashboard (`0x6E01`), runs another app (`0x6511`), or runs a version that does not know the command (`0x6E00`, `0x6D00`). `statusWord` says which. | Yes | Ask the user to open the Zcash app on the device, then start again. |
| `AppNotInstalled` | `ensureZcashAppOpen` asked the device to open the Zcash app and it has none installed. | No | Ask the user to install the Zcash app with Ledger Live, then start again. |
| `AppOpenRejected` | The user declined opening the Zcash app on the device during `ensureZcashAppOpen`. | Yes | Offer to try again; the device asks the user once more. |
| `AppTooOld` | The Zcash app predates PCZT signing (checked before anything is exported), or the Ironwood pool a version 6 transaction needs. | No | Ask the user to update the Zcash app with Ledger Live. |
| `DeviceMismatch` | Signing found that the connected device is not the one the account was paired with. Pairing reads the identity once and no longer raises it. Nothing of the transaction was sent. | No | Ask the user to connect the paired device. |
| `CapsMismatch` | The Zcash app was updated or swapped during the operation. Nothing of the transaction was sent. | No | Connect again and start over. |
| `DerivationBudgetExhausted` | The Zcash app's per-run Orchard key derivation budget is spent. | Yes | Ask the user to close and reopen the Zcash app, then start again. |
| `DeviceRefused` | Any other refusal; `statusWord` and `isTransient` describe it (a locked device is transient). | As reported | When restartable, ask the user to unlock the device and try again; otherwise report the status word. |
| `TransactionNotSignable` | A rule refuses the transaction before anything is sent: Sapling funds, too many outputs to review, an account without an Orchard key. `reason` names it. | No | Explain `reason`; the user has to change the transaction (for example, shield or migrate the funds first). |
| `MalformedReply` | A reply did not have the promised shape, did not reassemble, or a returned signature did not verify. | No | Connect again and start over; if it persists, report it. |
| `InvalidInput` | A value passed in was refused before any device I/O: a stored identity, an index, an unknown account. | No | A bug or corrupt stored data in the app; do not retry unchanged. |
| `BluetoothUnavailable` | The phone has no Bluetooth LE, or a scan could not be started (`scanErrorCode`). | No | Hide the Bluetooth option, or retry the scan later. |
| `BluetoothUnauthorized` | A Bluetooth permission is not granted (`missingPermissions`), or the Bluetooth stack refused a call for lack of one while connecting. | No | Request the permissions, then scan or connect again. |
| `BluetoothDisabled` | Bluetooth is off. | No | Ask the user to turn Bluetooth on. |
| `DeviceNotFound` | The identifier is not a Bluetooth device, or the device offers no Ledger service. | No | Scan again. |
| `ConnectionFailed` | The connection could not be set up: the connect timeout ran out, service discovery or subscribing failed, or the Ledger MTU handshake's write or answer did not complete in time. | No | Ask the user to make sure the device is on, unlocked, nearby and not connected to another phone; connect again. |
| `Disconnected` | The device disconnected, the transport was closed, or an earlier exchange failed and left it unusable. | No | Connect again and start over. |
| `PairingRefused` | Bluetooth pairing was declined, failed, or did not complete before the connect timeout; or the device refused the first write on the link. | No | Ask the user to accept the pairing on both the phone and the device; if the device was reset or paired elsewhere, remove it from the phone's Bluetooth settings first. |
| `Timeout` | The device did not answer an exchange in time. The transport is closed. | No | Connect again and start over; `pairAccount` with `reconnect` does this once for the reads before the export. |
| `Internal` | An unexpected failure on this side of the transport. | No | Report it. |

"No" means the flag is `false`: starting again unchanged is not expected to help. For the transport
failures (`ConnectionFailed`, `Disconnected`, `Timeout`, `DeviceNotFound`), connecting again is the
recovery, and a new operation over the new transport can then succeed.

Not every failure is a `LedgerException`:

- `CancellationException` from cancelling the calling coroutine, a timeout of the caller's own
  included, propagates as it is (and closes the transport of an exchange in progress).
- A transport of the app's own, `onProgress`, and a `pairAccount` `reconnect` function throw whatever
  they throw; the SDK rethrows it unchanged.
- Errors from loading the SDK's native library (`UnsatisfiedLinkError` and other `Error`s) are not
  wrapped.

## Verifying an address on the device

`LedgerDevice.displayUnifiedAddress(zip32AccountIndex, transparentAddressIndex)` shows an address on the
device and returns it once the user confirms. The returned address carries only the account's Orchard
receiver at diversifier index 0 — whatever `transparentAddressIndex` is, which only selects the
transparent address shown next to it on screen. Compare it with a unified address built from that
Orchard receiver alone; the account's full unified address carries a transparent receiver too and
never matches.

## Pool migration

The Orchard-to-Ironwood migration signs its transactions in-process or with Keystone batch signing.
There is no Ledger path for it: a migration run is sized for those signers only, and a Ledger account
cannot sign one.

## Manual test plan (testnet)

Real devices cannot be exercised in CI. Before a release, on a testnet build, with a Nano X, a Stax and a
Flex running the Zcash app 3.6.0 or later (3.9.3 preferred) with a testnet-configured seed:

1. With Bluetooth permissions not granted, scanning fails with `BluetoothUnauthorized`; with Bluetooth
   off, `BluetoothDisabled`.
2. Grant permissions, open the Zcash app on the device, scan: the device appears with its model.
3. Connect for the first time: the OS pairing prompt and the device's code appear; decline once
   (`PairingRefused`), then accept.
4. Pair account 0: approve the viewing key export on the device. Decline once first (`UserRejected`).
   Import it with `pairing.accountImportSetup(...)` and sync; the account's balance matches the device's.
5. `displayUnifiedAddress`: the address on the device matches the returned one; reject once.
6. Receive testnet funds to the account's Orchard address and to its transparent address.
7. Send from Orchard to another wallet: review on the device (recipient, amount, fee), approve; the
   transaction confirms. Repeat and reject the review: `UserRejected` with `isRestartable`, then sign
   again with the same PCZT on the same transport.
8. Shield the transparent funds: the device shows no third-party output; approve; it confirms.
9. With a second Ledger holding a different seed, sign with the first device's binding:
   `DeviceMismatch`, and nothing is shown on the second device.
10. Close the Zcash app mid-stream: `WrongApp` or `DeviceRefused`. Walk out of range mid-review:
    `Disconnected`; reconnect and sign again.
11. A transaction with Sapling inputs, or more outputs than the device reviews, fails with
    `TransactionNotSignable` before anything is sent.
