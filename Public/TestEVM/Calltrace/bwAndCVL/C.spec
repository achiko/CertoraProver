rule imprecision(uint256 x, uint y) {
    assert (x & y) | (x & ~y) == x;
}