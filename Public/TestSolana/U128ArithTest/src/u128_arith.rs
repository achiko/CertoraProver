//! Some rules for Rust u128

use cvlr::prelude::*;
use cvlr::mathint::NativeInt;

#[rule]
pub fn rule_shift_left() {
    let x: u128 = nondet();
    let s: u64 = nondet();
    cvlr_assume!(x <= 1000);
    cvlr_assume!(s <= 93);
    let res = x << s;
    cvlr::clog!(x, s, res);
    cvlr_assert!(res <= 99_035_203_142_830_420_000_000_000_000_000u128);
}

#[rule]
pub fn rule_shift_right() {
    let x: u128 = nondet();
    let s: u64 = nondet();
    cvlr_assume!(x <= 99_035_203_142_830_420_000_000_000_000_000u128);
    cvlr_assume!(s >= 30 && s < 128);
    let res = x >> s;
    cvlr::clog!(x, s, res);
    cvlr_assert!(res <= 92_233_720_368_547_760_000_000u128);
}

// Tests for u128 wrapping_sub

#[rule]
pub fn check_u128_wrapping_sub() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x == 5);
    cvlr_assume!(y == 10);

    let z: u128 = x.wrapping_sub(y);
    clog!(x, y, z);
    cvlr_assert!(z == 0xffff_ffff_ffff_ffff_ffff_ffff_ffff_fffb);
}

#[rule]
pub fn check_u128_wrapping_sub_fail() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x == 5);
    cvlr_assume!(y == 11);

    let z: u128 = x.wrapping_sub(y);
    clog!(x, y, z);
    cvlr_assert!(z == 0xffff_ffff_ffff_ffff_ffff_ffff_ffff_fffb);
}

#[rule]
pub fn check_u128_saturating_and_wrapping_sub_equiv() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x >= y);

    let z_sat: u128 = saturating_sub(x, y);
    let z_wrap: u128 = x.wrapping_sub(y);
    clog!(x, y, z_sat, z_wrap);

    cvlr_assert!(z_sat == z_wrap);
}

#[rule]
pub fn check_u128_saturating_and_wrapping_sub_equiv_fail() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    //cvlr_assume!(x >= y);

    let z_sat: u128 = saturating_sub(x, y);
    let z_wrap: u128 = x.wrapping_sub(y);
    clog!(x, y, z_sat, z_wrap);

    cvlr_assert!(z_sat == z_wrap);
}

#[rule]
pub fn check_u128_checked_and_wrapping_sub_equiv() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    let z_no_overflow: u128 = x.checked_sub(y).unwrap();
    let z_wrap: u128 = x.wrapping_sub(y);
    clog!(x, y, z_no_overflow, z_wrap);

    cvlr_assert!(z_no_overflow == z_wrap);
}

#[rule]
pub fn check_u128_wrapping_add() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x == 0xffff_ffff_ffff_ffff_ffff_ffff_ffff_fffb);
    cvlr_assume!(y == 5);

    let z: u128 = x.wrapping_add(y);
    clog!(x, y, z);
    cvlr_assert!(z == 0);
}

#[rule]
pub fn check_u128_saturating_and_wrapping_add_equiv() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(NativeInt::from(x) + NativeInt::from(y) < NativeInt::from(u128::MAX));

    let z_sat: u128 = saturating_add(x, y);
    let z_wrap: u128 = x.wrapping_add(y);
    clog!(x, y, z_sat, z_wrap);
    cvlr_assert!(z_sat == z_wrap);
}

#[rule]
pub fn check_u128_saturating_and_wrapping_add_equiv_fail() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    //cvlr_assume!(NativeInt::from(x) + NativeInt::from(y) < NativeInt::from(u128::MAX));

    let z_sat: u128 = saturating_add(x, y);
    let z_wrap: u128 = x.wrapping_add(y);
    clog!(x, y, z_sat, z_wrap);

    cvlr_assert!(z_sat == z_wrap);
}

#[rule]
pub fn check_u128_checked_and_wrapping_add_equiv() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    let z_no_overflow: u128 = x.checked_add(y).unwrap();
    let z_wrap: u128 = x.wrapping_add(y);
    clog!(x, y, z_no_overflow, z_wrap);

    cvlr_assert!(z_no_overflow == z_wrap);
}



#[inline(never)]
fn saturating_sub(a: u128, b: u128) -> u128 {
    let a = NativeInt::from(a);
    let b = NativeInt::from(b);
    if a < b {
        0
    } else {
        (a - b).into()
    }
}

#[inline(never)]
fn saturating_add(a: u128, b: u128) -> u128 {
    let res = NativeInt::from(a) + NativeInt::from(b);
    if res >= NativeInt::from(u128::MAX) {
        u128::MAX
    } else {
        res.into()
    }
}
