
methods{
    function _.havocALL() external => HAVOC_ALL;
}

strong invariant storageValueIsOne_strong() 1 == currentContract.storageValue;
weak invariant storageValueIsOne_weak() 1 == currentContract.storageValue;

rule simpleRuleRequiringStrongInvariant(){
    requireInvariant(storageValueIsOne_strong());
    env e;
    address token;
    doHavocALL(e, token);
    assert currentContract.storageValue == 1;
}

rule simpleRuleRequiringStrongInvariantDelegate(){
    requireInvariant(storageValueIsOne_strong());
    env e;
    address token;
    doHavocALLDelegate(e, token);
    assert currentContract.storageValue == 1;
}

rule simpleRuleRequiringWeakInvariant(){
    requireInvariant(storageValueIsOne_weak());
    env e;
    address token;
    doHavocALL(e, token);
    assert currentContract.storageValue == 1;
}
