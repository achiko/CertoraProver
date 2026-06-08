ghost bool hookAccessed;

hook Sstore currentContract.someField uint256 hook_store_newValue (uint256 hook_store_oldValue) {
    if (hook_store_newValue > hook_store_oldValue || hook_store_newValue <= hook_store_oldValue) {
        hookAccessed = true;
    }
}

hook Sload uint256 hook_load_value currentContract.someField {
    if (hook_load_value > 0) {
        hookAccessed = true;
    }
}

rule hookExample() {
    hookAccessed = false;
    env e;
    uint x;
    setSomeField(e, x);
    assert !hookAccessed;
}

rule hookExampleCalldataArgs() {
    hookAccessed = false;
    env e;
    calldataarg args;
    setSomeField(e, args);
    assert !hookAccessed;
}