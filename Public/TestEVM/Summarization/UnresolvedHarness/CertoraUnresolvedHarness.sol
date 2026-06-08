pragma solidity ^0.8.0;

contract CertoraUnresolvedHarness {
    // These slots are written by the prover before redirecting:
    // slot 0: original callee address
    // slot 1: msg.sender of the caller's caller
    // slot 2: executing contract address
    address public originalCallee;      // slot 0
    address public callersSender;       // slot 1
    address public executingAddr;       // slot 2
    uint256 public inSize;              // slot 3
    uint256 public outSize;             // slot 4
    uint256 public callValue;           // slot 5
    uint256 public callGas;             // slot 6

    // External helpers callable from the fallback, summarizable in CVL
    function getResult(bytes4 selector) external returns (uint256) {
        return 42;
    }

    function getResultPair() external returns (bool, uint256) {
        return (true, 42);
    }

    fallback() external payable {
        bytes4 selector;
        if (msg.data.length >= 4) {
            selector = bytes4(msg.data[:4]);
        }
        if (outSize == 64) {
            // Two-element return: (bool, uint256)
            (bool flag, uint256 val) = this.getResultPair();
            bytes memory ret = abi.encode(flag, val);
            assembly { return(add(ret, 0x20), mload(ret)) }
        } else if (inSize == 0 && outSize == 0) {
            // Truly no-op call (e.g. callUnresolvedNoReturn) — return nothing
        } else {
            // Default: return single uint256. Works for both outSize==0 from
            // high-level .call() (which uses RETURNDATACOPY) and outSize==32
            // from assembly call.
            uint256 result = this.getResult(selector);
            bytes memory ret = abi.encode(result);
            assembly { return(add(ret, 0x20), mload(ret)) }
        }
    }
}
