//! Test rules for Rust u128 greater than operations

use cvlr::prelude::*;
use cvlr::mathint::NativeInt;

// Tests for u128 > comparisons

#[rule]
pub fn check_u128_gt_basic() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let result: bool = x > y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_lt_basic() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let result: bool = x < y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_gt_verified() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x > y);

    let result: bool = x > y;
    clog!(x, y, result);
    cvlr_assert!(result);
}


#[rule]
pub fn check_u128_lt_fail() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x > y);

    let result: bool = x < y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_gt_large_numbers() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    // Testing with values that span both high and low parts
    cvlr_assume!(x > (1u128 << 64) + 100);  // High part = 1, low part = 100
    cvlr_assume!(y < (1u128 << 64) + 100);   // High part = 1, low part = 50
    cvlr_assume!(y > (1u128 << 64) + 50);   // High part = 1, low part = 50

    let result: bool = x > y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn negative_test_check_u64_gt_basic() {
    let x: u64 = nondet();
    let y: u64 = nondet();
    let result: bool = x > y;
    clog!(x, y, result);
    cvlr_assert!(result);
}


#[rule]
pub fn check_u128_double_branch_simple() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let z: u128 = nondet();

    if x > y && y > z {
        clog!(x, y, z, "x > y > z path");
        cvlr_assert!(x > z);
    } else {
        clog!(x, y, z, "not x > y > z path");
        cvlr_assert!(!(x > y && y > z));
    }
}

#[rule]
pub fn check_u128_gt_with_native_math() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let rust_gt_result = x > y;

    let x_nat = NativeInt::from_u128(into_parts(x).0, into_parts(x).1);
    let y_nat = NativeInt::from_u128(into_parts(y).0, into_parts(y).1);
    let native_gt_result = x_nat > y_nat;

    compare(rust_gt_result, native_gt_result);
}

#[rule]
pub fn check_u128_gt_with_native_math_with_assert() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let rust_gt_result = x > y;

    let x_nat = NativeInt::from_u128(into_parts(x).0, into_parts(x).1);
    let y_nat = NativeInt::from_u128(into_parts(y).0, into_parts(y).1);
    let native_gt_result = x_nat > y_nat;

    cvlr_assert!(rust_gt_result == native_gt_result);
}


#[rule]
pub fn check_u128_lt_with_native_math() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let rust_lt_result = x < y;

    let x_nat = NativeInt::from_u128(into_parts(x).0, into_parts(x).1);
    let y_nat = NativeInt::from_u128(into_parts(y).0, into_parts(y).1);
    let native_lt_result = x_nat < y_nat;

    compare(rust_lt_result, native_lt_result);
}


#[rule]
pub fn check_u128_lt_with_native_math_with_assert() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let rust_lt_result = x < y;

    let x_nat = NativeInt::from_u128(into_parts(x).0, into_parts(x).1);
    let y_nat = NativeInt::from_u128(into_parts(y).0, into_parts(y).1);
    let native_lt_result = x_nat < y_nat;

     cvlr_assert!(rust_lt_result == native_lt_result);
}

#[inline(never)]
fn into_parts(x: u128) -> (u64, u64) {
    let x_high: u64 = (x >> 64) as u64;  // Shifts right 64 bits, takes remaining
    let x_low: u64 = x as u64;  // Takes lower 64 bits
    (x_low, x_high)
}


// Tests for u128 >= (GE) comparisons

#[rule]
pub fn check_u128_ge_basic() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let result: bool = x >= y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_ge_verified() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x >= y);

    let result: bool = x >= y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_ge_fail() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x < y);

    let result: bool = x >= y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_ge_with_native_math() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let rust_ge_result = x >= y;

    let x_nat = NativeInt::from_u128(into_parts(x).0, into_parts(x).1);
    let y_nat = NativeInt::from_u128(into_parts(y).0, into_parts(y).1);
    let native_ge_result = x_nat >= y_nat;

    cvlr_assert!(rust_ge_result == native_ge_result);
}

// Tests for u128 <= (LE) comparisons

#[rule]
pub fn check_u128_le_basic() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let result: bool = x <= y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_le_verified() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x <= y);

    let result: bool = x <= y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_le_fail() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x > y);

    let result: bool = x <= y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_le_with_native_math() {
    let x: u128 = nondet();
    let y: u128 = nondet();
    let rust_le_result = x <= y;

    let x_nat = NativeInt::from_u128(into_parts(x).0, into_parts(x).1);
    let y_nat = NativeInt::from_u128(into_parts(y).0, into_parts(y).1);
    let native_le_result = x_nat <= y_nat;

    cvlr_assert!(rust_le_result == native_le_result);
}

// Tests for relationships between comparisons

#[rule]
pub fn check_u128_ge_le_inverse() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    let ge_result = x >= y;
    let le_result = y <= x;

    clog!(x, y, ge_result, le_result);
    cvlr_assert!(ge_result == le_result);
}

#[rule]
pub fn check_u128_gt_ge_relationship() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x > y);

    let gt_result = x > y;
    let ge_result = x >= y;

    clog!(x, y, gt_result, ge_result);
    cvlr_assert!(gt_result && ge_result);
}

#[rule]
pub fn check_u128_lt_le_relationship() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    cvlr_assume!(x < y);

    let lt_result = x < y;
    let le_result = x <= y;

    clog!(x, y, lt_result, le_result);
    cvlr_assert!(lt_result && le_result);
}

#[rule]
pub fn check_u128_ge_large_numbers() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    // Testing with values that span both high and low parts
    cvlr_assume!(x >= (1u128 << 64) + 100);
    cvlr_assume!(y <= (1u128 << 64) + 100);

    let result: bool = x >= y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[rule]
pub fn check_u128_le_large_numbers() {
    let x: u128 = nondet();
    let y: u128 = nondet();

    // Testing with values that span both high and low parts
    cvlr_assume!(x <= (1u128 << 64) + 100);
    cvlr_assume!(y >= (1u128 << 64) + 100);

    let result: bool = x <= y;
    clog!(x, y, result);
    cvlr_assert!(result);
}

#[inline(never)]
fn compare(rust_res: bool, native_res: bool) {
    cvlr_assert!(rust_res == native_res);
}