methods {
    function canRevert(bool) external envfree;
    function RevertLibrary.summarizedRevert(bool y) internal => revertSummary(y);
}

rule verifyRevertRollsBackStateChanges() {
    require(currentContract.fooWasCalled == false);
    bool shouldRevert = true;
    canRevert@withrevert(shouldRevert);
    assert lastReverted && currentContract.fooWasCalled == false;
}

function revertSummary(bool y) {
    if (y) {
        revert();
    }
}