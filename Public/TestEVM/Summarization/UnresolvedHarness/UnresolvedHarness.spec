using CertoraUnresolvedHarness as harness;

methods {
    function target() external returns (address) envfree;
    function resolved() external returns (address) envfree;
    function lastResult() external returns (uint256) envfree;
    function harness.originalCallee() external returns (address) envfree;
    function harness.callersSender() external returns (address) envfree;
    function harness.executingAddr() external returns (address) envfree;
    function harness.inSize() external returns (uint256) envfree;
    function harness.outSize() external returns (uint256) envfree;
    function harness.callValue() external returns (uint256) envfree;
    function harness.callGas() external returns (uint256) envfree;
}

rule harnessReceivesCallee {
    env e;
    address t;
    require t != 0;
    callUnresolved(e, t);
    assert harness.originalCallee() == t;
}

rule harnessReceivesSender {
    env e;
    address t;
    require t != 0;
    callUnresolved(e, t);
    assert harness.callersSender() == e.msg.sender;
}

rule harnessReceivesExecutingContract {
    env e;
    address t;
    require t != 0;
    callUnresolved(e, t);
    assert harness.executingAddr() == currentContract;
}

rule returnValueIs42 {
    env e;
    address t;
    require t != 0;
    uint256 result = callUnresolved(e, t);
    assert result == 42;
}

rule twoUnresolvedCalls {
    env e;
    address t1;
    address t2;
    require t1 != 0 && t2 != 0;
    require t1 != t2;
    callTwoUnresolved(e, t1, t2);
    assert harness.originalCallee() == t2;
}

rule harnessReceivesInSize {
    env e;
    address t;
    require t != 0;
    callUnresolved(e, t);
    // getValue() selector is 4 bytes of calldata
    assert harness.inSize() == 4;
}

rule harnessReceivesOutSize {
    env e;
    address t;
    require t != 0;
    callUnresolved(e, t);
    // low-level call doesn't specify expected return size
    assert harness.outSize() == 0;
}

rule harnessReceivesValue {
    env e;
    require e.msg.value == 0;
    address t;
    require t != 0;
    callUnresolved(e, t);
    assert harness.callValue() == 0;
}

rule harnessReceivesGas {
    env e;
    address t;
    require t != 0;
    callUnresolved(e, t);
    satisfy harness.callGas() > 0;
}

// callUnresolvedWithDetails uses explicit gas=50000, retsize=32, inSize=100 (4 + 3*32)
rule detailedCallGas {
    env e;
    address t;
    require t != 0;
    callUnresolvedWithDetails(e, t);
    assert harness.callGas() == 50000;
}

rule detailedCallOutSize {
    env e;
    address t;
    require t != 0;
    callUnresolvedWithDetails(e, t);
    assert harness.outSize() == 32;
}

rule detailedCallInSize {
    env e;
    address t;
    require t != 0;
    callUnresolvedWithDetails(e, t);
    // compute(uint256,uint256,uint256): 4-byte selector + 3 * 32-byte args = 100
    assert harness.inSize() == 100;
}

rule zeroReturnSize {
    env e;
    address t;
    require t != 0;
    callUnresolvedNoReturn(e, t);
    assert harness.outSize() == 0;
    assert harness.inSize() == 0;
}

rule twoElementReturn {
    env e;
    address t;
    require t != 0;
    bool flag;
    uint256 val;
    flag, val = callUnresolvedTwoReturns(e, t);
    // check(address): 4 + 32 = 36 bytes input, 64 bytes output
    assert harness.outSize() == 64;
    assert harness.inSize() == 36;
    // The harness fallback returns (true, 42) via getResultPair
    assert flag == true;
    assert val == 42;
}

// Resolved call via linking returns 7 (from Resolved contract), not 42 (from harness)
rule resolvedCallNotRedirected {
    env e;
    uint256 result = callResolved(e);
    assert result == 7;
}
