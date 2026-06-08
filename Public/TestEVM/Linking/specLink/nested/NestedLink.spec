using Main as main;
using TokenA as tokenA;
using TokenB as tokenB;
using TokenC as tokenC;
using TokenD as tokenD;

links {
    // ── Nested dynamic array: address[][] ──
    main.dynDyn[0][0] => tokenA;
    main.dynDyn[0][1] => tokenB;
    main.dynDyn[1][0] => tokenC;

    // ── Mapping to dynamic array: mapping(uint => address[]) ──
    main.mapDyn[0][0] => tokenA;
    main.mapDyn[0][1] => tokenB;
    main.mapDyn[1][0] => tokenC;

    // ── Nested mapping: mapping(uint => mapping(uint => address)) ──
    main.mapMap[0][0] => tokenA;
    main.mapMap[0][1] => tokenB;
    main.mapMap[1][0] => tokenD;

    // ── Static-inner, dynamic-outer: address[3][] ──
    // Outer dynamic, inner static (size 3)
    main.staticInDyn[0][0] => tokenA;
    main.staticInDyn[0][1] => tokenB;
    main.staticInDyn[0][2] => tokenC;
    main.staticInDyn[1][0] => tokenD;

    // ── Dynamic-inner, static-outer: address[][2] ──
    // Outer static (size 2), inner dynamic
    main.dynInStatic[0][0] => tokenA;
    main.dynInStatic[0][1] => tokenB;
    main.dynInStatic[1][0] => tokenC;

    // ── Mapping to static array: mapping(uint => address[2]) ──
    main.mapStatic[0][0] => tokenA;
    main.mapStatic[0][1] => tokenB;
    main.mapStatic[1][0] => tokenD;

    // ── Wildcard nested dynamic array ──
    main.wcDynDyn[_][_] => [tokenA, tokenB];

    // ── Wildcard nested mapping ──
    main.wcMapMap[_][_] => [tokenC, tokenD];

    // ── Wildcard + concrete precedence on nested mapping ──
    main.wcMapMapPrec[0][0] => tokenA;
    main.wcMapMapPrec[_][_] => tokenB;

    // ── Complex: mapping(bytes32 => Widget[]) with struct + static array ──
    // Path: complexMap[key][idx].tokens[inner]  (mapping → dyn array → struct → static array)
    main.complexMap[to_bytes32(0xAA)][0].tokens[0] => tokenA;
    main.complexMap[to_bytes32(0xAA)][0].tokens[1] => tokenB;
    main.complexMap[to_bytes32(0xAA)][0].tokens[2] => tokenC;
    main.complexMap[to_bytes32(0xAA)][1].tokens[0] => tokenD;
    main.complexMap[to_bytes32(0xBB)][0].tokens[0] => tokenB;

    // ── Wildcard on complex struct ──
    main.wcComplexMap[_][_].tokens[_] => tokenC;
}

// ── Nested dynamic array: dynDyn[0][0]=42, [0][1]=100, [1][0]=999 ──
rule nestedDynDynLinked {
    env e;
    uint v00 = main.getDynDynAt@withrevert(e, 0, 0);
    assert !lastReverted => v00 == 42;
    uint v01 = main.getDynDynAt@withrevert(e, 0, 1);
    assert !lastReverted => v01 == 100;
    uint v10 = main.getDynDynAt@withrevert(e, 1, 0);
    assert !lastReverted => v10 == 999;
}

// ── Mapping to dynamic array: mapDyn[0][0]=42, [0][1]=100, [1][0]=999 ──
rule mapDynLinked {
    env e;
    uint v00 = main.getMapDynAt@withrevert(e, 0, 0);
    assert !lastReverted => v00 == 42;
    uint v01 = main.getMapDynAt@withrevert(e, 0, 1);
    assert !lastReverted => v01 == 100;
    uint v10 = main.getMapDynAt@withrevert(e, 1, 0);
    assert !lastReverted => v10 == 999;
}

// ── Nested mapping: mapMap[0][0]=42, [0][1]=100, [1][0]=666 ──
// No length constraints at all (both levels are mappings)
rule mapMapLinked {
    env e;
    assert main.getMapMapAt(e, 0, 0) == 42;
    assert main.getMapMapAt(e, 0, 1) == 100;
    assert main.getMapMapAt(e, 1, 0) == 666;
}

// ── Static-inner, dynamic-outer: staticInDyn[0][0]=42, [0][1]=100, [0][2]=999, [1][0]=666 ──
rule staticInDynLinked {
    env e;
    uint v00 = main.getStaticInDynAt@withrevert(e, 0, 0);
    assert !lastReverted => v00 == 42;
    uint v01 = main.getStaticInDynAt@withrevert(e, 0, 1);
    assert !lastReverted => v01 == 100;
    uint v02 = main.getStaticInDynAt@withrevert(e, 0, 2);
    assert !lastReverted => v02 == 999;
    uint v10 = main.getStaticInDynAt@withrevert(e, 1, 0);
    assert !lastReverted => v10 == 666;
}

// ── Dynamic-inner, static-outer: dynInStatic[0][0]=42, [0][1]=100, [1][0]=999 ──
rule dynInStaticLinked {
    env e;
    uint v00 = main.getDynInStaticAt@withrevert(e, 0, 0);
    assert !lastReverted => v00 == 42;
    uint v01 = main.getDynInStaticAt@withrevert(e, 0, 1);
    assert !lastReverted => v01 == 100;
    uint v10 = main.getDynInStaticAt@withrevert(e, 1, 0);
    assert !lastReverted => v10 == 999;
}

// ── Mapping to static array: mapStatic[0][0]=42, [0][1]=100, [1][0]=666 ──
// Outer is mapping, inner is static — no length constraints at all
rule mapStaticLinked {
    env e;
    assert main.getMapStaticAt(e, 0, 0) == 42;
    assert main.getMapStaticAt(e, 0, 1) == 100;
    assert main.getMapStaticAt(e, 1, 0) == 666;
}

// ── Wildcard nested dynamic array: all elements are tokenA or tokenB ──
rule wildcardDynDyn {
    env e;
    uint i; uint j;
    require main.wcDynDynOuterLength(e) > i;
    uint val = main.getWcDynDynAt(e, i, j);
    assert val == 42 || val == 100;
}

// ── Wildcard nested mapping: all values are tokenC or tokenD ──
rule wildcardMapMap {
    env e;
    uint i; uint j;
    uint val = main.getWcMapMapAt(e, i, j);
    assert val == 999 || val == 666;
}

// ── Wildcard + concrete precedence on nested mapping ──
// [0][0] is constrained to tokenA (42) by concrete entry
// Any other key pair gets wildcard target tokenB (100)
rule wildcardMapMapPrecedence {
    env e;
    assert main.getWcMapMapPrecAt(e, 0, 0) == 42;
    assert main.getWcMapMapPrecAt(e, 1, 1) == 100;
    assert main.getWcMapMapPrecAt(e, 0, 1) == 100;
}

// ── Complex: mapping(bytes32 => Widget[]) with struct + static array ──
rule complexMapLinked {
    env e;
    uint vAA00 = main.getComplexAt@withrevert(e, to_bytes32(0xAA), 0, 0);
    assert !lastReverted => vAA00 == 42;   // tokenA
    uint vAA01 = main.getComplexAt@withrevert(e, to_bytes32(0xAA), 0, 1);
    assert !lastReverted => vAA01 == 100;  // tokenB
    uint vAA02 = main.getComplexAt@withrevert(e, to_bytes32(0xAA), 0, 2);
    assert !lastReverted => vAA02 == 999;  // tokenC
    uint vAA10 = main.getComplexAt@withrevert(e, to_bytes32(0xAA), 1, 0);
    assert !lastReverted => vAA10 == 666;  // tokenD
    uint vBB00 = main.getComplexAt@withrevert(e, to_bytes32(0xBB), 0, 0);
    assert !lastReverted => vBB00 == 100;  // tokenB
}

// ── Wildcard on complex struct: all elements are tokenC (999) ──
rule wcComplexMapWildcard {
    env e;
    bytes32 key;
    uint idx;
    uint inner;
    require inner < 3;
    require main.wcComplexMapInnerLength(e, key) > idx;
    uint val = main.getWcComplexAt(e, key, idx, inner);
    assert val == 999;
}
