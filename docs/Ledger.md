# Ledger hardware wallets

The SDK signs transactions with a Ledger device running the Zcash app (3.6.0 or later; 3.9.3 is the
version the engine is pinned to). The protocol engine is [`pczt_ledger`](https://github.com/zodl-inc/pczt-ledger),
compiled into the SDK's native library. It performs no I/O: the SDK drives it over a
`LedgerApduTransport`, a request/response channel to one device that the app provides.

All Ledger types are in `cash.z.ecc.android.sdk.ledger`; every failure is a
`cash.z.ecc.android.sdk.exception.LedgerException`.

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

`pairAccount` reads the device's identity before and after exporting the viewing key and fails with
`LedgerException.DeviceMismatch` if they differ, so the stored binding always names the device whose
key was imported. It refuses an app that cannot sign PCZTs (`LedgerException.AppTooOld`) before
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

