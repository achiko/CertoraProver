pragma solidity ^0.8.0;

contract Caller {
    address public target;
    address public resolved;
    uint256 public lastResult;

    function callUnresolved(address t) external returns (uint256) {
        target = t;
        (bool success, bytes memory data) = t.call(
            abi.encodeWithSignature("getValue()")
        );
        require(success);
        lastResult = abi.decode(data, (uint256));
        return lastResult;
    }

    function callTwoUnresolved(address t1, address t2) external returns (uint256) {
        (bool success1, ) = t1.call(abi.encodeWithSignature("doSomething()"));
        require(success1);
        (bool success2, bytes memory data2) = t2.call(abi.encodeWithSignature("getValue()"));
        require(success2);
        lastResult = abi.decode(data2, (uint256));
        return lastResult;
    }

    // Calls a linked (resolved) address — should be inlined normally, not redirected to harness
    function callResolved() external returns (uint256) {
        (bool success, bytes memory data) = resolved.call(
            abi.encodeWithSignature("getValue()")
        );
        require(success);
        lastResult = abi.decode(data, (uint256));
        return lastResult;
    }

    // No return data expected (outSize=0, inSize=0)
    function callUnresolvedNoReturn(address t) external {
        target = t;
        assembly {
            let success := call(gas(), t, 0, 0, 0, 0, 0)
            if iszero(success) { revert(0, 0) }
        }
    }

    // Expects 64 bytes back: (bool, uint256)
    function callUnresolvedTwoReturns(address t) external returns (bool, uint256) {
        target = t;
        bool flag;
        uint256 val;
        bytes memory input = abi.encodeWithSignature("check(address)", t);
        assembly {
            let success := call(gas(), t, 0, add(input, 0x20), mload(input), 0x00, 0x40)
            if iszero(success) { revert(0, 0) }
            flag := mload(0x00)
            val := mload(0x20)
        }
        return (flag, val);
    }

    // Variant with explicit gas, retsize, and a longer selector (3 args = 4 + 3*32 = 100 bytes)
    function callUnresolvedWithDetails(address t) external returns (uint256) {
        target = t;
        uint256 result;
        bytes memory input = abi.encodeWithSignature("compute(uint256,uint256,uint256)", 1, 2, 3);
        assembly {
            let success := call(
                50000,              // gas = 50000
                t,                  // target
                0,                  // value = 0
                add(input, 0x20),   // input data pointer
                mload(input),       // input size = 100
                0x00,               // output offset
                0x20                // output size = 32
            )
            if iszero(success) { revert(0, 0) }
            result := mload(0x00)
        }
        lastResult = result;
        return result;
    }
}
