using CertoraUnresolvedHarness as harness;

methods {
    function target() external returns (address) envfree;
    function lastResult() external returns (uint256) envfree;
    function harness.originalCallee() external returns (address) envfree;
}

// Count calls originating from outside the harness.
// This filters out the harness's internal this.getResult() self-calls.
persistent ghost mathint redirectedCallCount {
    init_state axiom redirectedCallCount == 0;
}

hook CALL(uint g, address addr, uint value, uint argsOffset, uint argsLength, uint retOffset, uint retLength) uint rc {
    if (executingContract != harness) {
        redirectedCallCount = redirectedCallCount + 1;
    }
}

// One unresolved call = one redirected call to the harness
rule singleRedirectedCall {
    env e;
    address t;
    require t != 0;
    require redirectedCallCount == 0;
    callUnresolved(e, t);
    assert redirectedCallCount == 1;
    assert harness.originalCallee() == t;
}

// Two unresolved calls = two redirected calls to the harness
rule twoRedirectedCalls {
    env e;
    address t1;
    address t2;
    require t1 != 0 && t2 != 0;
    require t1 != t2;
    require redirectedCallCount == 0;
    callTwoUnresolved(e, t1, t2);
    assert redirectedCallCount == 2;
}
