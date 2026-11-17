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

    fn create_proof<R: rand::Rng>(&self, _circuit: Spend, _rng: &mut R) -> Self::Proof {
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

    fn create_proof<R: rand::Rng>(&self, _circuit: Output, _rng: &mut R) -> Self::Proof {
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
    use zcash_client_backend::data_api::wallet::{SpendingKeys, create_proposed_transactions};
    use zcash_client_backend::wallet::OvkPolicy;

    use super::*;
    use crate::send_max::tests::{
        Received, TestProposal, TestWallet, external_recipient, funded_wallet, propose_max_to,
    };

    /// The user's Send Max of the test wallet's only note to a recipient outside it.
    fn propose(st: &mut TestWallet) -> TestProposal {
        let recipient = external_recipient(st);
        propose_max_to(st, recipient)
    }

    #[test]
    fn non_sapling_send_max_builds_without_sapling_parameters() {
        let mut st = funded_wallet(Received::Orchard);
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
        let clock = st.clock().clone();
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
            &clock,
            &mut crate::system_rng(),
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
        let mut st = funded_wallet(Received::Sapling);
        let proposal = propose(&mut st);
        assert!(proposal_requires_sapling_proofs(&proposal));
    }
}
