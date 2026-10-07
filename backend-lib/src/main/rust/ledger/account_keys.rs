//! What a Ledger device derives for an account, recomputed on the phone from the account's unified
//! full viewing key, so that the wallet can check the device's answers against its own keys.
//!
//! - [`check_ufvk_device_identity`] binds a UFVK exported for ZIP 32 account 0 to the identity the
//!   device answered before the export. The identity hashes the public key at
//!   `m/44'/coin'/0'/0/0`, which is the external address key at index 0 under the UFVK's P2PKH
//!   item. A UFVK of any other account carries no key on that path, so nothing here can bind it.

use pczt_ledger::pairing::DeviceIdentity;
use transparent::keys::{NonHardenedChildIndex, TransparentKeyScope};
use zcash_client_backend::keys::UnifiedFullViewingKey;
use zcash_protocol::consensus::Parameters;

use super::error::LedgerError;

/// Checks that `ufvk`, exported by the device for ZIP 32 account 0, belongs to the device whose
/// identity is `device_identity`.
///
/// The check derives the compressed public key at external index 0 from the UFVK's P2PKH item,
/// hashes it as [`DeviceIdentity::from_compressed_pubkey`] does, and compares the result with
/// `device_identity`. Call it only for account 0: the identity path is in account 0, so the UFVK
/// of another account never matches.
///
/// # Errors
///
/// - `DeviceMismatch` when the UFVK derives another identity.
/// - `MalformedReply` when the UFVK does not decode for `network`, or has no P2PKH item. The
///   Zcash app always exports a P2PKH item next to the Orchard one, so a UFVK without it did not
///   come from the app.
/// - `InvalidInput` when `device_identity` is not a device identity.
pub(crate) fn check_ufvk_device_identity<P: Parameters>(
    network: &P,
    ufvk: &str,
    device_identity: &str,
) -> Result<(), LedgerError> {
    let expected =
        DeviceIdentity::parse(device_identity).map_err(|e| LedgerError::from_identity(&e))?;
    let ufvk = UnifiedFullViewingKey::decode(network, ufvk).map_err(|_| {
        LedgerError::malformed_reply("the device's reply is not a valid viewing key")
    })?;
    let account_key = ufvk.p2pkh().ok_or_else(|| {
        LedgerError::malformed_reply(
            "the exported viewing key has no transparent component, which the Zcash app always \
             exports",
        )
    })?;
    let pubkey = account_key
        .derive_address_pubkey(TransparentKeyScope::EXTERNAL, NonHardenedChildIndex::ZERO)
        .map_err(|_| {
            LedgerError::malformed_reply(
                "the exported viewing key does not derive a transparent address key",
            )
        })?;
    if DeviceIdentity::from_compressed_pubkey(&pubkey.serialize()) == expected {
        Ok(())
    } else {
        Err(LedgerError::device_mismatch(
            "the exported viewing key does not belong to the device that answered the identity \
             probe; the key was discarded",
        ))
    }
}
