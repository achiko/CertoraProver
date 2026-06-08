using Library1 as library1;
using Library2 as library2;
using Library3 as library3;
using Library4 as library4;

methods {
	function Library1._ external => NONDET;
	function Library2._ external => HAVOC_ALL;
	function Library3._ external => HAVOC_ECF;
	function Library4._ external => AUTO;

	function Test.toSummarize() external returns (uint256) => NONDET;
    unresolved external in Test.unresolvedExternalSummary(address, bytes) => DISPATCH (optimistic=true) [ Test.toSummarize() ];

    unresolved external in Test.unresolvedExternalSummaryDefault(address, bytes) => DISPATCH [ Test.toSummarize() ] default NONDET;
}

rule nondetSummary {
    env e;
	assert nondetSummary(e) == nondetSummary(e), "should fail";
}

rule havocAllSummary {
    env e;
	assert havocAllSummary(e) == havocAllSummary(e), "should fail";
}

rule havocECFSummary {
    env e;
	assert havocECFSummary(e) == havocECFSummary(e), "should fail";
}

rule autoSummary {
    env e;
	assert autoSummary(e) == autoSummary(e), "should fail";
}

rule unresolvedExternal {
    env e;
    address target;
    bytes b = getToSummarizeFunctionSelector(e);
    uint res = unresolvedExternalSummary(e, target, b);
    assert res == 5, "should fail";
}

rule unresolvedExternalForcingDefaultCase {
    env e;
    address target;
    bytes b;
    require(getToSummarizeFunctionSelector(e) != b);
    require(b.length > 0 && b.length < 32) ;
    uint res = unresolvedExternalSummaryDefault(e, target, b);
    assert res == 5, "should fail";
}
