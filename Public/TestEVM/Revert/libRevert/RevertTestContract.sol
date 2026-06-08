// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

import "./RevertLibrary.sol";

contract RevertTestContract {
    bool public fooWasCalled = false;

    function foo() internal {
        fooWasCalled = true;
    }

    function canRevert(bool y) external {
        // Call library function that may revert based on y parameter
        RevertLibrary.canRevert(y);
        // This state change should be rolled back if the library reverts
        foo();
    }
}