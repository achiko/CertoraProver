use cvlr::prelude::*;
use cvlr_solana::cvlr_deserialize_nondet_accounts;
use solana_program::account_info::next_account_info;

extern "C" {
    pub fn CVT_is_account_written(ptr: *const u8) -> bool;
}

pub fn cvlr_is_account_written(account: &solana_program::account_info::AccountInfo) -> bool {
    let data = account.try_borrow_data().unwrap();
    unsafe { CVT_is_account_written(data.as_ptr()) }
}

// Three accounts a, b, c.
// Both calls receive c as a trailing account, but add_counter always picks
// the first element of its slice, so only a and b are ever written.
// The rule checks that cvlr_is_account_written agrees.
#[rule]
pub fn rule_is_written_two_of_three() {
    let account_infos = cvlr_deserialize_nondet_accounts();
    let iter = &mut account_infos.iter();
    let a = next_account_info(iter).unwrap();
    let b = next_account_info(iter).unwrap();
    let c = next_account_info(iter).unwrap();

    // Writes to a — b and c are also in the slice but not consumed.
    crate::add_counter(a.owner, &account_infos).unwrap();

    // Writes to b — c is still in the tail but is not consumed.
    crate::add_counter(b.owner, &account_infos[1..]).unwrap();

    cvlr_assert!(cvlr_is_account_written(a));
    cvlr_assert!(cvlr_is_account_written(b));
    cvlr_assert!(!cvlr_is_account_written(c));
}

#[rule]
pub fn rule_is_written_two_of_three_fail() {
    let account_infos = cvlr_deserialize_nondet_accounts();
    let iter = &mut account_infos.iter();
    let a = next_account_info(iter).unwrap();
    let b = next_account_info(iter).unwrap();
    let c = next_account_info(iter).unwrap();

    // Writes to a — b and c are also in the slice but not consumed.
    crate::add_counter(a.owner, &account_infos).unwrap();

    // Writes to b — c is still in the tail but is not consumed.
    crate::add_counter(b.owner, &account_infos[1..]).unwrap();

    cvlr_assert!(cvlr_is_account_written(a));
    cvlr_assert!(!cvlr_is_account_written(b));
    cvlr_assert!(!cvlr_is_account_written(c));
}