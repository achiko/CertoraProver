#![no_std]
use cvlr::{cvlr_assert, cvlr_assume, nondet};

#[no_mangle]
#[inline(never)]
fn add(x: u64, y: u64) -> u64 {
    return x + y;
}

// Both asserts pass
#[no_mangle]
#[inline(never)]
fn sunbeam_multi_assert_both_pass() {
    let x: u64 = nondet();
    let y: u64 = nondet();
    cvlr_assume!(x > 0 && x < 1_000_000);
    cvlr_assume!(y > 0 && y < 1_000_000);
    let res = add(x, y);
    cvlr_assert!(res > x);
    cvlr_assert!(res > y);
}

// First passes, second fails.
#[no_mangle]
#[inline(never)]
fn sunbeam_multi_assert_second_fails() {
    let x: u64 = nondet();
    let y: u64 = nondet();
    cvlr_assume!(x > 0 && x < 1_000_000);
    cvlr_assume!(y > 0 && y < 1_000_000);
    let res = add(x, y);
    cvlr_assert!(res > x);
    cvlr_assert!(res > 3_000_000);
}
