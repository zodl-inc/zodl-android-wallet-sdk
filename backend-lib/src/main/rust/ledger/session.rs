//! Building a Ledger signing session from the wallet's own account data.
//!
//! The session needs more than the PCZT: the account's viewing keys (which make the device's
//! pre-flight checks exact), its seed fingerprint if it has one, and — for transparent spends and
//! transparent change — the derivation of every transparent address involved. All of that lives
//! in the wallet database, so it is read here rather than trusted from the caller.

use std::convert::Infallible;

use pczt::{
    Pczt,
    roles::{updater::Updater, verifier::Verifier},
};
use pczt_ledger::{
    Network as LedgerNetwork,
    pairing::{self, DeviceIdentity},
    session::{LedgerSignSession, SessionParams},
    shape::{ChangeInfo, HARDENED, TRANSPARENT_INTERNAL_SCOPE, p2pkh_hash},
    viewing::{AccountViewingKeys, TransparentAccountKey},
};
use tracing::debug;
use transparent::{
    address::TransparentAddress,
    keys::{AccountPubKey, NonHardenedChildIndex, TransparentKeyScope},
    pczt::Bip32Derivation,
};
use zcash_client_backend::{
    data_api::{Account, WalletRead, wallet::SignerView, wallet::redact_pczt_for_signer},
    wallet::TransparentAddressSource,
};
use zcash_client_sqlite::AccountUuid;

use super::error::LedgerError;

/// The BIP 44 purpose component, hardened on the wire.
const BIP44_PURPOSE: u32 = 44;

/// A signing session and how many commands a complete ceremony exchanges.
pub(crate) struct PreparedSession {
    pub(crate) session: LedgerSignSession,
    pub(crate) total_commands: usize,
}

/// Everything [`new_sign_session`] needs besides the wallet.
pub(crate) struct SessionRequest<'a> {
    pub(crate) network: LedgerNetwork,
    pub(crate) account: AccountUuid,
    pub(crate) pczt: &'a [u8],
    pub(crate) device_identity: &'a str,
    pub(crate) zip32_account: zip32::AccountId,
    pub(crate) firmware_version_reply: &'a [u8],
}

/// Builds a signing session for `request.pczt`, bound to the paired device, from the account's
/// own keys in `wallet`.
///
/// In order: the stored device identity is parsed; the device's `GET_FIRMWARE_VERSION` reply is
/// turned into its capabilities, and an app without PCZT support is refused; the account is
/// loaded and its UFVK must carry an Orchard key; the PCZT is redacted to
/// `SignerView::Full`; transparent inputs the wallet created without a derivation get one from
/// the wallet's address metadata; the transparent change output, if the wallet knows one, is
/// described; and the engine builds the session, which runs the full pre-flight.
pub(crate) fn new_sign_session<W>(
    wallet: &W,
    request: SessionRequest<'_>,
) -> Result<PreparedSession, LedgerError>
where
    W: WalletRead<AccountId = AccountUuid>,
{
    let device = DeviceIdentity::parse(request.device_identity)
        .map_err(|e| LedgerError::from_identity(&e))?;

    let caps = pairing::device_caps(request.firmware_version_reply)
        .map_err(|e| LedgerError::from_pairing(&e))?;
    if !caps.pczt() {
        return Err(LedgerError::app_too_old(caps.version()));
    }

    let account = wallet
        .get_account(request.account)
        .map_err(|_| LedgerError::internal("the wallet database could not be read"))?
        .ok_or_else(|| LedgerError::invalid_input("the account is not in this wallet"))?;
    let ufvk = account
        .ufvk()
        .ok_or_else(|| LedgerError::not_signable("the account has no unified full viewing key"))?;
    let orchard_fvk = ufvk.orchard().ok_or_else(|| {
        LedgerError::not_signable("the account's viewing key has no Orchard component")
    })?;
    let transparent_key = ufvk.p2pkh();
    let seed_fingerprint = account
        .source()
        .key_derivation()
        .map(|derivation| derivation.seed_fingerprint().to_bytes());

    let pczt = Pczt::parse(request.pczt)
        .map_err(|_| LedgerError::invalid_input("the transaction bytes are not a PCZT"))?;
    let redacted = redact_pczt_for_signer(&pczt, SignerView::Full);

    let redacted = match transparent_key {
        Some(key) => stamp_transparent_input_derivations(
            wallet,
            request.account,
            key,
            redacted,
            request.network,
            request.zip32_account,
            seed_fingerprint,
        )?,
        None => redacted,
    };

    let change = match transparent_key {
        Some(key) => transparent_change(wallet, request.account, key, &redacted)?,
        None => None,
    };

    let params = SessionParams::new(request.network, device, request.zip32_account)
        .with_viewing_keys(Some(AccountViewingKeys::from_orchard_fvk(
            orchard_fvk.clone(),
        )))
        .with_transparent_account_key(
            transparent_key.map(|key| TransparentAccountKey::from_account_pubkey(key.clone())),
        )
        .with_seed_fingerprint(seed_fingerprint)
        .with_change(change);

    let session = LedgerSignSession::new_from_pczt(redacted, caps, params)
        .map_err(|e| LedgerError::from_session(&e, false))?;
    let total_commands = total_commands(&session);
    debug!(
        total_commands,
        "Ledger signing session prepared for app v{}.{}.{}",
        caps.version().0,
        caps.version().1,
        caps.version().2
    );

    Ok(PreparedSession {
        session,
        total_commands,
    })
}

/// How many commands a complete ceremony exchanges, not counting `0x6901` resends: the two probes
/// the session opens with, every packet of the PCZT stream, and one signing command per real
/// Orchard and Ironwood spend and per transparent input.
///
/// This mirrors how the engine queues its commands and is used only to report progress, so a
/// drift here costs a progress bar its accuracy and nothing else.
fn total_commands(session: &LedgerSignSession) -> usize {
    const PROBES: usize = 2;
    let plan = session.plan();
    let stream = plan.stream().to_apdus().map_or(0, |apdus| apdus.len());
    PROBES
        + stream
        + plan.orchard_real_spends().len()
        + plan.ironwood_real_spends().len()
        + plan.stream().transparent_inputs().len()
}

/// The BIP 44 path component of a transparent key scope, for the three scopes a wallet derives
/// addresses under.
fn scope_component(scope: TransparentKeyScope) -> Option<u32> {
    match scope {
        TransparentKeyScope::EXTERNAL => Some(0),
        TransparentKeyScope::INTERNAL => Some(1),
        TransparentKeyScope::EPHEMERAL => Some(2),
        _ => None,
    }
}

/// The wallet's derivation of the P2PKH address `script` pays, if it is one of `account`'s derived
/// addresses.
fn derived_address<W>(
    wallet: &W,
    account: AccountUuid,
    script: &[u8],
) -> Result<Option<(TransparentKeyScope, NonHardenedChildIndex)>, LedgerError>
where
    W: WalletRead<AccountId = AccountUuid>,
{
    let Some(hash) = p2pkh_hash(script) else {
        return Ok(None);
    };
    let metadata = wallet
        .get_transparent_address_metadata(account, &TransparentAddress::PublicKeyHash(hash))
        .map_err(|_| LedgerError::internal("the wallet database could not be read"))?;
    Ok(match metadata.as_ref().map(|m| m.source()) {
        Some(TransparentAddressSource::Derived {
            scope,
            address_index,
        }) => Some((*scope, *address_index)),
        _ => None,
    })
}

/// Gives every transparent input that has no `bip32_derivation` the one the wallet knows for the
/// address it spends.
///
/// `create_pczt_from_proposal` stamps input derivations only for an account with a ZIP 32
/// derivation of its own, and an account imported from a Ledger's UFVK has none, so without this
/// every shielding transaction from such an account would be refused by the engine (a transparent
/// input must carry exactly one derivation). The path is the account's BIP 44 path at the
/// address's scope and index, and the seed fingerprint is the account's, or zeros — the same
/// placeholder the engine's shaping pass stamps on shielded actions, which the firmware reads and
/// never checks.
///
/// Nothing here is taken on trust by the engine: it re-derives each input's pubkey from its path
/// under the account's transparent key and requires it to pay the input's script before the first
/// APDU. An input the wallet has no derivation for is left alone and refused there.
fn stamp_transparent_input_derivations<W>(
    wallet: &W,
    account: AccountUuid,
    key: &AccountPubKey,
    pczt: Pczt,
    network: LedgerNetwork,
    zip32_account: zip32::AccountId,
    seed_fingerprint: Option<[u8; 32]>,
) -> Result<Pczt, LedgerError>
where
    W: WalletRead<AccountId = AccountUuid>,
{
    // `bip32_derivation` is readable only through the parsed bundle.
    let mut unstamped = Vec::new();
    let pczt = Verifier::new(pczt)
        .with_transparent::<Infallible, _>(|bundle| {
            unstamped = bundle
                .inputs()
                .iter()
                .enumerate()
                .filter(|(_, input)| input.bip32_derivation().is_empty())
                .map(|(index, _)| index)
                .collect();
            Ok(())
        })
        .map_err(|_| LedgerError::not_signable("the transparent bundle does not parse"))?
        .finish();

    let mut stamps = Vec::new();
    for index in unstamped {
        let Some(script) = pczt
            .transparent()
            .inputs()
            .get(index)
            .map(|input| input.script_pubkey())
        else {
            continue;
        };
        let Some((scope, address_index)) = derived_address(wallet, account, script)? else {
            continue;
        };
        let (Some(scope_index), Ok(pubkey)) = (
            scope_component(scope),
            key.derive_address_pubkey(scope, address_index),
        ) else {
            continue;
        };
        let path = vec![
            BIP44_PURPOSE | HARDENED,
            network.coin_type() | HARDENED,
            u32::from(zip32_account) | HARDENED,
            scope_index,
            address_index.index(),
        ];
        let derivation = Bip32Derivation::parse(seed_fingerprint.unwrap_or([0; 32]), path)
            .map_err(|_| LedgerError::internal("a transparent input path could not be built"))?;
        stamps.push((index, pubkey.serialize(), derivation));
    }

    if stamps.is_empty() {
        return Ok(pczt);
    }
    debug!(
        inputs = stamps.len(),
        "Stamping transparent input derivations from the wallet"
    );
    Updater::new(pczt)
        .update_transparent_with(|mut updater| {
            for (index, pubkey, derivation) in stamps {
                updater.update_input_with(index, |mut input| {
                    input.set_bip32_derivation(pubkey, derivation);
                    Ok(())
                })?;
            }
            Ok(())
        })
        .map(Updater::finish)
        .map_err(|_| LedgerError::internal("the transparent input derivations could not be set"))
}

/// Describes the transaction's transparent change output, if exactly one output pays an address
/// on the account's internal (change) chain.
///
/// Without it the device shows the wallet's own change as a third-party recipient. The zodl
/// proposal code sends change to shielded pools, so a transaction it builds carries no transparent
/// change today; the lookup still runs, so that a transaction that does is reviewed correctly
/// rather than misleadingly. Two or more such outputs describe nothing: the device accepts one
/// change output, and the engine's pre-flight names the problem.
fn transparent_change<W>(
    wallet: &W,
    account: AccountUuid,
    key: &AccountPubKey,
    pczt: &Pczt,
) -> Result<Option<ChangeInfo>, LedgerError>
where
    W: WalletRead<AccountId = AccountUuid>,
{
    let mut candidates = Vec::new();
    for (index, output) in pczt.transparent().outputs().iter().enumerate() {
        if let Some((TransparentKeyScope::INTERNAL, address_index)) =
            derived_address(wallet, account, output.script_pubkey())?
        {
            candidates.push((index, address_index, output.script_pubkey()));
        }
    }
    match candidates.as_slice() {
        [(output_index, address_index, script)] => ChangeInfo::derive(
            key,
            TRANSPARENT_INTERNAL_SCOPE,
            *address_index,
            *output_index,
            script,
        )
        .map(Some)
        .map_err(|e| LedgerError::not_signable(e.to_string())),
        _ => Ok(None),
    }
}
