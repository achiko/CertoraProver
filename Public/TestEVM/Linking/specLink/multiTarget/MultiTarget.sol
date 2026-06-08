// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.0;

interface IToken {
    function getValue() external view returns (uint);
}

contract TokenA is IToken {
    function getValue() external pure returns (uint) {
        return 42;
    }
}

contract TokenB is IToken {
    function getValue() external pure returns (uint) {
        return 100;
    }
}

contract TokenC is IToken {
    function getValue() external pure returns (uint) {
        return 999;
    }
}

contract TokenD is IToken {
    function getValue() external pure returns (uint) {
        return 666;
    }
}

contract Main {
    mapping(uint256 => address) public registryA;
    mapping(uint256 => address) public registryB;

    // Dispatch through registryA or registryB depending on flag.
    // At the call site, the address may come from either storage read,
    // exercising multi-target wildcard link resolution.
    function dispatch(bool useA, uint256 key) external view returns (uint) {
        address target;
        if (useA) {
            target = registryA[key];
        } else {
            target = registryB[key];
        }
        return IToken(target).getValue();
    }

    // Dispatch through registryA or registryB depending on flag.
    // At the call site, the address may come from either storage read,
    // exercising multi-target wildcard link resolution.
    function dispatchStorage(bool useA, uint256 key) external view returns (uint) {
        mapping(uint256 => address) storage target = registryA;
        if (!useA) {
            target = registryB;
        }
        return IToken(target[key]).getValue();
    }
}
