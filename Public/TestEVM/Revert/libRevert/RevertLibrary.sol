// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

library RevertLibrary {
    function summarizedRevert(bool y) public {
        // This function is summarized in the CVL spec
        // The actual implementation is replaced by the revertSummary function
        // which reverts when y is true
    }

    function canRevert(bool y) external {
        summarizedRevert(y);
    }
}