//! Sapling proving for transactions built from a proposal.
//!
//! The Sapling parameter files (about 50 MB) are needed only to prove Sapling spends and
//! outputs. A proposal that neither spends a Sapling note nor creates a Sapling output
//! (payments and change alike) builds without them, so a wallet that never touches Sapling
//! does not have to download them in order to send.

use sapling::{
    Diversifier, MerklePath, PaymentAddress, ProofGenerationKey, Rseed,
    bundle::GrothProofBytes,
    circuit::{Output, Spend},
    keys::EphemeralSecretKey,
    prover::{OutputProver, SpendProver},
    value::{NoteValue, ValueCommitTrapdoor},
};
use zcash_client_backend::{fees::ChangeValue, proposal::Proposal, wallet::Note};
use zcash_protocol::PoolType;

/// Returns `true` if building `proposal` requires Sapling proofs, and therefore the Sapling
/// parameter files.
///
/// That is the case when any step spends a Sapling note, pays to a Sapling receiver, or sends
/// change to the Sapling pool.
pub(crate) fn proposal_requires_sapling_proofs<FeeRuleT, NoteRef>(
    proposal: &Proposal<FeeRuleT, NoteRef>,
) -> bool {
    proposal.steps().iter().any(|step| {
        let spends_sapling = step.shielded_inputs().is_some_and(|inputs| {
            inputs
                .notes()
                .iter()
                .any(|note| matches!(note.note(), Note::Sapling(_)))
        });
        let pays_to_sapling = step
            .payment_pools()
            .values()
            .any(|pool| *pool == PoolType::SAPLING);
        let changes_to_sapling = step
            .balance()
            .proposed_change()
            .iter()
            .any(|change: &ChangeValue| change.output_pool() == PoolType::SAPLING);
        spends_sapling || pays_to_sapling || changes_to_sapling
    })
}

/// A Sapling prover for transactions that have no Sapling component.
///
/// It is only ever handed to the builder for a proposal that
/// [`proposal_requires_sapling_proofs`] has cleared, so none of its methods can be reached.
/// Should that invariant ever break, it panics rather than producing an invalid proof; the JNI
/// layer turns the panic into an exception.
pub(crate) struct NoSaplingProver;

const NO_SAPLING_PROVER: &str =
    "Sapling proving was requested for a transaction that was not expected to need it";

impl SpendProver for NoSaplingProver {
    type Proof = GrothProofBytes;

    fn prepare_circuit(
        _proof_generation_key: ProofGenerationKey,
        _diversifier: Diversifier,
        _rseed: Rseed,
        _value: NoteValue,
        _alpha: jubjub::Fr,
        _rcv: ValueCommitTrapdoor,
        _anchor: bls12_381::Scalar,
        _merkle_path: MerklePath,
    ) -> Option<Spend> {
        panic!("{}", NO_SAPLING_PROVER)
    }

    fn create_proof<R: rand::RngCore>(&self, _circuit: Spend, _rng: &mut R) -> Self::Proof {
        panic!("{}", NO_SAPLING_PROVER)
    }

    fn encode_proof(_proof: Self::Proof) -> GrothProofBytes {
        panic!("{}", NO_SAPLING_PROVER)
    }
}

impl OutputProver for NoSaplingProver {
    type Proof = GrothProofBytes;

    fn prepare_circuit(
        _esk: &EphemeralSecretKey,
        _payment_address: PaymentAddress,
        _rcm: jubjub::Fr,
        _value: NoteValue,
        _rcv: ValueCommitTrapdoor,
    ) -> Output {
        panic!("{}", NO_SAPLING_PROVER)
    }

    fn create_proof<R: rand::RngCore>(&self, _circuit: Output, _rng: &mut R) -> Self::Proof {
        panic!("{}", NO_SAPLING_PROVER)
    }

    fn encode_proof(_proof: Self::Proof) -> GrothProofBytes {
        panic!("{}", NO_SAPLING_PROVER)
    }
}

/// End to end against a synthetic wallet: a send-max of shielded non-Sapling funds builds with
/// [`NoSaplingProver`], so it never needs the Sapling parameters, while a Sapling-funded one is
/// recognized as needing them.
#[cfg(test)]
mod tests {
    use zcash_client_backend::data_api::{
        Account as _, MaxSpendMode,
        testing::{AddressType, TestBuilder, TestFvk, TestState},
        wallet::{
            ConfirmationsPolicy, SpendingKeys, create_proposed_transactions,
            input_selection::LockedInputPolicy, propose_send_max_transfer,
        },
    };
    use zcash_client_backend::{fees::StandardFeeRule, wallet::OvkPolicy};
    use zcash_client_sqlite::testing::{BlockCache, db::TestDbFactory};
    use zcash_keys::address::{Address, UnifiedAddress};
    use zcash_primitives::block::BlockHash;
    use zcash_protocol::value::Zatoshis;

    use super::*;
    use crate::send_max::SEND_MAX_POOLS;

    /// A wallet whose only funds are one note received by `fvk`, spendable under the default
    /// confirmations policy.
    fn funded_wallet<F: TestFvk>(
        fvk: impl FnOnce(&zcash_keys::keys::UnifiedSpendingKey) -> F,
    ) -> TestState<
        BlockCache,
        zcash_client_sqlite::testing::db::TestDb,
        zcash_protocol::local_consensus::LocalNetwork,
    > {
        let mut st = TestBuilder::new()
            .with_data_store_factory(TestDbFactory::default())
            .with_block_cache(BlockCache::new())
            .with_account_from_sapling_activation(BlockHash([0; 32]))
            .build();
        let fvk = fvk(st.test_account().unwrap().usk());
        let (h, _, _) = st.generate_next_block(
            &fvk,
            AddressType::DefaultExternal,
            Zatoshis::const_from_u64(100_000),
        );
        // Received funds need ten confirmations under the default policy.
        for _ in 0..10 {
            st.generate_empty_block();
        }
        st.scan_cached_blocks(h, 11);
        st
    }

    /// An Orchard-only unified address that belongs to nobody in the test wallet.
    fn external_recipient(
        st: &TestState<
            BlockCache,
            zcash_client_sqlite::testing::db::TestDb,
            zcash_protocol::local_consensus::LocalNetwork,
        >,
    ) -> zcash_address::ZcashAddress {
        let sk = orchard::keys::SpendingKey::from_bytes([9; 32]).unwrap();
        let address = orchard::keys::FullViewingKey::from(&sk)
            .address_at(0u32, orchard::keys::Scope::External);
        Address::from(UnifiedAddress::from_receivers(Some(address), None, None).unwrap())
            .to_zcash_address(st.network())
    }

    /// The same call `send_max::propose_send_max` makes, against the test wallet.
    fn propose(
        st: &mut TestState<
            BlockCache,
            zcash_client_sqlite::testing::db::TestDb,
            zcash_protocol::local_consensus::LocalNetwork,
        >,
    ) -> Proposal<StandardFeeRule, zcash_client_sqlite::ReceivedNoteId> {
        let account = st.test_account().unwrap().id();
        let recipient = external_recipient(st);
        let network = *st.network();
        propose_send_max_transfer::<_, _, _, std::convert::Infallible>(
            st.wallet_mut(),
            &network,
            account,
            &SEND_MAX_POOLS,
            &StandardFeeRule::Zip317,
            recipient,
            None,
            MaxSpendMode::MaxSpendable,
            ConfirmationsPolicy::default(),
            &LockedInputPolicy::Exclude,
            None,
        )
        .unwrap()
    }

    #[test]
    fn non_sapling_send_max_builds_without_sapling_parameters() {
        let mut st = funded_wallet(|usk| orchard::keys::FullViewingKey::from(usk.orchard()));
        let proposal = propose(&mut st);
        assert!(!proposal_requires_sapling_proofs(&proposal));
        // Send-max leaves no change.
        assert!(
            proposal
                .steps()
                .iter()
                .all(|s| s.balance().proposed_change().is_empty())
        );

        let usk = st.test_account().unwrap().usk().clone();
        let network = *st.network();
        let txids = create_proposed_transactions::<
            _,
            _,
            std::convert::Infallible,
            _,
            std::convert::Infallible,
            _,
        >(
            st.wallet_mut(),
            &network,
            &NoSaplingProver,
            &NoSaplingProver,
            &SpendingKeys::from_unified_spending_key(usk),
            OvkPolicy::Discard,
            &proposal,
            None,
        )
        .expect("a transaction without Sapling components must build without Sapling proving");
        assert_eq!(txids.len(), 1);
    }

    #[test]
    fn sapling_send_max_requires_sapling_parameters() {
        let mut st = funded_wallet(|usk| usk.sapling().to_diversifiable_full_viewing_key());
        let proposal = propose(&mut st);
        assert!(proposal_requires_sapling_proofs(&proposal));
    }
}
