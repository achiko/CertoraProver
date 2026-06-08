pragma solidity 0.8.24;

import "./SomeInterface.sol";

contract RequiringStrongInvariants {
    uint256 storageValue = 1;

    function doHavocALL(SomeInterface token) external {
        address(token).call(abi.encodeWithSignature("havocALL()"));
    }

    function doHavocALLDelegate(SomeInterface token) external {
        address(token).delegatecall(abi.encodeWithSignature("havocALL()"));
    }
}
