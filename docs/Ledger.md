# Ledger hardware wallets

The SDK signs transactions with a Ledger device running the Zcash app (3.6.0 or later; 3.9.3 is the
version the engine is pinned to) over Bluetooth LE. The protocol engine is
[`pczt_ledger`](https://github.com/zodl-inc/pczt-ledger), compiled into the SDK's native library. It
performs no I/O: the SDK drives it over a `LedgerApduTransport`, a request/response channel to one
device — `LedgerBluetoothTransport`, or a channel of the app's own.

All Ledger types are in `cash.z.ecc.android.sdk.ledger`; every failure is a
`cash.z.ecc.android.sdk.exception.LedgerException`.

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
