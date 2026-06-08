# CertoraUnresolvedHarness

## Overview

The `--use_unresolved_harness` flag redirects external calls that would normally be
havoc'd (because their target is unresolved) to a user-provided contract named
`CertoraUnresolvedHarness`. The prover writes the call context into the harness's
storage before invoking its fallback function, giving the user full control over
the behavior of unresolved calls.

## Enabling

In your `.conf` file:

```json
{
    "files": ["MyContract.sol", "CertoraUnresolvedHarness.sol"],
    "verify": "MyContract:MySpec.spec",
    "prover_args": ["-useUnresolvedHarness true"]
}
```

The harness contract must be named exactly `CertoraUnresolvedHarness` and must be
included in the scene (listed in `files`).

## Storage Slot Layout

The prover writes the following values to the harness's storage **before** invoking
the fallback. All 7 slots must be declared as public storage variables in exactly
this order:

| Slot | Type      | Name             | Description                                          |
|------|-----------|------------------|------------------------------------------------------|
| 0    | `address` | `originalCallee` | The address the caller was trying to call            |
| 1    | `address` | `callersSender`  | `msg.sender` of the context performing the call      |
| 2    | `address` | `executingAddr`  | The contract executing the call instruction          |
| 3    | `uint256` | `inSize`         | Size of the call's input data (calldata) in bytes    |
| 4    | `uint256` | `outSize`        | Expected return data size in bytes                   |
| 5    | `uint256` | `callValue`      | ETH value sent with the call                         |
| 6    | `uint256` | `callGas`        | Gas forwarded to the call                            |

**Important:** The slot positions are determined by declaration order in Solidity.
All 7 variables must be the first 7 storage declarations in the contract, in the
order shown above.

## Template Harness

```solidity
pragma solidity ^0.8.0;

contract CertoraUnresolvedHarness {
    // === Prover-written storage slots (must be first, in this order) ===
    address public originalCallee;      // slot 0
    address public callersSender;       // slot 1
    address public executingAddr;       // slot 2
    uint256 public inSize;              // slot 3
    uint256 public outSize;             // slot 4
    uint256 public callValue;           // slot 5
    uint256 public callGas;             // slot 6

    // === External helpers (summarizable in CVL) ===

    // Single uint256 return
    function getResult(bytes4 selector) external returns (uint256) {
        return 42;
    }

    // Two-element return: (bool, uint256)
    function getResultPair() external returns (bool, uint256) {
        return (true, 42);
    }

    // === Fallback ===

    fallback() external payable {
        bytes4 selector;
        if (msg.data.length >= 4) {
            selector = bytes4(msg.data[:4]);
        }

        if (outSize == 64) {
            // Two-element return
            (bool flag, uint256 val) = this.getResultPair();
            bytes memory ret = abi.encode(flag, val);
            assembly { return(add(ret, 0x20), mload(ret)) }
        } else if (inSize == 0 && outSize == 0) {
            // Truly no-op call — return nothing
        } else {
            // Default: return single uint256. Works for both outSize==0
            // (high-level .call() uses RETURNDATACOPY) and outSize==32.
            uint256 result = this.getResult(selector);
            bytes memory ret = abi.encode(result);
            assembly { return(add(ret, 0x20), mload(ret)) }
        }
    }
}
```

### Key design points

1. **External helper functions** (`getResult`, `getResultPair`) make external calls
   to `this`, which allows them to be summarized in CVL. The fallback delegates to
   these helpers so you can control return values from your spec.

2. **Branching on `outSize`** lets the fallback return the right number of bytes for
   different call sites. The prover writes `outSize` to slot 4 before the fallback
   runs, so it is available for dispatch.

3. **The `selector` variable** (extracted from `msg.data`) is passed to `getResult`,
   allowing CVL summaries to differentiate behavior based on which function was
   originally called.

It is important to note that these default 7 storage variables **must** be declared.
Additional storage variables can be added after them.
One can think of the first 7 slots as ghost states maintained by the harness.
In CVL terms, those are _non-persistent_ ghosts; if the harness reverts, their values will revert.
If there are multiple calls to the harness from a summary, they would be overwritten.
To illustrate it, if the harness (ext-)calls another contract, which is unresolved
and thus triggers the inlining of the harness contract within the same call chain,
the 7 storage variables are overwrritten and hence the original call
frame of the harness would see the _new_ values.
This can be easily worked around by, e.g., storing their values in memory (local variables).

## CVL Spec Example

```cvl
using CertoraUnresolvedHarness as harness;

methods {
    // Declare harness slot getters as envfree
    function harness.originalCallee() external returns (address) envfree;
    function harness.callersSender()  external returns (address) envfree;
    function harness.executingAddr()  external returns (address) envfree;
    function harness.inSize()         external returns (uint256) envfree;
    function harness.outSize()        external returns (uint256) envfree;
    function harness.callValue()      external returns (uint256) envfree;
    function harness.callGas()        external returns (uint256) envfree;

    // Summarize the helper — or leave unsummarized to get the concrete value (42)
    // function harness.getResult(bytes4) external returns (uint256) => NONDET;
}

rule checkCalleeIsRecorded {
    env e;
    address t;
    require t != 0;
    myFunction(e, t);
    assert harness.originalCallee() == t;
}
```

## Filtering CALL Opcode Hooks

When `--use_unresolved_harness` is active, redirected calls become `CALL`s to the
harness contract. The harness fallback may also make internal `CALL`s to its own
helper functions (e.g., `this.getResult()`).

To **count only the original redirected calls** (excluding the harness's internal
self-calls to helpers like `this.getResult()`), filter by `executingContract`:

```cvl
persistent ghost mathint redirectedCallCount {
    init_state axiom redirectedCallCount == 0;
}

hook CALL(uint g, address addr, uint value, uint argsOffset, uint argsLength,
          uint retOffset, uint retLength) uint rc {
    if (executingContract != harness) {
        // Only fires for calls originating outside the harness
        redirectedCallCount = redirectedCallCount + 1;
    }
}
```

To recover the original callee address (before redirection), read slot 0:

```cvl
assert harness.originalCallee() == expectedTarget;
```

## Limitations

- **Delegate calls** are not redirected (they continue to receive havoc summaries).
- **Explicit CVL summaries** take priority — if you have a matching summary in your
  `methods` block, it will be used instead of the harness redirect.
- The harness contract must have a `fallback` function (the prover resolves to it).
