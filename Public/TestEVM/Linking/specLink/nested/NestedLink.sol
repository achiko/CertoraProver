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
    // ── Nested dynamic array: address[][] ──
    // Two nesting levels, both dynamic
    address[][] public dynDyn;

    function getDynDynAt(uint i, uint j) external view returns (uint) {
        return IToken(dynDyn[i][j]).getValue();
    }

    function dynDynOuterLength() external view returns (uint) {
        return dynDyn.length;
    }

    function dynDynInnerLength(uint i) external view returns (uint) {
        return dynDyn[i].length;
    }

    // ── Mapping to dynamic array: mapping(uint => address[]) ──
    // Outer is mapping (no length), inner is dynamic array (has length)
    mapping(uint256 => address[]) public mapDyn;

    function getMapDynAt(uint i, uint j) external view returns (uint) {
        return IToken(mapDyn[i][j]).getValue();
    }

    function mapDynInnerLength(uint i) external view returns (uint) {
        return mapDyn[i].length;
    }

    // ── Nested mapping: mapping(uint => mapping(uint => address)) ──
    // Both levels are mappings (no length constraints at all)
    mapping(uint256 => mapping(uint256 => address)) public mapMap;

    function getMapMapAt(uint i, uint j) external view returns (uint) {
        return IToken(mapMap[i][j]).getValue();
    }

    // ── Static-inner, dynamic-outer: address[3][] ──
    // Outer is dynamic (has length), inner is static (fixed size 3)
    address[3][] public staticInDyn;

    function getStaticInDynAt(uint i, uint j) external view returns (uint) {
        return IToken(staticInDyn[i][j]).getValue();
    }

    function staticInDynOuterLength() external view returns (uint) {
        return staticInDyn.length;
    }

    // ── Dynamic-inner, static-outer: address[][2] ──
    // Outer is static (fixed size 2), inner is dynamic (has length)
    address[][2] public dynInStatic;

    function getDynInStaticAt(uint i, uint j) external view returns (uint) {
        return IToken(dynInStatic[i][j]).getValue();
    }

    function dynInStaticInnerLength(uint i) external view returns (uint) {
        return dynInStatic[i].length;
    }

    // ── Mapping to static array: mapping(uint => address[2]) ──
    // Outer is mapping, inner is static
    mapping(uint256 => address[2]) public mapStatic;

    function getMapStaticAt(uint i, uint j) external view returns (uint) {
        return IToken(mapStatic[i][j]).getValue();
    }

    // ── Wildcard nested dynamic array: address[][] ──
    address[][] public wcDynDyn;

    function getWcDynDynAt(uint i, uint j) external view returns (uint) {
        return IToken(wcDynDyn[i][j]).getValue();
    }

    function wcDynDynOuterLength() external view returns (uint) {
        return wcDynDyn.length;
    }

    // ── Wildcard nested mapping: mapping(uint => mapping(uint => address)) ──
    mapping(uint256 => mapping(uint256 => address)) public wcMapMap;

    function getWcMapMapAt(uint i, uint j) external view returns (uint) {
        return IToken(wcMapMap[i][j]).getValue();
    }

    // ── Wildcard + concrete precedence on nested mapping ──
    mapping(uint256 => mapping(uint256 => address)) public wcMapMapPrec;

    function getWcMapMapPrecAt(uint i, uint j) external view returns (uint) {
        return IToken(wcMapMapPrec[i][j]).getValue();
    }

    // ── Complex: mapping(bytes32 => Widget[]) where Widget has padding + address[3] ──
    // Path: complexMap[key][idx].tokens[inner]
    //   mapping (no length) → dynamic array (has length) → struct field → static array (no length)
    struct Widget {
        uint256 id;         // padding: not at offset 0
        uint128 weight;     // more padding: occupies half a slot
        address[3] tokens;  // static array of 3 addresses
    }

    mapping(bytes32 => Widget[]) public complexMap;

    function getComplexAt(bytes32 key, uint idx, uint inner) external view returns (uint) {
        return IToken(complexMap[key][idx].tokens[inner]).getValue();
    }

    function complexMapInnerLength(bytes32 key) external view returns (uint) {
        return complexMap[key].length;
    }

    // ── Same struct, wildcard + precedence ──
    mapping(bytes32 => Widget[]) public wcComplexMap;

    function getWcComplexAt(bytes32 key, uint idx, uint inner) external view returns (uint) {
        return IToken(wcComplexMap[key][idx].tokens[inner]).getValue();
    }

    function wcComplexMapInnerLength(bytes32 key) external view returns (uint) {
        return wcComplexMap[key].length;
    }
}
