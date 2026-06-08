using Main as main;
using TokenA as tokenA;
using TokenB as tokenB;
using TokenC as tokenC;
using TokenD as tokenD;

links {
    // registryA: concrete key 0 -> tokenA, wildcard -> tokenB
    main.registryA[0] => tokenA;
    main.registryA[_] => tokenB;
    // registryB: concrete key 0 -> tokenC, wildcard -> tokenD
    main.registryB[0] => tokenC;
    main.registryB[_] => tokenD;
}

// Branching dispatch: dispatch() reads from registryA or registryB depending
// on `useA`, then calls through the result. At the call site, the address may
// come from either storage read, exercising multi-path wildcard link resolution.

rule dispatch {
    env e;
    uint256 key;
    require key != 0;
    assert main.dispatch(e, true, key) == 100;
    assert main.dispatch(e, false, key) == 666;
}

rule concrete {
    env e;
    assert main.dispatch(e, true, 0) == 42;
    assert main.dispatch(e, false, 0) == 999;
}

// Imprecise: dispatchStorage uses a storage pointer that phi's registryA and
// registryB before the SLOAD, producing a single storage read from a merged wordmap.
// The wildcard precedence assumption merges disjuncts from both registries, so the
// solver can pick any wildcard target from either registry. We can only assert that
// the result is one of the two tokens linked as wildcards (tokenB or tokenD).
rule dispatchStorage {
    env e;
    uint256 key;
    require key != 0;
    uint valA = main.dispatchStorage(e, true, key);
    assert valA == 100 || valA == 666;
    uint valB = main.dispatchStorage(e, false, key);
    assert valB == 100 || valB == 666;
}

rule concreteStorage {
    env e;
    assert main.dispatchStorage(e, true, 0) == 42;
    assert main.dispatchStorage(e, false, 0) == 999;
}
