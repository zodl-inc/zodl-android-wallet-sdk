//! Regression tests for the unified address and viewing key strings that the SDK gives to
//! Kotlin.
//!
//! `zcash_keys` encodes these values at ZIP 316 revision 0 if revision 0 can represent them, and
//! at revision 2 otherwise. The tests compare its output with the strings that the SDK returned
//! before the NU7 crate move, and with upstream test vectors.

use transparent::address::TransparentAddress;
use zcash_address::unified::{self, Encoding as _};
use zcash_client_backend::{
    address::{Receiver, UnifiedAddress},
    keys::{
        UnifiedAddressRequest, UnifiedFullViewingKey, UnifiedIncomingViewingKey, UnifiedSpendingKey,
    },
};
use zcash_protocol::consensus::{MAIN_NETWORK, Parameters, TEST_NETWORK};

/// Returns the ZIP 316 revision of a unified full viewing key string.
fn ufvk_revision(encoded: &str) -> unified::Revision {
    unified::Ufvk::decode(encoded).unwrap().1
}

/// Returns the ZIP 316 revision of a unified incoming viewing key string.
fn uivk_revision(encoded: &str) -> unified::Revision {
    unified::Uivk::decode(encoded).unwrap().1
}

/// Returns the ZIP 316 revision of a unified address string.
fn ua_revision(encoded: &str) -> unified::Revision {
    unified::Address::decode(encoded).unwrap().1
}

/// Checks that the UFVK, the UIVK and the UA of a seed-derived key encode at revision 0, with the
/// given prefixes. Checks also that each string decodes back to the same value, and that the UA
/// keeps its transparent receiver.
fn assert_revision_0_round_trip(
    network: &impl Parameters,
    ua_prefix: &str,
    ufvk_prefix: &str,
    uivk_prefix: &str,
) {
    let usk = UnifiedSpendingKey::from_seed(network, &[7u8; 32], zip32::AccountId::ZERO).unwrap();
    let ufvk = usk.to_unified_full_viewing_key();
    let uivk = ufvk.to_unified_incoming_viewing_key();
    let (ua, _) = ufvk
        .default_address(UnifiedAddressRequest::AllAvailableKeys)
        .unwrap();

    let ufvk_str = ufvk.encode(network).unwrap();
    let uivk_str = uivk.encode(network).unwrap();
    let ua_str = ua.encode_receiver_preserving(network);

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
    assert_eq!(ufvk_revision(&ufvk_str), unified::Revision::R0);
    assert_eq!(uivk_revision(&uivk_str), unified::Revision::R0);
    assert_eq!(ua_revision(&ua_str), unified::Revision::R0);

    let decoded_ufvk = UnifiedFullViewingKey::decode(network, &ufvk_str).unwrap();
    assert_eq!(decoded_ufvk.encode(network).unwrap(), ufvk_str);

    let decoded_uivk = UnifiedIncomingViewingKey::decode(network, &uivk_str).unwrap();
    assert_eq!(decoded_uivk.encode(network).unwrap(), uivk_str);

    let decoded_ua = UnifiedAddress::decode(network, &ua_str).unwrap();
    assert_eq!(decoded_ua.encode_receiver_preserving(network), ua_str);
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
        assert_eq!(
            ua_revision(vector),
            unified::Revision::R0,
            "upstream vector must be revision 0"
        );
        let ua = UnifiedAddress::decode(&MAIN_NETWORK, vector).unwrap();
        assert_eq!(
            ua.encode_receiver_preserving(&MAIN_NETWORK),
            vector,
            "must reproduce the upstream vector exactly"
        );
    }
}

/// A transparent-only UFVK has no shielded item, so revision 0 cannot represent it (ZIP 316). It
/// encodes at revision 2.
#[test]
fn transparent_only_ufvk_encodes_at_revision_2() {
    let usk =
        UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO).unwrap();
    let ufvk = usk.to_unified_full_viewing_key();

    // A transparent-only UFVK built from the fixture key's own p2pkh item.
    let p2pkh_fvk_bytes: [u8; 65] = ufvk.p2pkh().unwrap().serialize().try_into().unwrap();
    let transparent_only_ufvk_str = unified::Ufvk::try_from_items(
        unified::Revision::R2,
        vec![unified::Uitem::Data(unified::Fvk::P2pkh(p2pkh_fvk_bytes))],
    )
    .unwrap()
    .encode(&MAIN_NETWORK.network_type());
    let transparent_only_ufvk =
        UnifiedFullViewingKey::decode(&MAIN_NETWORK, &transparent_only_ufvk_str).unwrap();

    assert_eq!(
        transparent_only_ufvk.encode(&MAIN_NETWORK).unwrap(),
        transparent_only_ufvk_str,
        "a transparent-only UFVK must encode at revision 2"
    );
}

/// ZIP 316 revision 0 rejects a container whose only items are transparent. An item of an
/// unknown typecode is not transparent, so a value with a transparent item and an unknown item
/// encodes at revision 0, although it has no Orchard or Sapling item.
#[test]
fn transparent_and_unknown_items_encode_at_revision_0() {
    let usk =
        UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO).unwrap();
    let ufvk = usk.to_unified_full_viewing_key();
    let (ua, _) = ufvk
        .default_address(UnifiedAddressRequest::AllAvailableKeys)
        .unwrap();

    let p2pkh_fvk_bytes: [u8; 65] = ufvk.p2pkh().unwrap().serialize().try_into().unwrap();
    let ufvk_r2 = unified::Ufvk::try_from_items(
        unified::Revision::R2,
        vec![
            unified::Uitem::Data(unified::Fvk::P2pkh(p2pkh_fvk_bytes)),
            unified::Uitem::Data(unified::Fvk::Unknown {
                typecode: 0xff00,
                data: vec![1, 2, 3, 4],
            }),
        ],
    )
    .unwrap()
    .encode(&MAIN_NETWORK.network_type());
    let decoded_ufvk = UnifiedFullViewingKey::decode(&MAIN_NETWORK, &ufvk_r2).unwrap();
    let encoded_ufvk = decoded_ufvk.encode(&MAIN_NETWORK).unwrap();
    assert_eq!(ufvk_revision(&encoded_ufvk), unified::Revision::R0);
    assert!(
        UnifiedFullViewingKey::decode(&MAIN_NETWORK, &encoded_ufvk)
            .unwrap()
            .is_equivalent_to(&decoded_ufvk)
    );

    let receiver_item = match ua.transparent().unwrap() {
        TransparentAddress::PublicKeyHash(data) => unified::Receiver::P2pkh(*data),
        TransparentAddress::ScriptHash(data) => unified::Receiver::P2sh(*data),
    };
    let ua_r2 = unified::Address::try_from_items(
        unified::Revision::R2,
        vec![
            unified::Uitem::Data(receiver_item),
            unified::Uitem::Data(unified::Receiver::Unknown {
                typecode: 0xff00,
                data: vec![0u8; 20],
            }),
        ],
    )
    .unwrap()
    .encode(&MAIN_NETWORK.network_type());
    let decoded_ua = UnifiedAddress::decode(&MAIN_NETWORK, &ua_r2).unwrap();
    let encoded_ua = decoded_ua.encode_receiver_preserving(&MAIN_NETWORK);
    assert_eq!(ua_revision(&encoded_ua), unified::Revision::R0);
    assert_eq!(
        UnifiedAddress::decode(&MAIN_NETWORK, &encoded_ua).unwrap(),
        decoded_ua
    );
}

/// A data item of a typecode that this crate does not interpret survives encoding at revision 0
/// unchanged.
#[test]
fn an_unknown_item_keeps_its_place_at_revision_0() {
    let usk =
        UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO).unwrap();
    let ufvk = usk.to_unified_full_viewing_key();

    let input = unified::Ufvk::try_from_items(
        unified::Revision::R0,
        vec![
            unified::Uitem::Data(unified::Fvk::Orchard(ufvk.orchard().unwrap().to_bytes())),
            unified::Uitem::Data(unified::Fvk::Sapling(ufvk.sapling().unwrap().to_bytes())),
            unified::Uitem::Data(unified::Fvk::Unknown {
                typecode: 0xff00,
                data: vec![1, 2, 3, 4],
            }),
        ],
    )
    .unwrap()
    .encode(&MAIN_NETWORK.network_type());

    let decoded = UnifiedFullViewingKey::decode(&MAIN_NETWORK, &input).unwrap();
    assert_eq!(
        decoded.encode(&MAIN_NETWORK).unwrap(),
        input,
        "the unknown item must survive unchanged"
    );
}

/// A transparent-only UIVK has no shielded item, so revision 0 cannot represent it. It encodes
/// at revision 2.
#[test]
fn transparent_only_uivk_encodes_at_revision_2() {
    use transparent::keys::IncomingViewingKey as _;

    let usk =
        UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO).unwrap();
    let uivk = usk
        .to_unified_full_viewing_key()
        .to_unified_incoming_viewing_key();

    let p2pkh_ivk_bytes: [u8; 65] = uivk.p2pkh().unwrap().serialize().try_into().unwrap();
    let transparent_only_uivk_str = unified::Uivk::try_from_items(
        unified::Revision::R2,
        vec![unified::Uitem::Data(unified::Ivk::P2pkh(p2pkh_ivk_bytes))],
    )
    .unwrap()
    .encode(&MAIN_NETWORK.network_type());
    let transparent_only_uivk =
        UnifiedIncomingViewingKey::decode(&MAIN_NETWORK, &transparent_only_uivk_str).unwrap();

    assert_eq!(
        transparent_only_uivk.encode(&MAIN_NETWORK).unwrap(),
        transparent_only_uivk_str,
        "a transparent-only UIVK must encode at revision 2"
    );
}

/// Revision 0 cannot hold expiry metadata. A unified address with an expiry height item encodes
/// at revision 2, and keeps the metadata.
#[test]
fn ua_with_expiry_metadata_encodes_at_revision_2() {
    let usk =
        UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO).unwrap();
    let ufvk = usk.to_unified_full_viewing_key();
    let (ua, _) = ufvk
        .default_address(UnifiedAddressRequest::AllAvailableKeys)
        .unwrap();

    let transparent_item = match ua.transparent().unwrap() {
        TransparentAddress::PublicKeyHash(data) => unified::Receiver::P2pkh(*data),
        TransparentAddress::ScriptHash(data) => unified::Receiver::P2sh(*data),
    };
    let with_expiry_str = unified::Address::try_from_items(
        unified::Revision::R2,
        vec![
            unified::Uitem::Data(unified::Receiver::Orchard(
                ua.orchard().unwrap().to_raw_address_bytes(),
            )),
            unified::Uitem::Data(unified::Receiver::Sapling(ua.sapling().unwrap().to_bytes())),
            unified::Uitem::Data(transparent_item),
            unified::Uitem::Metadata(unified::MetadataItem::ExpiryHeight(2_000_000)),
        ],
    )
    .unwrap()
    .encode(&MAIN_NETWORK.network_type());
    let ua_with_expiry = UnifiedAddress::decode(&MAIN_NETWORK, &with_expiry_str).unwrap();

    assert_eq!(
        ua_with_expiry.encode_receiver_preserving(&MAIN_NETWORK),
        with_expiry_str,
        "an address with expiry metadata must encode at revision 2 with the metadata"
    );
}

/// The UFVK counterpart of the address case above: a UFVK with an expiry height item encodes at
/// revision 2, and keeps the metadata.
#[test]
fn ufvk_with_expiry_metadata_encodes_at_revision_2() {
    let usk =
        UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO).unwrap();
    let ufvk = usk.to_unified_full_viewing_key();

    let with_expiry_str = unified::Ufvk::try_from_items(
        unified::Revision::R2,
        vec![
            unified::Uitem::Data(unified::Fvk::Orchard(ufvk.orchard().unwrap().to_bytes())),
            unified::Uitem::Data(unified::Fvk::Sapling(ufvk.sapling().unwrap().to_bytes())),
            unified::Uitem::Metadata(unified::MetadataItem::ExpiryHeight(2_000_000)),
        ],
    )
    .unwrap()
    .encode(&MAIN_NETWORK.network_type());
    let ufvk_with_expiry = UnifiedFullViewingKey::decode(&MAIN_NETWORK, &with_expiry_str).unwrap();

    assert_eq!(
        ufvk_with_expiry.encode(&MAIN_NETWORK).unwrap(),
        with_expiry_str,
        "a UFVK with expiry metadata must encode at revision 2 with the metadata"
    );
}

/// ZIP 316 specifies a P2SH viewing key item only for revision 2, so a UFVK that carries one
/// encodes at revision 2. The vector is the first one with a P2SH item (Orchard, no expiry) in
/// `zcash_keys-0.17.0-pre.0`'s `src/keys/test_vectors/unified_viewing_keys_r2.rs`, from
/// `zcash/zcash-test-vectors`'s `unified_viewing_keys_r2.py`.
#[test]
fn p2sh_ufvk_encodes_at_revision_2() {
    let p2sh_ufvk_str = "uvf1td5cvtvcvlmxqgaxus3m2m745ykzk5gncx4z7x5zrk9l256qjfmfg6n8efrg0htq5sac4kg54aejsepkcl6hgaqyk6dlstk4fpnkmxjcvl72zh97r3fz7wa02wt4mmcayn6ukzck9d96tcqmkk2j5rzdr44f6haa9v7qzqsserruscf6lqum38xr8k84yesq8ecxa25aqg04uh3gweg8d25055v9z5u4c75mtt06g57eu8mq8eldg2gn9su23hkv0jrq7asc6tad5kaxs4r330uyf8qmydxkhe0nmtx26nux84sftau7072hp72qhfdzyhwu3ndhdq4glu8zjxp7srdaep9nn96swfl8td478n3jey0r73x5du50gjqr37ta39dpg25vkqw0q59pvfxp4xhl8392keskzl202njjeh677tphgcuayn09kl9vkxqkcy2pq7tar8vcjgewvem8hptycrd7v659lgzsnr85udpx7kv785m4a4259wr0snunfndya9x44m0zqt3py4x9md4vcdrj628qxmam6t836dp5zwsv744ay";

    let decoded = UnifiedFullViewingKey::decode(&MAIN_NETWORK, p2sh_ufvk_str).unwrap();
    assert_eq!(
        decoded.encode(&MAIN_NETWORK).unwrap(),
        p2sh_ufvk_str,
        "must reproduce the upstream vector exactly"
    );
}

/// The UIVK counterpart of the vector above: the same key, with the same P2SH item.
#[test]
fn p2sh_uivk_encodes_at_revision_2() {
    let p2sh_uivk_str = "uvi153t9c3gg9y02a7lwtku6pk9hqhz0mvxysnnr770usznsghqfjz69maurfgecxawspdcumw0j2arw7yms275rgs34v8y3trezdh78cl6vvqmypgqmnsqp34psk8420kndga6vpefj468pkfxdfyu4awum9g68fgm5vfszm6vv05z46e6f5r0vvvu2frzv969tgeuy5aes9ugd9jlut7n3ym69u6a3mjrrxsxdt9c5v78z5ze0ffm85ehsge480krnhw0r9v928qr7k5c5f6mht4f23fdk608zugpqfhmndlle35fd5qdew8le2taepl604fp6dd4w7tquydjtwzxs7c24smzccjqf6q5eljr6fwrlagyqwd5smz4rxsz5fz9kjnn2ce8xe3lwmm2dhd28cd2mnr85lnhe956tts4hse455m0kwt25n5029f29gchm6lpaz88mp3stqnk2e4s0u684zu6wl45mhhw2a540tyw5f25uz2ztsas9jjrsn";

    let decoded = UnifiedIncomingViewingKey::decode(&MAIN_NETWORK, p2sh_uivk_str).unwrap();
    assert_eq!(
        decoded.encode(&MAIN_NETWORK).unwrap(),
        p2sh_uivk_str,
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

fn assert_pre_port_strings(network: zcash_protocol::consensus::Network, expected: &PrePortStrings) {
    let seed = bip0039::Mnemonic::<bip0039::English>::from_phrase(BEN_PHRASE)
        .unwrap()
        .to_seed("");
    let ufvk = UnifiedSpendingKey::from_seed(&network, &seed, zip32::AccountId::ZERO)
        .unwrap()
        .to_unified_full_viewing_key();
    let (ua, _) = ufvk
        .find_address(
            zip32::DiversifierIndex::new(),
            UnifiedAddressRequest::AllAvailableKeys,
        )
        .unwrap();

    assert_eq!(ufvk.encode(&network).unwrap(), expected.ufvk);
    assert_eq!(
        ufvk.to_unified_incoming_viewing_key()
            .encode(&network)
            .unwrap(),
        expected.uivk
    );
    assert_eq!(ua.encode_receiver_preserving(&network), expected.address);

    let orchard = ufvk.orchard().unwrap();
    assert_eq!(
        Receiver::Orchard(orchard.address_at(0u32, orchard::keys::Scope::Internal))
            .to_zcash_address(network.network_type())
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

/// Revision 0 does not recognize typecode 0x01, so a revision 0 viewing key can carry an unknown
/// item with that typecode. At revision 2, typecode 0x01 is a P2SH item, and arbitrary data is
/// not valid there. Such a key encodes at revision 0, to the same string it decoded from.
#[test]
fn an_unknown_typecode_1_item_keeps_revision_0() {
    let usk =
        UnifiedSpendingKey::from_seed(&MAIN_NETWORK, &[7u8; 32], zip32::AccountId::ZERO).unwrap();
    let ufvk = usk.to_unified_full_viewing_key();
    let uivk = ufvk.to_unified_incoming_viewing_key();
    let unknown_1 = vec![1, 2, 3, 4];

    let ufvk_input = unified::Ufvk::try_from_items(
        unified::Revision::R0,
        vec![
            unified::Uitem::Data(unified::Fvk::Orchard(ufvk.orchard().unwrap().to_bytes())),
            unified::Uitem::Data(unified::Fvk::Sapling(ufvk.sapling().unwrap().to_bytes())),
            unified::Uitem::Data(unified::Fvk::Unknown {
                typecode: 1,
                data: unknown_1.clone(),
            }),
        ],
    )
    .unwrap()
    .encode(&MAIN_NETWORK.network_type());
    let decoded_ufvk = UnifiedFullViewingKey::decode(&MAIN_NETWORK, &ufvk_input).unwrap();
    assert_eq!(decoded_ufvk.encode(&MAIN_NETWORK).unwrap(), ufvk_input);

    let uivk_input = unified::Uivk::try_from_items(
        unified::Revision::R0,
        vec![
            unified::Uitem::Data(unified::Ivk::Orchard(
                uivk.orchard().as_ref().unwrap().to_bytes(),
            )),
            unified::Uitem::Data(unified::Ivk::Sapling(
                uivk.sapling().as_ref().unwrap().to_bytes(),
            )),
            unified::Uitem::Data(unified::Ivk::Unknown {
                typecode: 1,
                data: unknown_1,
            }),
        ],
    )
    .unwrap()
    .encode(&MAIN_NETWORK.network_type());
    let decoded_uivk = UnifiedIncomingViewingKey::decode(&MAIN_NETWORK, &uivk_input).unwrap();
    assert_eq!(decoded_uivk.encode(&MAIN_NETWORK).unwrap(), uivk_input);
}
