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
use secrecy::zeroize::Zeroize;
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

/// The parsed, JNI-ready form of a card. The seed is zeroized on drop.
pub(crate) struct ParsedCard {
    pub(crate) origin: jint,
    pub(crate) network_id: jint,
    pub(crate) birthday_height: jlong,
    /// The stated amount in zatoshis, or `-1` when the link states none.
    pub(crate) amount: jlong,
    pub(crate) description: Option<String>,
    pub(crate) seed: [u8; 64],
    pub(crate) funding_address: String,
}

impl Drop for ParsedCard {
    fn drop(&mut self) {
        self.seed.zeroize();
    }
}

/// Parses `link` and derives everything the Kotlin layer needs from it.
///
/// Only Mainnet and Testnet cards are accepted, matching the networks this SDK runs on.
pub(crate) fn parse_card(link: &str) -> Result<ParsedCard, liberated_payment::Error> {
    let card = LiberatedPayment::parse(link)?;
    let (network_id, params) = match card.network() {
        NetworkType::Main => (1, Network::MainNetwork),
        NetworkType::Test => (0, Network::TestNetwork),
        NetworkType::Regtest => return Err(liberated_payment::Error::UnsupportedNetwork),
    };
    let funding_address = card.funding_address(&params)?;
    let seed = card.seed()?;
    let amount = match card.amount() {
        Some(zat) => jlong::try_from(zat.into_u64())
            .map_err(|_| liberated_payment::Error::InvalidField("amount"))?,
        None => -1,
    };
    Ok(ParsedCard {
        origin: origin_code(card.origin()),
        network_id,
        birthday_height: jlong::from(card.birthday_height()),
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
    let seed = crate::utils::rust_bytes_to_java(env, &card.seed)?;
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
    use super::*;

    /// The host the SDK's own links are served from. Only tests build links: the SDK reads them.
    const HOST: &str = "https://gift.zodl.com/";

    // A published, never-funded reference vector of the legacy `v3` encoding (32 zero bytes of
    // entropy); the same vector `liberated_payment.rs` tests against.
    const LEGACY_V3: &str = "https://legacy.example/payment-links/open#v3=WyJtYWluIiwiQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQSIsMzQ4MzE0MSwiMTAwMDAwMCIsImtuaWdodE1hZ2ljIiwxMS4xNzQ3LCJJdCdzIGEgZ3JlYXQgZGF5IHRvIHNoaWVsZCB5b3VyIFpFQyDwn5uh77iPIl0";

    #[test]
    fn parses_a_legacy_link_for_the_jni_layer() {
        let card = parse_card(LEGACY_V3).unwrap();
        assert_eq!(card.origin, 3);
        assert_eq!(card.network_id, 1);
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
        assert_eq!(card.seed, expected);
        // An Orchard-only mainnet unified address.
        assert!(card.funding_address.starts_with("u1"));
    }

    #[test]
    fn parses_a_v1_link_and_reports_its_network_and_funding_address() {
        let secret =
            liberated_payment::derive_payment_secret(&Network::TestNetwork, &[7; 32], 0).unwrap();
        let card = LiberatedPayment::new(NetworkType::Test, secret, 4_000_000, None, None).unwrap();
        let link = card.to_link(HOST).unwrap();
        let parsed = parse_card(&link).unwrap();
        assert_eq!(parsed.origin, 0);
        assert_eq!(parsed.network_id, 0);
        assert_eq!(parsed.amount, -1);
        assert_eq!(parsed.description, None);
        assert_eq!(parsed.seed, card.seed().unwrap());
        assert_eq!(
            parsed.funding_address,
            card.funding_address(&Network::TestNetwork).unwrap()
        );
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
        let link = LiberatedPayment::new(NetworkType::Regtest, [1; 32], 10, None, None)
            .unwrap()
            .to_link(HOST)
            .unwrap();
        assert_eq!(
            parse_card(&link).err().map(|e| error_kind(&e)),
            Some("unsupported_network")
        );
    }

    #[test]
    fn categorizes_rejected_links() {
        let link = LiberatedPayment::new(NetworkType::Main, [1; 32], 3_500_000, None, None)
            .unwrap()
            .to_link(HOST)
            .unwrap();
        let key = link.split('&').nth(1).unwrap().trim_start_matches("key=");
        let cases = [
            // The host is not part of the payment: any `https` URL is read, so only a link
            // without a fragment or with another scheme is "not a gift link".
            (
                link.split_once('#').unwrap().0.to_owned(),
                "not_a_gift_link",
            ),
            (link.replacen("https://", "http://", 1), "not_a_gift_link"),
            (link.replace("v=1", "v=9"), "unsupported_version"),
            (format!("{link}&height=1"), "duplicate_field"),
            (link.replace("&height=3500000", ""), "missing_field"),
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
