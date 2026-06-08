using Main as main;
using TokenA as tokenA;
using TokenB as tokenB;
using TokenC as tokenC;
using TokenD as tokenD;

links {
    main.token => tokenA;
    main.tokenMulti => [tokenB, tokenD]; // Instead of dispatch list on wildcard summaries
    main.tokens[0] => tokenA; // Array support
    main.tokens[1] => tokenB; // Both A and B are inlined at each access in Solidity (+ default havoc for any other access)
    main.multiTokens[0] => [tokenB, tokenC];
    main.fixedTokens[0] => tokenA;
    main.fixedTokens[1] => tokenB;
    main.fixedTokens[2] => tokenC;
    main.fixedMultiTokens[0] => [tokenA, tokenD];
    main.holder.token => tokenB; // nested struct
    main.wrapper.holder.token => tokenD;
    main.arrayHolder.tokens[0] => tokenC; // array within a struct
    main.arrayHolder.tokens[1] => tokenD;
    main.structItems[0].token => tokenA; // struct within an array
    main.structItems[1].token => tokenC;
    main.tokenMap[0] => tokenA; // mapping support
    main.tokenMap[1] => tokenB;
    main.tokenMap[_] => [tokenB, tokenD]; // wildcard (was ghost-indexed)
    main.structMap[0].token => tokenC; // mapping with struct target type
    main.bytes4Map[to_bytes4(0x12345678)] => tokenA; // bytes4-keyed mapping
    main.bytes4Map[to_bytes4(0xdeadbeef)] => tokenB;
    main.addrMap[tokenA] => tokenC; // address-keyed mapping (contract alias as index)
    main.addrMap[tokenB] => tokenD;
    main.immutableToken => tokenB; // immutable support
    main.immutableTokenMulti => [tokenA, tokenC];

    // Wildcard link entries
    main.wcDynTokens[_] => [tokenA, tokenB];
    main.wcFixedTokens[_] => [tokenA, tokenB];
    main.wcTokenMap[_] => [tokenA, tokenB];
    main.wcPrecedence[0] => tokenB;
    main.wcPrecedence[_] => tokenA; // precedence: element 0 will be tokenB, all else tokenA
    main.wcStructItems[_].token => [tokenA, tokenB];

    // Wildcard mapping + struct
    main.wcStructMap[_].token => [tokenA, tokenB];
    // Wildcard mapping + struct + precedence
    main.wcStructMapPrec[_].token => tokenA;
    main.wcStructMapPrec[0].token => tokenB;

    // Precision: mapping with two address fields, each linked to different targets
    main.wcTwoAddrMap[_].addrA => [tokenA, tokenB];
    main.wcTwoAddrMap[_].addrB => [tokenC, tokenD];
    // Precision: dynamic array with two address fields
    main.wcTwoAddrArr[_].addrA => [tokenA, tokenB];
    main.wcTwoAddrArr[_].addrB => [tokenC, tokenD];
}

// Single-target: token is linked to tokenA with value 42
rule tokenLinked {
    env e;
    assert main.getTokenValue(e) == 42;
    assert main.token(e) == tokenA;
    assert main.token == tokenA;
}

// Multi-target: tokenMulti address is tokenB or tokenD, never tokenA, value != 42
rule multiLink {
    env e;
    assert main.tokenMulti(e) == tokenB || main.tokenMulti(e) == tokenD;
    assert main.tokenMulti(e) != tokenA;
    assert main.getTokenMultiValue(e) != 42;
    assert main.tokenMulti == tokenB || main.tokenMulti == tokenD;
}

// Dynamic array: tokens[0]=42, tokens[1]=100
// Uses @withrevert because the conditional linking (len > idx => link) doesn't
// force array length, so Solidity's built-in bounds check may revert.
rule dynamicArrayLinked {
    env e;
    uint val0 = main.getTokenAt@withrevert(e, 0);
    assert !lastReverted => val0 == 42;
    uint val1 = main.getTokenAt@withrevert(e, 1);
    assert !lastReverted => val1 == 100;
}

// Static array: fixedTokens[0]=42, [1]=100, [2]=999
rule staticArrayLinked {
    env e;
    assert main.getFixedTokenAt(e, 0) == 42;
    assert main.getFixedTokenAt(e, 1) == 100;
    assert main.getFixedTokenAt(e, 2) == 999;
    assert main.fixedTokens[1] == tokenB;
}

// Struct: holder.token is linked to tokenB (value 100)
rule structLinked {
    env e;
    assert main.getHolderValue(e) == 100;
    assert main.holder.token == tokenB;
}

// Nested struct: wrapper.holder.token is linked to tokenD (value 666)
rule nestedStructLinked {
    env e;
    assert main.getWrapperValue(e) == 666;
    assert main.wrapper.holder.token == tokenD;
}

// Array in struct: arrayHolder.tokens[0]=999, [1]=666
rule structArrayLinked {
    env e;
    uint val0 = main.getArrayHolderTokenAt@withrevert(e, 0);
    assert !lastReverted => val0 == 999;
    uint val1 = main.getArrayHolderTokenAt@withrevert(e, 1);
    assert !lastReverted => val1 == 666;
}

// Struct in array: structItems[0].token=42, [1]=999
rule structInArrayLinked {
    env e;
    uint val0 = main.getStructItemTokenAt@withrevert(e, 0);
    assert !lastReverted => val0 == 42;
    uint val1 = main.getStructItemTokenAt@withrevert(e, 1);
    assert !lastReverted => val1 == 999;
}

// Multi-target dynamic array: addr is B or C, not A, value 100 or 999
rule multiTargetDynArray {
    env e;
    uint val = main.getMultiTokenAt@withrevert(e, 0);
    assert !lastReverted => (val == 100 || val == 999);
    address addr = main.multiTokens(e, 0);
    assert main.multiTokens[0] == tokenB || main.multiTokens[0] == tokenC;
}

// Multi-target static array: addr is A or D, not B, value 42 or 666
rule multiTargetStaticArray {
    env e;
    address addr = main.fixedMultiTokens(e, 0);
    assert addr == tokenA || addr == tokenD;
    assert addr != tokenB;
    uint val = main.getFixedMultiTokenAt(e, 0);
    assert val == 42 || val == 666;
    assert main.fixedMultiTokens[0] == tokenA || main.fixedMultiTokens[0] == tokenD;
}

// Mapping with wildcard + precedence: concrete keys override wildcard
rule mappingLinked {
    env e;
    // Concrete entries still hold
    assert main.getTokenMapAt(e, 0) == 42;  // concrete: tokenA
    assert main.getTokenMapAt(e, 1) == 100; // concrete: tokenB
    // For non-concrete keys, wildcard applies: tokenB or tokenD
    uint i;
    require i != 0 && i != 1;
    uint val = main.getTokenMapAt(e, i);
    assert val == 100 || val == 666;         // wildcard: tokenB(100) or tokenD(666)
    assert main.tokenMap[i] == tokenB || main.tokenMap[i] == tokenD;
}

// Mapping to struct: structMap[0].token is tokenC (value 999)
rule mappingStructLinked {
    env e;
    assert main.getStructMapTokenAt(e, 0) == 999;
    assert main.structMap[0].token == tokenC;
}

// Bytes4-keyed mapping: bytes4Map[0x12345678]=tokenA(42), bytes4Map[0xdeadbeef]=tokenB(100)
rule bytes4MappingLinked {
    env e;
    assert main.getBytes4MapAt(e, to_bytes4(0x12345678)) == 42;
    assert main.getBytes4MapAt(e, to_bytes4(0xdeadbeef)) == 100;
    assert main.bytes4Map[to_bytes4(0x12345678)] == tokenA;
    assert main.bytes4Map[to_bytes4(0xdeadbeef)] == tokenB;
}

// Address-keyed mapping: addrMap[tokenA]=tokenC(999), addrMap[tokenB]=tokenD(666)
rule addrMappingLinked {
    env e;
    assert main.getAddrMapAt(e, tokenA) == 999;
    assert main.getAddrMapAt(e, tokenB) == 666;
    assert main.addrMap[tokenA] == tokenC;
    assert main.addrMap[tokenB] == tokenD;
}

// Immutable single-target: immutableToken is linked to tokenB (value 100)
rule immutableLinked {
    env e;
    assert main.getImmutableTokenValue(e) == 100;
    assert main.immutableToken(e) == tokenB;
    assert main.immutableToken == tokenB;
}

// Immutable multi-target: immutableTokenMulti is tokenA or tokenC, not tokenB
rule immutableMultiLink {
    env e;
    assert main.immutableTokenMulti(e) == tokenA || main.immutableTokenMulti(e) == tokenC;
    assert main.immutableTokenMulti(e) != tokenB;
    uint val = main.getImmutableTokenMultiValue(e);
    assert val == 42 || val == 999;
    assert main.immutableTokenMulti == tokenA || main.immutableTokenMulti == tokenC;
}

// Wildcard dynamic array: all elements are tokenA or tokenB (value 42 or 100)
rule wildcardDynArray {
    env e;
    uint i;
    require main.wcDynTokensLength(e) > i;
    uint val = main.getWcDynTokenAt(e, i);
    assert val == 42 || val == 100;
    assert main.wcDynTokens[i] == tokenA || main.wcDynTokens[i] == tokenB;
}

// Wildcard static array: all elements are tokenA or tokenB (value 42 or 100)
rule wildcardStaticArray {
    env e;
    uint i;
    require i < 4;
    uint val = main.getWcFixedTokenAt(e, i);
    assert val == 42 || val == 100;
    assert main.wcFixedTokens[i] == tokenA || main.wcFixedTokens[i] == tokenB;
}

// Wildcard mapping: all values are tokenA or tokenB (value 42 or 100)
rule wildcardMapping {
    env e;
    uint i;
    uint val = main.getWcTokenMapAt(e, i);
    assert val == 42 || val == 100;
    assert main.wcTokenMap[i] == tokenA || main.wcTokenMap[i] == tokenB;
}

// Wildcard + concrete precedence:
// - Index 0 is constrained to tokenB (100) by the concrete TAC assumption
// - Index 1 can only be tokenA (42), since the precedence assumption limits non-concrete to wildcard targets
rule wildcardPrecedence {
    env e;
    require main.wcPrecedenceLength(e) >= 2;
    // Index 0 has concrete link to tokenB, forced by TAC assumption
    assert main.getWcPrecedenceAt(e, 0) == 100;
    // Index 1: only wildcard target (tokenA) is reachable, value must be 42
    assert main.getWcPrecedenceAt(e, 1) == 42;
    assert main.wcPrecedence[0] == tokenB;
    assert main.wcPrecedence[1] == tokenA;
}

// Wildcard + struct field: all elements' token is tokenA or tokenB (value 42 or 100)
rule wildcardStructField {
    env e;
    uint i;
    require main.wcStructItemsLength(e) > i;
    uint val = main.getWcStructItemTokenAt(e, i);
    assert val == 42 || val == 100;
    assert main.wcStructItems[i].token == tokenA || main.wcStructItems[i].token == tokenB;
}

// Wildcard mapping + struct: all values' token is tokenA or tokenB (value 42 or 100)
rule wildcardMappingStruct {
    env e;
    uint i;
    uint val = main.getWcStructMapTokenAt(e, i);
    assert val == 42 || val == 100;
    assert main.wcStructMap[i].token == tokenA || main.wcStructMap[i].token == tokenB;
}

// Wildcard mapping + struct + precedence:
// - Key 0 is constrained to tokenB (100) by the concrete link
// - Key 1 can only be tokenA (42), since only wildcard target is reachable
rule wildcardMappingStructPrecedence {
    env e;
    assert main.getWcStructMapPrecTokenAt(e, 0) == 100;
    assert main.getWcStructMapPrecTokenAt(e, 1) == 42;
    assert main.wcStructMapPrec[0].token == tokenB;
    assert main.wcStructMapPrec[1].token == tokenA;
}

// Precision: mapping with two address fields — addrA linked to tokenA/B, addrB to tokenC/D
rule wildcardMappingPrecisionA {
    env e;
    uint i;
    uint val = main.getWcTwoAddrMapA(e, i);
    assert val == 42 || val == 100;
    assert main.wcTwoAddrMap[i].addrA == tokenA || main.wcTwoAddrMap[i].addrA == tokenB;
}

rule wildcardMappingPrecisionB {
    env e;
    uint i;
    uint val = main.getWcTwoAddrMapB(e, i);
    assert val == 999 || val == 666;
    assert main.wcTwoAddrMap[i].addrB == tokenC || main.wcTwoAddrMap[i].addrB == tokenD;
}

// Precision: dynamic array with two address fields — addrA linked to tokenA/B, addrB to tokenC/D
rule wildcardArrayPrecisionA {
    env e;
    uint i;
    require main.wcTwoAddrArrLength(e) > i;
    uint val = main.getWcTwoAddrArrA(e, i);
    assert val == 42 || val == 100;
    assert main.wcTwoAddrArr[i].addrA == tokenA || main.wcTwoAddrArr[i].addrA == tokenB;
}

rule wildcardArrayPrecisionB {
    env e;
    uint i;
    require main.wcTwoAddrArrLength(e) > i;
    uint val = main.getWcTwoAddrArrB(e, i);
    assert val == 999 || val == 666;
    assert main.wcTwoAddrArr[i].addrB == tokenC || main.wcTwoAddrArr[i].addrB == tokenD;
}
