#![no_std]

use cvlr::cvlr_assert;
use cvlr_soroban_derive::rule;
use soroban_sdk::Env;
use soroban_sdk::Symbol;
use soroban_sdk::symbol_short;

// package functions

#[rule]
  pub fn dummy_rule(e: &Env) {
   let sym1 = Symbol::new(&e, "toolongtobeshort");
   let sym2 = Symbol::new(&e, "toolongtobeshort");
   cvlr_assert!(sym1 == sym2);
}

#[rule]
  pub fn dummy_rule2() {
   let sym1 = symbol_short!("foo");
   let sym2 = symbol_short!("foo");
   cvlr_assert!(sym1 == sym2);
}
