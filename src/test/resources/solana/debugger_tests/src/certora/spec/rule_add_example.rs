use cvlr::prelude::*;

#[rule]
pub fn rule_add_example() {
    let input_a: u64 = nondet();
    let input_b: u64 = nondet();
    if(input_a > 0 && input_b > 0){
        let z = input_a + input_b;
        cvlr_assert!(z != 10);
    }
}