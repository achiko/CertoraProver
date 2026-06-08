using CertoraUnresolvedHarness as harness;

methods {
    function target() external returns (address) envfree;
    function lastResult() external returns (uint256) envfree;
    function harness.originalCallee() external returns (address) envfree;
    function harness.callersSender() external returns (address) envfree;
    function harness.executingAddr() external returns (address) envfree;
    function harness.inSize() external returns (uint256) envfree;
    function harness.outSize() external returns (uint256) envfree;
    function harness.callValue() external returns (uint256) envfree;
    function harness.callGas() external returns (uint256) envfree;

    // Override harness helpers with CVL summaries returning different values
    function harness.getResult(bytes4) external returns (uint256) => cvlGetResult();
    function harness.getResultPair() external returns (bool, uint256) => cvlGetResultPair();
}

function cvlGetResult() returns uint256 {
    return 99;
}

function cvlGetResultPair() returns (bool, uint256) {
    return (false, 77);
}

// Ghost for tracking void calls via CALL hook
persistent ghost bool voidCallHappened {
    init_state axiom !voidCallHappened;
}
persistent ghost address tGhost;

hook CALL(uint g, address addr, uint value, uint argsOffset, uint argsLength, uint retOffset, uint retLength) uint rc {
    if (addr == tGhost && argsLength == 0 && retLength == 0) {
        voidCallHappened = true;
    }
}

// Single return: CVL summary returns 99 instead of concrete 42
rule cvlSummarySingleReturn {
    env e;
    address t;
    require t != 0;
    uint256 result = callUnresolved(e, t);
    assert result == 99;
}

// Double return: CVL summary returns (false, 77) instead of concrete (true, 42)
rule cvlSummaryDoubleReturn {
    env e;
    address t;
    require t != 0;
    bool flag;
    uint256 val;
    flag, val = callUnresolvedTwoReturns(e, t);
    assert flag == false;
    assert val == 77;
}

// Void call: ghost updated by CALL hook detecting the no-return call
rule cvlSummaryVoidCall {
    env e;
    address t;
    require t != 0;
    tGhost = t;
    require !voidCallHappened;
    callUnresolvedNoReturn(e, t);
    assert voidCallHappened;
}
