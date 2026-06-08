methods {
	function a(uint256) external returns (uint16) envfree;
	function add(uint16) external envfree;
	function remove() external envfree;
	function set(uint, uint16) external envfree;
	function get(uint) external returns (uint16) envfree;
	function len() external returns (uint) envfree;
}

rule check1() {
	add(100);
	uint lastIndex = require_uint256(len() - 1);
	assert get(lastIndex) == 100;
}

rule check2() {
	uint lenBefore = len();
	add(100);
	remove();
	uint lenAfter = len();
	assert lenBefore == lenAfter;
}