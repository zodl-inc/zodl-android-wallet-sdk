//! ZIP 316 revision 0 encodings of the unified addresses and viewing keys handed to Kotlin.
//!
//! On the NU7 pre-release crates `zcash_keys` encodes at revision 2. These helpers keep the
//! strings that cross the JNI boundary at revision 0, with every item they carried before.

use zcash_address::{
    ToAddress as _, ZcashAddress,
    unified::{Container as _, Encoding as _, Fvk, Ivk, Receiver, Revision, Ufvk, Uitem, Uivk},
};
use zcash_client_backend::{
    address::UnifiedAddress,
    keys::{UnifiedFullViewingKey, UnifiedIncomingViewingKey},
};
use zcash_protocol::consensus::{NetworkType, Parameters};

/// Encodes a unified full viewing key at ZIP 316 revision 0, for the given network, with every
/// item `zcash_keys`'s own encoder gave it.
///
/// `zcash_keys`'s own [`UnifiedFullViewingKey::encode`] produces revision 2 unconditionally on
/// the NU7 crates. Hardware wallets still produce revision 0, and values an app stored before
/// the move are revision 0, so this reproduces the string the SDK handed Kotlin before the move. `zcash_keys`'s own item builder (`to_ufvk`) is private,
/// so this takes the string `encode` already produced, decodes it back with `zcash_address`, and
/// re-encodes the same parsed items — whatever they are, known or not — at revision 0.
///
/// Falls back to the revision 2 string unchanged for a key revision 0 cannot hold: one with
/// expiry metadata, a P2SH viewing-key item (`Fvk::P2sh`, specified only for revision 2), or no
/// Orchard or Sapling item (revision 0 requires a shielded item). It does the same, rather than
/// panic, when that revision 2 string does not decode back: a revision 0 key may carry a
/// typecode 0x01 item, which revision 0 leaves unrecognised, and `zcash_keys` writes it back
/// out at revision 2, where 0x01 is a P2SH item its data need not be valid as.
pub(crate) fn encode_ufvk_r0(ufvk: &UnifiedFullViewingKey, network: &impl Parameters) -> String {
    let r2 = ufvk.encode(network);
    let Ok((_, _, container)) = Ufvk::decode(&r2) else {
        return r2;
    };
    let items = container.items_as_parsed();

    let has_metadata = items.iter().any(|item| matches!(item, Uitem::Metadata(_)));
    let has_p2sh_item = items
        .iter()
        .any(|item| matches!(item, Uitem::Data(Fvk::P2sh(_))));
    let has_shielded_item = items.iter().any(|item| {
        matches!(
            item,
            Uitem::Data(Fvk::Orchard(_)) | Uitem::Data(Fvk::Sapling(_))
        )
    });

    if has_metadata || has_p2sh_item || !has_shielded_item {
        return r2;
    }

    Ufvk::try_from_items(Revision::R0, items.to_vec())
        .map(|r0| r0.encode(&network.network_type()))
        .unwrap_or(r2)
}

/// Encodes a unified incoming viewing key at ZIP 316 revision 0, for the given network. See
/// [`encode_ufvk_r0`]: same method (`encode`, decode, re-encode), same revision 2 fallback,
/// including for a revision 2 string that does not decode back.
pub(crate) fn encode_uivk_r0(
    uivk: &UnifiedIncomingViewingKey,
    network: &impl Parameters,
) -> String {
    let r2 = uivk.encode(network);
    let Ok((_, _, container)) = Uivk::decode(&r2) else {
        return r2;
    };
    let items = container.items_as_parsed();

    let has_metadata = items.iter().any(|item| matches!(item, Uitem::Metadata(_)));
    let has_p2sh_item = items
        .iter()
        .any(|item| matches!(item, Uitem::Data(Ivk::P2sh(_))));
    let has_shielded_item = items.iter().any(|item| {
        matches!(
            item,
            Uitem::Data(Ivk::Orchard(_)) | Uitem::Data(Ivk::Sapling(_))
        )
    });

    if has_metadata || has_p2sh_item || !has_shielded_item {
        return r2;
    }

    Uivk::try_from_items(Revision::R0, items.to_vec())
        .map(|r0| r0.encode(&network.network_type()))
        .unwrap_or(r2)
}

/// Encodes a unified address at ZIP 316 revision 0, for the given network, with every receiver
/// `zcash_keys`'s own encoder gave it.
///
/// `zcash_keys`'s own [`UnifiedAddress::encode`]/[`UnifiedAddress::to_zcash_address`] produce
/// revision 2 unconditionally on the NU7 crates, and drop the transparent receiver whenever a
/// shielded one is present. `getTransparentReceiverForUnifiedAddress` takes the transparent
/// receiver back out of this string, so that drop breaks it; hardware wallets and stored values
/// are also still revision 0. This reproduces the string the SDK handed Kotlin before the move: it takes `zcash_keys`'s own
/// [`UnifiedAddress::encode_receiver_preserving`] string — revision 2, but with every receiver,
/// including transparent, never dropped — decodes it back with `zcash_address`, and re-encodes
/// the same parsed items at revision 0.
///
/// Falls back to that revision 2, receiver-preserving string unchanged for an address revision 0
/// cannot hold: one with expiry metadata, or no Orchard or Sapling receiver (revision 0 requires
/// a shielded receiver). A P2SH *receiver* (an ordinary transparent address) is unaffected by
/// either check; only a P2SH *viewing-key item*, which only a UFVK or UIVK can carry, is
/// revision-2-only. Like [`encode_ufvk_r0`], it also returns that string, rather than panic, if
/// it does not decode back.
pub(crate) fn encode_unified_address_r0(
    address: &UnifiedAddress,
    network: &impl Parameters,
) -> String {
    let r2 = address.encode_receiver_preserving(network);
    let Ok((_, _, container)) = zcash_address::unified::Address::decode(&r2) else {
        return r2;
    };
    let items = container.items_as_parsed();

    let has_metadata = items.iter().any(|item| matches!(item, Uitem::Metadata(_)));
    let has_shielded_item = items.iter().any(|item| {
        matches!(
            item,
            Uitem::Data(Receiver::Orchard(_)) | Uitem::Data(Receiver::Sapling(_))
        )
    });

    if has_metadata || !has_shielded_item {
        return r2;
    }

    zcash_address::unified::Address::try_from_items(Revision::R0, items.to_vec())
        .map(|r0| r0.encode(&network.network_type()))
        .unwrap_or(r2)
}

/// A ZIP 316 revision 0 unified address holding only the given Orchard receiver, for the given
/// network: the recipient both Ironwood migration flows give a transfer to the account's own
/// internal receiver.
///
/// `zcash_keys`'s `Receiver::Orchard(..).to_zcash_address(..)` encodes at revision 2
/// unconditionally on the NU7 crates. The recipient is written to the wallet
/// (`sent_notes`, and from there `v_tx_outputs.to_address`) and the app reads it back for the
/// transaction history, so it stays at revision 0, as it was before the move.
pub(crate) fn orchard_only_address_r0(
    receiver: &orchard::Address,
    network: NetworkType,
) -> anyhow::Result<ZcashAddress> {
    let address = zcash_address::unified::Address::try_from_items(
        Revision::R0,
        vec![Uitem::Data(Receiver::Orchard(
            receiver.to_raw_address_bytes(),
        ))],
    )
    .map_err(|e| anyhow::anyhow!("Unable to construct the migration recipient: {e}"))?;
    Ok(ZcashAddress::from_unified(network, address))
}

#[cfg(test)]
mod tests {
    use transparent::address::TransparentAddress;
    use zcash_address::unified;
    use zcash_client_backend::keys::{UnifiedAddressRequest, UnifiedSpendingKey};
    use zcash_protocol::consensus::{MAIN_NETWORK, TEST_NETWORK};

    use super::*;

    /// A UFVK, a UIVK and a UA for a seed-derived key encode with revision-0 prefixes, and each
    /// decodes back to the same value: `zcash_keys`'s own (revision-2) encoder of the decoded
    /// value agrees with its encoder of the value built directly.
    fn assert_revision_0_round_trip(
        network: &impl Parameters,
        ua_prefix: &str,
        ufvk_prefix: &str,
        uivk_prefix: &str,
    ) {
        let usk = UnifiedSpendingKey::from_seed(network, &[7u8; 32], zip32::AccountId::ZERO)
            .expect("a spending key derives");
        let ufvk = usk.to_unified_full_viewing_key();
        let uivk = ufvk.to_unified_incoming_viewing_key();
        let (ua, _) = ufvk
            .default_address(UnifiedAddressRequest::AllAvailableKeys)
            .expect("an address derives");

        let ufvk_str = super::encode_ufvk_r0(&ufvk, network);
        let uivk_str = super::encode_uivk_r0(&uivk, network);
        let ua_str = super::encode_unified_address_r0(&ua, network);

        assert!(
            ufvk_str.starts_with(ufvk_prefix),
            "{ufvk_str} must start with {ufvk_prefix}"
        );
        assert!(
            uivk_str.starts_with(uivk_prefix),
            "{uivk_str} must start with {uivk_prefix}"
        );
        assert!(
            ua_str.starts_with(ua_prefix),
            "{ua_str} must start with {ua_prefix}"
        );

        let decoded_ufvk = UnifiedFullViewingKey::decode(network, &ufvk_str).expect("ufvk decodes");
        assert_eq!(decoded_ufvk.encode(network), ufvk.encode(network));

        let decoded_uivk =
            UnifiedIncomingViewingKey::decode(network, &uivk_str).expect("uivk decodes");
        assert_eq!(decoded_uivk.encode(network), uivk.encode(network));

        let (_, _, raw_ua) = unified::Address::decode(&ua_str).expect("ua decodes");
        let decoded_ua = UnifiedAddress::try_from(raw_ua).expect("converts to a UnifiedAddress");
        assert_eq!(decoded_ua.encode(network), ua.encode(network));
        // `zcash_keys`'s own encoder drops the transparent receiver, so the comparison above
        // cannot see it: check that the revision 0 string kept it.
        assert_eq!(decoded_ua.transparent(), ua.transparent());
        assert!(decoded_ua.transparent().is_some());
    }

    #[test]
    fn ufvk_uivk_and_ua_encode_at_revision_0_and_round_trip_on_mainnet() {
        assert_revision_0_round_trip(&MAIN_NETWORK, "u1", "uview1", "uivk1");
    }

    #[test]
    fn ufvk_uivk_and_ua_encode_at_revision_0_and_round_trip_on_testnet() {
        assert_revision_0_round_trip(&TEST_NETWORK, "utest1", "uviewtest1", "uivktest1");
    }

    /// Three rows of `librustzcash`'s own unified-address test vectors — the
    /// `TEST_VECTORS` table in `zcash_address`'s
    /// `src/kind/unified/address/test_vectors.rs` (sourced from
    /// `zcash/zcash-test-vectors`'s `unified_address.py`), revision 0, mainnet:
    /// a p2pkh+sapling address, a p2pkh+orchard address, and a
    /// p2pkh+sapling+orchard address.
    #[test]
    fn upstream_revision_0_vectors_round_trip_byte_for_byte() {
        let vectors = [
            "u1l8xunezsvhq8fgzfl7404m450nwnd76zshscn6nfys7vyz2ywyh4cc5daaq0c7q2su5lqfh23sp7fkf3kt27ve5948mzpfdvckzaect2jtte308mkwlycj2u0eac077wu70vqcetkxf",
            "u1snf9yr883aj2hm8pksp9aymnqdwzy42rpzuffevj35hhxeckays5pcpeq7vy2mtgzlcuc4mnh9443qnuyje0yx6h59angywka4v2ap6kchh2j96ezf9w0c0auyz3wwts2lx5gmk2sk9",
            "u19mzuf4l37ny393m59v4mxx4t3uyxkh7qpqjdfvlfk9f504cv9w4fpl7cql0kqvssz8jay8mgl8lnrtvg6yzh9pranjj963acc3h2z2qt7007du0lsmdf862dyy40c3wmt0kq35k5z836tfljgzsqtdsccchayfjpygqzkx24l77ga3ngfgskqddyepz8we7ny4ggmt7q48cgvgu57mz",
        ];

        for vector in vectors {
            let (_, revision, raw) = unified::Address::decode(vector).expect("vector decodes");
            assert_eq!(
                revision,
                unified::Revision::R0,
                "upstream vector must be revision 0"
            );
            let ua = UnifiedAddress::try_from(raw).expect("converts to a UnifiedAddress");
            let re_encoded = super::encode_unified_address_r0(&ua, &MAIN_NETWORK);
            assert_eq!(
                re_encoded, vector,
                "must reproduce the upstream vector exactly"
            );
        }
    }

    /// A transparent-only UFVK and a transparent-only unified address have no shielded item, so
    /// revision 0 cannot represent either (ZIP 316). `encode_ufvk_r0`/`encode_unified_address_r0`
    /// fall back to `zcash_keys`'s own revision 2 encoding for them instead of failing outright,
    /// so an imported transparent-only UFVK still encodes.
    #[test]
    fn transparent_only_values_fall_back_to_revision_2_instead_of_erroring() {
        let usk = UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO)
            .expect("a spending key derives");
        let ufvk = usk.to_unified_full_viewing_key();
        let (ua, _) = ufvk
            .default_address(UnifiedAddressRequest::AllAvailableKeys)
            .expect("an address derives");

        // A transparent-only UFVK built from the fixture key's own p2pkh item: revision 2 is
        // the only revision that can encode it standalone, so it has to start there.
        let p2pkh_fvk_bytes: [u8; 65] = ufvk
            .p2pkh()
            .expect("the fixture key has a transparent item")
            .serialize()
            .try_into()
            .expect("an account public key is 65 bytes");
        let transparent_only_ufvk_str = unified::Ufvk::try_from_items(
            unified::Revision::R2,
            vec![unified::Uitem::Data(unified::Fvk::P2pkh(p2pkh_fvk_bytes))],
        )
        .expect("a single transparent item is valid at revision 2")
        .encode(&MAIN_NETWORK.network_type());
        let transparent_only_ufvk =
            UnifiedFullViewingKey::decode(&MAIN_NETWORK, &transparent_only_ufvk_str)
                .expect("the fixture string decodes");

        let encoded_ufvk = super::encode_ufvk_r0(&transparent_only_ufvk, &MAIN_NETWORK);
        let (_, ufvk_revision, _) = unified::Ufvk::decode(&encoded_ufvk).expect("decodes");
        assert_eq!(
            ufvk_revision,
            unified::Revision::R2,
            "a transparent-only UFVK must fall back to revision 2 instead of failing: {encoded_ufvk}"
        );

        // Same shape, for the address: a transparent-only UA built from the fixture address's
        // own receiver, plus an unrelated unknown item purely to reach F4Jumble's 48-byte floor
        // (a lone 20-byte receiver, plus the 16-byte HRP padding, falls short of it at either
        // revision — nothing to do with revision 0 vs. 2).
        let receiver_item = match ua
            .transparent()
            .expect("the fixture address has a transparent receiver")
        {
            TransparentAddress::PublicKeyHash(data) => unified::Receiver::P2pkh(*data),
            TransparentAddress::ScriptHash(data) => unified::Receiver::P2sh(*data),
        };
        let transparent_only_ua_str = unified::Address::try_from_items(
            unified::Revision::R2,
            vec![
                unified::Uitem::Data(receiver_item),
                unified::Uitem::Data(unified::Receiver::Unknown {
                    typecode: 0xff00,
                    data: vec![0u8; 20],
                }),
            ],
        )
        .expect("a transparent receiver plus padding is valid at revision 2")
        .encode(&MAIN_NETWORK.network_type());
        let (_, _, raw_ua) =
            unified::Address::decode(&transparent_only_ua_str).expect("the fixture string decodes");
        let transparent_only_ua =
            UnifiedAddress::try_from(raw_ua).expect("converts to a UnifiedAddress");

        let encoded_ua = super::encode_unified_address_r0(&transparent_only_ua, &MAIN_NETWORK);
        let (_, ua_revision, _) = unified::Address::decode(&encoded_ua).expect("decodes");
        assert_eq!(
            ua_revision,
            unified::Revision::R2,
            "a transparent-only address must fall back to revision 2 instead of failing: {encoded_ua}"
        );
    }

    /// A data item of a typecode this crate does not interpret survives `encode_ufvk_r0`
    /// unchanged. `zcash_keys` keeps unknown items in a private field, so re-encoding the parsed
    /// items is the only construction that does not silently drop them.
    #[test]
    fn an_unknown_item_keeps_its_place_at_revision_0() {
        let usk = UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO)
            .expect("a spending key derives");
        let ufvk = usk.to_unified_full_viewing_key();
        let orchard_bytes = ufvk
            .orchard()
            .expect("the fixture key has an orchard item")
            .to_bytes();
        let sapling_bytes = ufvk
            .sapling()
            .expect("the fixture key has a sapling item")
            .to_bytes();

        let input = unified::Ufvk::try_from_items(
            unified::Revision::R0,
            vec![
                unified::Uitem::Data(unified::Fvk::Orchard(orchard_bytes)),
                unified::Uitem::Data(unified::Fvk::Sapling(sapling_bytes)),
                unified::Uitem::Data(unified::Fvk::Unknown {
                    typecode: 0xff00,
                    data: vec![1, 2, 3, 4],
                }),
            ],
        )
        .expect("a shielded item plus an unknown item is valid at revision 0")
        .encode(&MAIN_NETWORK.network_type());

        let decoded = UnifiedFullViewingKey::decode(&MAIN_NETWORK, &input)
            .expect("the fixture string decodes");
        let output = super::encode_ufvk_r0(&decoded, &MAIN_NETWORK);

        assert_eq!(output, input, "the unknown item must survive unchanged");
    }

    /// A transparent-only UIVK has no shielded item, so revision 0 cannot represent it — the
    /// same restriction as the transparent-only UFVK and address covered above.
    /// `encode_uivk_r0` falls back to `zcash_keys`'s own revision 2 encoding for it instead of
    /// failing outright.
    #[test]
    fn transparent_only_uivk_falls_back_to_revision_2_instead_of_erroring() {
        use transparent::keys::IncomingViewingKey as _;

        let usk = UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO)
            .expect("a spending key derives");
        let uivk = usk
            .to_unified_full_viewing_key()
            .to_unified_incoming_viewing_key();

        // A transparent-only UIVK built from the fixture key's own p2pkh item: revision 2 is
        // the only revision that can encode it standalone, so it has to start there.
        let p2pkh_ivk_bytes: [u8; 65] = uivk
            .p2pkh()
            .expect("the fixture key has a transparent item")
            .serialize()
            .try_into()
            .expect("an external ivk is 65 bytes");
        let transparent_only_uivk_str = unified::Uivk::try_from_items(
            unified::Revision::R2,
            vec![unified::Uitem::Data(unified::Ivk::P2pkh(p2pkh_ivk_bytes))],
        )
        .expect("a single transparent item is valid at revision 2")
        .encode(&MAIN_NETWORK.network_type());
        let transparent_only_uivk =
            UnifiedIncomingViewingKey::decode(&MAIN_NETWORK, &transparent_only_uivk_str)
                .expect("the fixture string decodes");

        let encoded = super::encode_uivk_r0(&transparent_only_uivk, &MAIN_NETWORK);
        assert_eq!(
            encoded,
            transparent_only_uivk.encode(&MAIN_NETWORK),
            "a transparent-only UIVK must fall back to zcash_keys's own revision 2 string"
        );
    }

    /// A unified address with an expiry-height metadata item is a value revision 0 cannot
    /// hold. `encode_unified_address_r0` falls back to `encode_receiver_preserving`'s revision
    /// 2 string for it instead of failing or silently dropping the metadata.
    #[test]
    fn ua_with_expiry_metadata_falls_back_to_revision_2_instead_of_dropping_it() {
        let usk = UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO)
            .expect("a spending key derives");
        let ufvk = usk.to_unified_full_viewing_key();
        let (ua, _) = ufvk
            .default_address(UnifiedAddressRequest::AllAvailableKeys)
            .expect("an address derives");

        let orchard_bytes = ua
            .orchard()
            .expect("the fixture address has an orchard receiver")
            .to_raw_address_bytes();
        let sapling_bytes = ua
            .sapling()
            .expect("the fixture address has a sapling receiver")
            .to_bytes();
        let transparent_item = match ua
            .transparent()
            .expect("the fixture address has a transparent receiver")
        {
            TransparentAddress::PublicKeyHash(data) => unified::Receiver::P2pkh(*data),
            TransparentAddress::ScriptHash(data) => unified::Receiver::P2sh(*data),
        };

        let with_expiry_str = unified::Address::try_from_items(
            unified::Revision::R2,
            vec![
                unified::Uitem::Data(unified::Receiver::Orchard(orchard_bytes)),
                unified::Uitem::Data(unified::Receiver::Sapling(sapling_bytes)),
                unified::Uitem::Data(transparent_item),
                unified::Uitem::Metadata(unified::MetadataItem::ExpiryHeight(2_000_000)),
            ],
        )
        .expect("the fixture's receivers plus an expiry height item are valid at revision 2")
        .encode(&MAIN_NETWORK.network_type());
        let (_, _, raw_ua) =
            unified::Address::decode(&with_expiry_str).expect("the fixture string decodes");
        let ua_with_expiry =
            UnifiedAddress::try_from(raw_ua).expect("converts to a UnifiedAddress");

        let encoded = super::encode_unified_address_r0(&ua_with_expiry, &MAIN_NETWORK);
        assert_eq!(
            encoded,
            ua_with_expiry.encode_receiver_preserving(&MAIN_NETWORK),
            "an address with expiry metadata must fall back to encode_receiver_preserving's string"
        );
    }

    /// A UFVK with an expiry-height metadata item, the key-level counterpart to the address
    /// case above. `zcash_keys`'s own decode reads `ExpiryHeight`/`ExpiryTime` items into
    /// dedicated fields rather than rejecting them, so this value is constructible the same
    /// way as the transparent-only fixtures elsewhere in this module; `encode_ufvk_r0` falls
    /// back to `zcash_keys`'s own revision 2 encoding for it, which keeps the metadata.
    #[test]
    fn ufvk_with_expiry_metadata_falls_back_to_revision_2_instead_of_dropping_it() {
        let usk = UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO)
            .expect("a spending key derives");
        let ufvk = usk.to_unified_full_viewing_key();
        let orchard_bytes = ufvk
            .orchard()
            .expect("the fixture key has an orchard item")
            .to_bytes();
        let sapling_bytes = ufvk
            .sapling()
            .expect("the fixture key has a sapling item")
            .to_bytes();

        let with_expiry_str = unified::Ufvk::try_from_items(
            unified::Revision::R2,
            vec![
                unified::Uitem::Data(unified::Fvk::Orchard(orchard_bytes)),
                unified::Uitem::Data(unified::Fvk::Sapling(sapling_bytes)),
                unified::Uitem::Metadata(unified::MetadataItem::ExpiryHeight(2_000_000)),
            ],
        )
        .expect("a shielded item plus an expiry height item is valid at revision 2")
        .encode(&MAIN_NETWORK.network_type());
        let ufvk_with_expiry = UnifiedFullViewingKey::decode(&MAIN_NETWORK, &with_expiry_str)
            .expect("zcash_keys accepts a UFVK carrying expiry metadata");

        let encoded = super::encode_ufvk_r0(&ufvk_with_expiry, &MAIN_NETWORK);
        assert_eq!(
            encoded,
            ufvk_with_expiry.encode(&MAIN_NETWORK),
            "a UFVK with expiry metadata must fall back to zcash_keys's own revision 2 string"
        );
    }

    /// A P2SH viewing-key item is revision-2-only (ZIP 316), so `encode_ufvk_r0` must fall back
    /// to `zcash_keys`'s own encoding for it instead of trying revision 0. Building such a key
    /// here would need `secp256k1` 0.33 for `P2shFullViewingKey`, a version this crate cannot
    /// name without its own direct dependency on it, so this takes a ready-made one instead: the
    /// first vector with a P2SH item (Orchard, no expiry) from `zcash_keys-0.17.0-pre.0`'s
    /// `src/keys/test_vectors/unified_viewing_keys_r2.rs`, itself sourced from
    /// `zcash/zcash-test-vectors`'s `unified_viewing_keys_r2.py`.
    #[test]
    fn p2sh_ufvk_falls_back_to_revision_2_instead_of_erroring() {
        let p2sh_ufvk_str = "uvf1td5cvtvcvlmxqgaxus3m2m745ykzk5gncx4z7x5zrk9l256qjfmfg6n8efrg0htq5sac4kg54aejsepkcl6hgaqyk6dlstk4fpnkmxjcvl72zh97r3fz7wa02wt4mmcayn6ukzck9d96tcqmkk2j5rzdr44f6haa9v7qzqsserruscf6lqum38xr8k84yesq8ecxa25aqg04uh3gweg8d25055v9z5u4c75mtt06g57eu8mq8eldg2gn9su23hkv0jrq7asc6tad5kaxs4r330uyf8qmydxkhe0nmtx26nux84sftau7072hp72qhfdzyhwu3ndhdq4glu8zjxp7srdaep9nn96swfl8td478n3jey0r73x5du50gjqr37ta39dpg25vkqw0q59pvfxp4xhl8392keskzl202njjeh677tphgcuayn09kl9vkxqkcy2pq7tar8vcjgewvem8hptycrd7v659lgzsnr85udpx7kv785m4a4259wr0snunfndya9x44m0zqt3py4x9md4vcdrj628qxmam6t836dp5zwsv744ay";

        let decoded = UnifiedFullViewingKey::decode(&MAIN_NETWORK, p2sh_ufvk_str)
            .expect("the upstream vector decodes");

        let encoded = super::encode_ufvk_r0(&decoded, &MAIN_NETWORK);
        assert_eq!(
            encoded,
            decoded.encode(&MAIN_NETWORK),
            "a P2SH item must fall back to zcash_keys's own revision 2 string"
        );
        assert_eq!(
            encoded, p2sh_ufvk_str,
            "must reproduce the upstream vector exactly"
        );
    }

    /// The UIVK counterpart of the vector above: same key, same P2SH-item fallback, this time
    /// through `encode_uivk_r0`.
    #[test]
    fn p2sh_uivk_falls_back_to_revision_2_instead_of_erroring() {
        let p2sh_uivk_str = "uvi153t9c3gg9y02a7lwtku6pk9hqhz0mvxysnnr770usznsghqfjz69maurfgecxawspdcumw0j2arw7yms275rgs34v8y3trezdh78cl6vvqmypgqmnsqp34psk8420kndga6vpefj468pkfxdfyu4awum9g68fgm5vfszm6vv05z46e6f5r0vvvu2frzv969tgeuy5aes9ugd9jlut7n3ym69u6a3mjrrxsxdt9c5v78z5ze0ffm85ehsge480krnhw0r9v928qr7k5c5f6mht4f23fdk608zugpqfhmndlle35fd5qdew8le2taepl604fp6dd4w7tquydjtwzxs7c24smzccjqf6q5eljr6fwrlagyqwd5smz4rxsz5fz9kjnn2ce8xe3lwmm2dhd28cd2mnr85lnhe956tts4hse455m0kwt25n5029f29gchm6lpaz88mp3stqnk2e4s0u684zu6wl45mhhw2a540tyw5f25uz2ztsas9jjrsn";

        let decoded = UnifiedIncomingViewingKey::decode(&MAIN_NETWORK, p2sh_uivk_str)
            .expect("the upstream vector decodes");

        let encoded = super::encode_uivk_r0(&decoded, &MAIN_NETWORK);
        assert_eq!(
            encoded,
            decoded.encode(&MAIN_NETWORK),
            "a P2SH item must fall back to zcash_keys's own revision 2 string"
        );
        assert_eq!(
            encoded, p2sh_uivk_str,
            "must reproduce the upstream vector exactly"
        );
    }

    /// `WalletFixture.Ben`'s seed phrase.
    const BEN_PHRASE: &str = "kitchen renew wide common vague fold vacuum tilt amazing pear \
        square gossip jewel month tree shock scan alpha just spot fluid toilet view dinner";

    /// The strings the SDK returned for account 0 of [`BEN_PHRASE`] before the NU7 crate move,
    /// computed independently with the pre-move `zcash_keys` 0.16.1 / `zcash_address` 0.13.0.
    struct PrePortStrings {
        address: &'static str,
        ufvk: &'static str,
        uivk: &'static str,
        orchard_only_internal: &'static str,
    }

    const MAINNET_PRE_PORT: PrePortStrings = PrePortStrings {
        address: "u1ppm8qyhws4mrs2m2puq2nyyrcpzfrj8hynw00355wy7vapzw5f84udl5qs5xf9lh9antxyvqfcgh76h87946q7vw5d6rrmze8x2c6p7vw59uy6mkadgz2467358z3ar7edyjcm7gkpqhdlaxhdzhqhxfacff0fud38zyyzakkscdmwvee59xzf36rhm5xvc893wewsrfe5svcmg4l85",
        ufvk: "uview188kc3e0wkwlv2qrke9wskcqz4qwj5sx0h8te0jgnfzyarwxm6ymzmsu9jzcl72pyamvj8whnvpfqahhx7nga2qgcv9zn9xsppym2vpcmw3204u57r5ygan9y3pllvv8wgk29ef34uycks9gee2plsa82v0g2dzdcve0g4y8ym4nmax9hnge4v9azw0hdzqr2pqr5uxq62x9p9vucdppq3f3u9z24jqx7envsh6tweu7a5xvrecxk7axjfad4pmtupsr76kvrqsjuqz3hpfdru5p6ty8qwjlfjr7p06u3dmmaap43k0fshcr3994a4l5cce2z3ef6ejdztqtqhd70xl43lmx938z56lz3d4w8twkjhx3en0up8dzqsjh5llmgxtdge3ux2lpxnr3f2e45enda2pa3qxx5pq2wjahj0k7vqfzqltkn3j0d5lxwppaqazyyfm5e5s93j4jzu25k663t8hwj6tq53mjagspzvl2y6fszhgvuudzt",
        uivk: "uivk1k45cs2xaq4ft88r9mkg7ut95jfgktqxz7kwgjugyzldtrg0833zk2e9w7gq7xj7z0c47zlt2ec7kcqhjndvr0re2tuvakjlzy0geafmkpuy5az07ehmnfml4dk04yz98sapmy9u7exv9r0ystm7whw0rnp36p0kada3a25za44u2ls5ys8fr3u2dlk4e4e9jk6n4pl9n04sz4yppn29dlzy62dmppw75n3pa424f338ckplnpsad736l7vw6844x9jzhnuav6f25eqxtuf3jk43ytsdsw8nfpu8w2ypna05syqxl74e6eywq8h55m32mtlw3evn55hhfje8nwxj7d8y4w9klpq",
        orchard_only_internal: "u144cetttqvww5xmhyvysxvcct3lr8n3rsufvynwl0hvv59nftkrk57fk3yev84z2qm4wr2g5zrz3xqytafdl8745ueaxxalk8p5p78vm7",
    };

    const TESTNET_PRE_PORT: PrePortStrings = PrePortStrings {
        address: "utest1yaqda6n3h0h5mdplk46e64rpwvhfe8ep6rm9lcc4r42cuk5376ul9yur8fuj0vms5l7emf6pf8l2d3lhxjzx8j6qkjf7g48kjakwdz6ycvcrdwyjhr9ve67v9hmw75u5fyvpeq99cjpp5l2shukmmd9dldvqj829r0s3qyevw7xzck6zrqp5hr93v5r929vsaqpa7we0xgakzc5dzpf",
        ufvk: "uviewtest18la5rss83ypen8qsnk79vwef270fffs50d302da2vjrv2guy3amz4kj66vx2rys8ytwk7rzfy2znykq4273q70q9nzsftjh8x4urejszpv2rn2rrh7thggc340m2vwxqgykypt2tcgkfp28y6yt7gtq4qc59lsssvxlma0jem7l4q7gh2jc6l2nag53t7neffpwhr626gr9zcnkfne87fltkrt4s6c76ez4u70e4sld4wtww7hrv508lch0dnqpysguhdjrn40rcjwj8aln22xeu7p75ted5nnpzyatlz25gu0t69vqtv8er774qqapmm0222q2pl5h84zpuhz5c0eg08pxkmtr7jlurxsw5c20urafl2acx3hm9w2uzz0exju66xksu9pplujr8trgnsjyfgrmqknrp2n5w6k899qjx2jrmxnrggg528s9pe694zvhec8vy6ussyr33nzsltz7y3zzlafypp54g39l6u79e0cgqf5cnpf9v",
        uivk: "uivktest1yxz7sek5j8e3xlytd98zcusvlfzg29z08vcv0j5gjun35xxyflnmjnt4958fne75gpj2tk8rfhgnxx7kpujan6q7urd4xrg4y440tk40gp9smldfes5hcakj5x424exvnrlpv0k9kux6k9tyv3qq3j3rpzjdv70p7ua879xzl3nas5k2vkm2dfu9ru2qe0hath7v7uxppp0w9qx56hrtfxjkn9r5h77wu8vkp8at0v396z2c2dhhs7srct4jry47n35axnzq4x4s3vxap2s2p6g0ejt2ge5zk9gy8cpusnhl3uzuq4sk0509eqa06scfrfljhuelsv62s59f6wzy0hlmklhdr8",
        orchard_only_internal: "utest1p3tmuly0dxjd5vvk47494uklm84rp8w8lyg77savy3aeazu8060ra8ty5cr8a4q40u4j05uwh5ewem5atmnmrr84020mk4gspyey5kma",
    };

    fn assert_pre_port_strings(
        network: zcash_protocol::consensus::Network,
        expected: &PrePortStrings,
    ) {
        let seed = bip0039::Mnemonic::<bip0039::English>::from_phrase(BEN_PHRASE)
            .expect("a valid phrase")
            .to_seed("");
        let ufvk = UnifiedSpendingKey::from_seed(&network, &seed, zip32::AccountId::ZERO)
            .expect("account 0 derives")
            .to_unified_full_viewing_key();
        let (ua, _) = ufvk
            .find_address(
                zip32::DiversifierIndex::new(),
                UnifiedAddressRequest::AllAvailableKeys,
            )
            .expect("the default address derives");

        assert_eq!(super::encode_ufvk_r0(&ufvk, &network), expected.ufvk);
        assert_eq!(
            super::encode_uivk_r0(&ufvk.to_unified_incoming_viewing_key(), &network),
            expected.uivk
        );
        assert_eq!(
            super::encode_unified_address_r0(&ua, &network),
            expected.address
        );

        let orchard = ufvk.orchard().expect("an Orchard key");
        assert_eq!(
            super::orchard_only_address_r0(
                &orchard.address_at(0u32, orchard::keys::Scope::Internal),
                network.network_type(),
            )
            .expect("the migration recipient")
            .encode(),
            expected.orchard_only_internal
        );
    }

    /// The UFVK, UIVK, default address and migration recipient of a fixed seed are exactly the
    /// strings the SDK returned before the NU7 crate move, not just the same revision.
    #[test]
    fn mainnet_encodings_match_the_pre_port_strings() {
        assert_pre_port_strings(
            zcash_protocol::consensus::Network::MainNetwork,
            &MAINNET_PRE_PORT,
        );
    }

    /// Testnet counterpart of [`mainnet_encodings_match_the_pre_port_strings`].
    #[test]
    fn testnet_encodings_match_the_pre_port_strings() {
        assert_pre_port_strings(
            zcash_protocol::consensus::Network::TestNetwork,
            &TESTNET_PRE_PORT,
        );
    }

    /// A revision 0 viewing key may carry a typecode 0x01 item, which revision 0 leaves
    /// unrecognised. `zcash_keys` keeps it and writes it back out at revision 2, where 0x01 is a
    /// P2SH item, so its own revision 2 string does not decode back. The encoders return that
    /// string instead of panicking.
    #[test]
    fn an_unknown_typecode_1_item_falls_back_to_revision_2_instead_of_panicking() {
        let usk = UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO)
            .expect("a spending key derives");
        let ufvk = usk.to_unified_full_viewing_key();
        let uivk = ufvk.to_unified_incoming_viewing_key();
        let unknown_1 = vec![1, 2, 3, 4];

        let ufvk_input = unified::Ufvk::try_from_items(
            unified::Revision::R0,
            vec![
                unified::Uitem::Data(unified::Fvk::Orchard(
                    ufvk.orchard().expect("an orchard item").to_bytes(),
                )),
                unified::Uitem::Data(unified::Fvk::Sapling(
                    ufvk.sapling().expect("a sapling item").to_bytes(),
                )),
                unified::Uitem::Data(unified::Fvk::Unknown {
                    typecode: 1,
                    data: unknown_1.clone(),
                }),
            ],
        )
        .expect("an unrecognised 0x01 item is valid at revision 0")
        .encode(&MAIN_NETWORK.network_type());
        let decoded_ufvk = UnifiedFullViewingKey::decode(&MAIN_NETWORK, &ufvk_input)
            .expect("the revision 0 string decodes");
        let ufvk_r2 = decoded_ufvk.encode(&MAIN_NETWORK);
        assert!(
            unified::Ufvk::decode(&ufvk_r2).is_err(),
            "the revision 2 string must not decode back, or this test covers nothing"
        );
        assert_eq!(super::encode_ufvk_r0(&decoded_ufvk, &MAIN_NETWORK), ufvk_r2);

        let uivk_input = unified::Uivk::try_from_items(
            unified::Revision::R0,
            vec![
                unified::Uitem::Data(unified::Ivk::Orchard(
                    uivk.orchard().as_ref().expect("an orchard item").to_bytes(),
                )),
                unified::Uitem::Data(unified::Ivk::Sapling(
                    uivk.sapling().as_ref().expect("a sapling item").to_bytes(),
                )),
                unified::Uitem::Data(unified::Ivk::Unknown {
                    typecode: 1,
                    data: unknown_1,
                }),
            ],
        )
        .expect("an unrecognised 0x01 item is valid at revision 0")
        .encode(&MAIN_NETWORK.network_type());
        let decoded_uivk = UnifiedIncomingViewingKey::decode(&MAIN_NETWORK, &uivk_input)
            .expect("the revision 0 string decodes");
        let uivk_r2 = decoded_uivk.encode(&MAIN_NETWORK);
        assert!(
            unified::Uivk::decode(&uivk_r2).is_err(),
            "the revision 2 string must not decode back, or this test covers nothing"
        );
        assert_eq!(super::encode_uivk_r0(&decoded_uivk, &MAIN_NETWORK), uivk_r2);
    }
}
