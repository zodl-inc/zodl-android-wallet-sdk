//! JNI surface for reading gift card links (see [`crate::liberated_payment`]).
//!
//! A gift card is a liberated payment (ZIP 324) as this SDK's Kotlin API names it. Parsing is
//! pure: it touches no wallet database and no network. A link carries a spending secret, so
//! nothing derived from the input other than the parsed card itself ever crosses back to the
//! JVM; failures are reported as a fixed category.

use jni::{
    JNIEnv,
    objects::{JClass, JObject, JString, JValue},
    sys::{jint, jlong, jobject},
};
use secrecy::zeroize::{Zeroize, Zeroizing};
use zcash_protocol::consensus::{Network, NetworkType};

use crate::liberated_payment::{self, LiberatedPayment, Origin};
use crate::utils::{catch_unwind, exception::unwrap_exc_or, java_string_to_rust};

/// The JVM class carrying a parsed card. Its constructor signature is part of the JNI contract
/// with `cash.z.ecc.android.sdk.internal.model.JniGiftCard`.
const JNI_GIFT_CARD_CLASS: &str = "cash/z/ecc/android/sdk/internal/model/JniGiftCard";
const JNI_GIFT_CARD_CTOR: &str = "(IIJJLjava/lang/String;[BLjava/lang/String;)V";

/// The JVM class thrown when a link is rejected. Its `(String)` constructor, taking one of the
/// [`error_kind`] categories, is part of the JNI contract with
/// `cash.z.ecc.android.sdk.internal.jni.GiftCardLinkException`.
const GIFT_CARD_LINK_EXCEPTION_CLASS: &str =
    "cash/z/ecc/android/sdk/internal/jni/GiftCardLinkException";

/// Categorizes a rejected link without echoing any part of it.
///
/// The categories are the Kotlin layer's `GiftCardLinkError` reasons and are stable. The field
/// names carried by some [`liberated_payment::Error`] variants are static strings chosen by the
/// shared module, never input, but they are still dropped: the category is all a caller can act
/// on.
pub(crate) fn error_kind(error: &liberated_payment::Error) -> &'static str {
    match error {
        liberated_payment::Error::NotAPaymentLink => "not_a_gift_link",
        liberated_payment::Error::UnsupportedVersion => "unsupported_version",
        liberated_payment::Error::MissingField(_) => "missing_field",
        liberated_payment::Error::DuplicateField(_) => "duplicate_field",
        liberated_payment::Error::InvalidField(_) => "invalid_field",
        liberated_payment::Error::UnsupportedNetwork => "unsupported_network",
        liberated_payment::Error::NetworkMismatch => "network_mismatch",
        liberated_payment::Error::TooLong => "too_long",
        liberated_payment::Error::KeyDerivation => "key_derivation",
    }
}

/// The network id the Kotlin layer's `ZcashNetwork` uses for Testnet.
const NETWORK_ID_TESTNET: jint = 0;
/// The network id the Kotlin layer's `ZcashNetwork` uses for Mainnet.
const NETWORK_ID_MAINNET: jint = 1;

/// The `amount` a [`ParsedCard`] carries when the link states none. Zatoshi amounts are never
/// negative, so the sentinel cannot collide with a stated amount.
pub(crate) const NO_AMOUNT: jlong = -1;

/// The integer the Kotlin layer uses for each [`Origin`]: the ordinal of its `GiftCardOrigin`
/// case (`Zodl`, `LegacyV1`, `LegacyV2`, `LegacyV3`).
pub(crate) fn origin_code(origin: Origin) -> jint {
    match origin {
        Origin::V1 => 0,
        Origin::LegacyV1 => 1,
        Origin::LegacyV2 => 2,
        Origin::LegacyV3 => 3,
    }
}

/// The parsed, JNI-ready form of a card. The seed is wiped when dropped.
pub(crate) struct ParsedCard {
    pub(crate) origin: jint,
    pub(crate) network_id: jint,
    pub(crate) birthday_height: jlong,
    /// The stated amount in zatoshis, or [`NO_AMOUNT`] when the link states none.
    pub(crate) amount: jlong,
    pub(crate) description: Option<String>,
    pub(crate) seed: Zeroizing<[u8; 64]>,
    pub(crate) funding_address: String,
}

/// Parses `link` and derives everything the Kotlin layer needs from it.
///
/// Only Mainnet and Testnet cards are accepted, matching the networks this SDK runs on.
pub(crate) fn parse_card(link: &str) -> Result<ParsedCard, liberated_payment::Error> {
    let card = LiberatedPayment::parse(link)?;
    let (network_id, params) = match card.network() {
        NetworkType::Main => (NETWORK_ID_MAINNET, Network::MainNetwork),
        NetworkType::Test => (NETWORK_ID_TESTNET, Network::TestNetwork),
        NetworkType::Regtest => return Err(liberated_payment::Error::UnsupportedNetwork),
    };
    let funding_address = card.funding_address(&params)?;
    let seed = card.seed()?;
    let amount = match card.amount() {
        Some(zat) => jlong::try_from(zat.into_u64())
            .map_err(|_| liberated_payment::Error::InvalidField("amount"))?,
        None => NO_AMOUNT,
    };
    Ok(ParsedCard {
        origin: origin_code(card.origin()),
        network_id,
        birthday_height: jlong::from(u32::from(card.birthday_height())),
        amount,
        description: card.description().map(str::to_owned),
        seed,
        funding_address,
    })
}

fn to_jni<'local>(
    env: &mut JNIEnv<'local>,
    card: &ParsedCard,
) -> jni::errors::Result<JObject<'local>> {
    let description = match &card.description {
        Some(text) => JObject::from(env.new_string(text)?),
        None => JObject::null(),
    };
    let seed = crate::utils::rust_bytes_to_java(env, card.seed.as_slice())?;
    let funding_address = env.new_string(&card.funding_address)?;
    env.new_object(
        JNI_GIFT_CARD_CLASS,
        JNI_GIFT_CARD_CTOR,
        &[
            JValue::Int(card.origin),
            JValue::Int(card.network_id),
            JValue::Long(card.birthday_height),
            JValue::Long(card.amount),
            JValue::Object(&description),
            JValue::Object(&seed),
            JValue::Object(&funding_address),
        ],
    )
}

/// Parses a gift card link of any supported kind.
///
/// Returns a `JniGiftCard`. On a rejected link, throws `GiftCardLinkException` carrying only the
/// failure category; the generic `RuntimeException` is the fallback should that throw fail.
#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_RustGiftCardTool_parseGiftCardLink<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    input: JString<'local>,
) -> jobject {
    let result = catch_unwind(&mut env, |env| {
        let mut link = java_string_to_rust(env, &input)?;
        let parsed = parse_card(&link);
        link.zeroize();
        match parsed {
            Ok(card) => Ok(to_jni(env, &card)?.into_raw()),
            Err(e) => {
                let kind = error_kind(&e);
                crate::utils::exception::throw_object(env, GIFT_CARD_LINK_EXCEPTION_CLASS, |env| {
                    let reason = env.new_string(kind)?;
                    env.new_object(
                        GIFT_CARD_LINK_EXCEPTION_CLASS,
                        "(Ljava/lang/String;)V",
                        &[JValue::Object(&reason)],
                    )
                });
                Err(anyhow::anyhow!("invalid gift card link: {}", kind))
            }
        }
    });
    unwrap_exc_or(&mut env, result, std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
    use zcash_protocol::consensus::BlockHeight;

    use super::*;

    /// The host the SDK's own links are served from. Only tests build links: the SDK reads them.
    const HOST: &str = "https://gift.zodl.com/";

    /// A mainnet birthday above NU5 activation.
    const MAIN_HEIGHT: u32 = 3_500_000;

    // A published, never-funded reference vector of the legacy `v3` encoding (32 zero bytes of
    // entropy); the same vector `liberated_payment.rs` tests against.
    const LEGACY_V3: &str = "https://legacy.example/payment-links/open#v3=WyJtYWluIiwiQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQSIsMzQ4MzE0MSwiMTAwMDAwMCIsImtuaWdodE1hZ2ljIiwxMS4xNzQ3LCJJdCdzIGEgZ3JlYXQgZGF5IHRvIHNoaWVsZCB5b3VyIFpFQyDwn5uh77iPIl0";

    fn main_link(amount: Option<u64>) -> String {
        LiberatedPayment::new(
            NetworkType::Main,
            &[1; 32],
            BlockHeight::from_u32(MAIN_HEIGHT),
            amount.map(|zat| zcash_protocol::value::Zatoshis::from_u64(zat).unwrap()),
            None,
        )
        .unwrap()
        .to_link(HOST)
        .unwrap()
    }

    fn legacy_v2_link(json: &serde_json::Value) -> String {
        format!(
            "https://legacy.example/payment-links/open#v2={}",
            URL_SAFE_NO_PAD.encode(json.to_string())
        )
    }

    fn legacy_v2(amount: serde_json::Value, height: serde_json::Value) -> serde_json::Value {
        serde_json::json!({
            "v": 2, "network": "main", "amountZatoshi": amount,
            "mnemonic": format!("{} art", ["abandon"; 23].join(" ")),
            "birthdayHeight": height, "presentation": {"message": "gm"},
        })
    }

    #[test]
    fn parses_a_legacy_link_for_the_jni_layer() {
        let card = parse_card(LEGACY_V3).unwrap();
        assert_eq!(card.origin, 3);
        assert_eq!(card.network_id, NETWORK_ID_MAINNET);
        assert_eq!(card.birthday_height, 3_483_141);
        assert_eq!(card.amount, 1_000_000);
        assert_eq!(
            card.description.as_deref(),
            Some("It's a great day to shield your ZEC 🛡️")
        );
        let phrase = format!("{} art", ["abandon"; 23].join(" "));
        let expected = bip0039::Mnemonic::<bip0039::English>::from_phrase(phrase.as_str())
            .unwrap()
            .to_seed("");
        assert_eq!(*card.seed, expected);
        // An Orchard-only mainnet unified address.
        assert!(card.funding_address.starts_with("u1"));
    }

    #[test]
    fn parses_a_v1_link_and_reports_its_network_and_funding_address() {
        let secret =
            liberated_payment::derive_payment_secret(&Network::TestNetwork, &[7; 32], 0).unwrap();
        let card = LiberatedPayment::new(
            NetworkType::Test,
            &secret,
            BlockHeight::from_u32(4_000_000),
            None,
            None,
        )
        .unwrap();
        let link = card.to_link(HOST).unwrap();
        let parsed = parse_card(&link).unwrap();
        assert_eq!(parsed.origin, 0);
        assert_eq!(parsed.network_id, NETWORK_ID_TESTNET);
        assert_eq!(parsed.birthday_height, 4_000_000);
        assert_eq!(parsed.description, None);
        assert_eq!(*parsed.seed, *card.seed().unwrap());
        assert_eq!(
            parsed.funding_address,
            card.funding_address(&Network::TestNetwork).unwrap()
        );
    }

    #[test]
    fn a_link_without_an_amount_reports_the_no_amount_sentinel() {
        assert_eq!(NO_AMOUNT, -1);
        assert_eq!(parse_card(&main_link(None)).unwrap().amount, NO_AMOUNT);
        // The smallest and a typical stated amount are reported as-is, never as the sentinel.
        assert_eq!(parse_card(&main_link(Some(1))).unwrap().amount, 1);
        assert_eq!(
            parse_card(&main_link(Some(250_000_000))).unwrap().amount,
            250_000_000
        );
        // A malformed amount is informational and reads as absent.
        assert_eq!(
            parse_card(&format!("{}&amount=-1", main_link(None)))
                .unwrap()
                .amount,
            NO_AMOUNT
        );
    }

    #[test]
    fn descriptions_reach_the_jni_layer_sanitized() {
        let link = format!("{}&desc=Gift%E2%80%AEmoc.liame%0A%07", main_link(None));
        assert_eq!(
            parse_card(&link).unwrap().description.as_deref(),
            // The line break becomes a space; the override and the bell are removed.
            Some("Giftmoc.liame ")
        );
        // A malformed description does not make a funded card unclaimable.
        for desc in ["100%", "%ZZ"] {
            assert!(parse_card(&format!("{}&desc={desc}", main_link(None))).is_ok());
        }
    }

    #[test]
    fn upper_case_keys_are_read() {
        let link = main_link(None);
        let key = link
            .split("key=")
            .nth(1)
            .unwrap()
            .split('&')
            .next()
            .unwrap();
        let upper = link.replace(key, &key.to_ascii_uppercase());
        let card = parse_card(&upper).unwrap();
        assert_eq!(*card.seed, *parse_card(&link).unwrap().seed);
    }

    #[test]
    fn origin_codes_match_the_kotlin_ordinals() {
        assert_eq!(origin_code(Origin::V1), 0);
        assert_eq!(origin_code(Origin::LegacyV1), 1);
        assert_eq!(origin_code(Origin::LegacyV2), 2);
        assert_eq!(origin_code(Origin::LegacyV3), 3);
    }

    #[test]
    fn rejects_regtest_cards() {
        let link = LiberatedPayment::new(
            NetworkType::Regtest,
            &[1; 32],
            BlockHeight::from_u32(10),
            None,
            None,
        )
        .unwrap()
        .to_link(HOST)
        .unwrap();
        assert_eq!(
            parse_card(&link).err().map(|e| error_kind(&e)),
            Some("unsupported_network")
        );
    }

    #[test]
    fn rejects_inputs_shorter_than_an_https_prefix() {
        // `https://` is 8 bytes: nothing shorter can be a link, with or without a fragment.
        for text in ["", "#", "h#v=1", "https:#", "https:/"] {
            assert!(text.len() < "https://".len());
            assert_eq!(
                parse_card(text).err().map(|e| error_kind(&e)),
                Some("not_a_gift_link"),
                "{text:?}"
            );
        }
    }

    #[test]
    fn rejects_birthdays_below_nu5() {
        let link = main_link(None);
        let height = format!("height={MAIN_HEIGHT}");
        for h in ["1", "419199", "1687103"] {
            assert_eq!(
                parse_card(&link.replace(&height, &format!("height={h}")))
                    .err()
                    .map(|e| error_kind(&e)),
                Some("invalid_field"),
                "{h}"
            );
        }
        // NU5 activation itself is accepted.
        assert!(parse_card(&link.replace(&height, "height=1687104")).is_ok());
    }

    #[test]
    fn legacy_numbers_are_strict() {
        let ok = |amount: serde_json::Value, height: serde_json::Value| {
            parse_card(&legacy_v2_link(&legacy_v2(amount, height)))
        };
        // Numbers and digit strings (trimmed) are read.
        let card = ok(1_000.into(), " 3400000 ".into()).unwrap();
        assert_eq!((card.amount, card.birthday_height), (1_000, 3_400_000));
        assert_eq!(
            ok("2500000".into(), 3_400_000.into()).unwrap().amount,
            2_500_000
        );
        // Anything else is refused: signs, exponents, fractions, blanks, overflow, zero, and
        // values of the wrong JSON type.
        let bad_amounts: [serde_json::Value; 9] = [
            (-1).into(),
            "-1".into(),
            "+1".into(),
            "1e3".into(),
            1.5.into(),
            "".into(),
            "18446744073709551616".into(),
            0.into(),
            serde_json::Value::Null,
        ];
        for amount in bad_amounts {
            assert_eq!(
                ok(amount.clone(), 3_400_000.into())
                    .err()
                    .map(|e| error_kind(&e)),
                Some("invalid_field"),
                "{amount}"
            );
        }
        for height in [
            serde_json::Value::from(4_294_967_296u64),
            "1".into(),
            "".into(),
        ] {
            assert_eq!(
                ok(1_000.into(), height.clone())
                    .err()
                    .map(|e| error_kind(&e)),
                Some("invalid_field"),
                "{height}"
            );
        }
    }

    #[test]
    fn categorizes_rejected_links() {
        let link = main_link(None);
        let key = link.split('&').nth(1).unwrap().trim_start_matches("key=");
        let cases = [
            // The host is not part of the payment: any `https` URL is read, so only a link
            // without a fragment or with another scheme is "not a gift link".
            (
                link.split_once('#').unwrap().0.to_owned(),
                "not_a_gift_link",
            ),
            (link.replacen("https://", "http://", 1), "not_a_gift_link"),
            (link.replace("v=1", "v=42"), "unsupported_version"),
            (format!("{link}&height=1"), "duplicate_field"),
            (
                link.replace(&format!("&height={MAIN_HEIGHT}"), ""),
                "missing_field",
            ),
            (link.replace(key, &format!("{key}x")), "invalid_field"),
            (
                format!(
                    "{link}&desc={}",
                    "a".repeat(liberated_payment::MAX_LINK_LENGTH)
                ),
                "too_long",
            ),
        ];
        for (text, expected) in cases {
            let kind = error_kind(&parse_card(&text).err().unwrap());
            assert_eq!(kind, expected);
            // The reported category, and the message built from it, carry nothing of the key.
            assert!(!format!("invalid gift card link: {kind}").contains(key));
        }
    }
}
