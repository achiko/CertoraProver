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
    struct TokenHolder {
        address token;
    }

    struct Wrapper {
        uint dummy;      // offset the token field within the struct
        TokenHolder holder;
    }

    struct ArrayHolder {
        uint padding1;
        uint padding2;
        address[] tokens;
    }

    struct StructItem {
        uint value1;
        uint32 padding;
        address token;       // not at slot 0 of the struct
    }

    bool padding;
    address public token;
    address public tokenMulti;
    address[] public tokens;
    address[] public multiTokens;        // for multi-target array link test
    address[3] public fixedTokens;
    address[2] public fixedMultiTokens;  // for multi-target static array link test
    TokenHolder public holder;
    Wrapper public wrapper;
    ArrayHolder public arrayHolder;
    StructItem[] public structItems;
    mapping(uint256 => address) public tokenMap;        // for mapping link test
    mapping(uint256 => StructItem) public structMap;    // for mapping-to-struct link test
    mapping(bytes4 => address) public bytes4Map;        // for bytes4-keyed mapping link test
    mapping(address => address) public addrMap;         // for address-keyed mapping link test
    address immutable public immutableToken;            // for immutable link test
    address immutable public immutableTokenMulti;       // for multi-target immutable link test

    constructor(address _immutableToken, address _immutableTokenMulti) {
        immutableToken = _immutableToken;
        immutableTokenMulti = _immutableTokenMulti;
    }

    function getImmutableTokenValue() external view returns (uint) {
        return IToken(immutableToken).getValue();
    }

    function getImmutableTokenMultiValue() external view returns (uint) {
        return IToken(immutableTokenMulti).getValue();
    }

    // Wildcard test variables
    address[] public wcDynTokens;                       // wildcard dynamic array
    address[4] public wcFixedTokens;                    // wildcard static array
    mapping(uint256 => address) public wcTokenMap;      // wildcard mapping
    address[] public wcPrecedence;                      // wildcard + concrete precedence
    StructItem[] public wcStructItems;                  // wildcard + struct field

    // Wildcard mapping + struct test variables
    mapping(uint256 => StructItem) public wcStructMap;          // wildcard mapping + struct
    mapping(uint256 => StructItem) public wcStructMapPrec;      // wildcard mapping + struct + precedence

    // Precision test: struct with two address fields
    struct TwoAddrItem {
        address addrA;
        address addrB;
    }
    mapping(uint256 => TwoAddrItem) public wcTwoAddrMap;        // precision: mapping
    TwoAddrItem[] public wcTwoAddrArr;                          // precision: dynamic array

    function getTokenValue() external view returns (uint) {
        return IToken(token).getValue();
    }

    function getTokenMultiValue() external view returns (uint) {
        return IToken(tokenMulti).getValue();
    }

    function getTokenAt(uint i) external view returns (uint) {
        return IToken(tokens[i]).getValue();
    }

    function tokensLength() external view returns (uint) {
        return tokens.length;
    }

    function getMultiTokenAt(uint i) external view returns (uint) {
        return IToken(multiTokens[i]).getValue();
    }

    function multiTokensLength() external view returns (uint) {
        return multiTokens.length;
    }

    function getFixedTokenAt(uint i) external view returns (uint) {
        return IToken(fixedTokens[i]).getValue();
    }

    function getFixedMultiTokenAt(uint i) external view returns (uint) {
        return IToken(fixedMultiTokens[i]).getValue();
    }

    function getHolderValue() external view returns (uint) {
        return IToken(holder.token).getValue();
    }

    function getWrapperValue() external view returns (uint) {
        return IToken(wrapper.holder.token).getValue();
    }

    function getArrayHolderTokenAt(uint i) external view returns (uint) {
        return IToken(arrayHolder.tokens[i]).getValue();
    }

    function arrayHolderTokensLength() external view returns (uint) {
        return arrayHolder.tokens.length;
    }

    function getStructItemTokenAt(uint i) external view returns (uint) {
        return IToken(structItems[i].token).getValue();
    }

    function structItemsLength() external view returns (uint) {
        return structItems.length;
    }

    function getTokenMapAt(uint i) external view returns (uint) {
        return IToken(tokenMap[i]).getValue();
    }

    function getStructMapTokenAt(uint i) external view returns (uint) {
        return IToken(structMap[i].token).getValue();
    }

    function getBytes4MapAt(bytes4 key) external view returns (uint) {
        return IToken(bytes4Map[key]).getValue();
    }

    function getAddrMapAt(address key) external view returns (uint) {
        return IToken(addrMap[key]).getValue();
    }

    // Wildcard accessor functions
    function getWcDynTokenAt(uint i) external view returns (uint) {
        return IToken(wcDynTokens[i]).getValue();
    }

    function wcDynTokensLength() external view returns (uint) {
        return wcDynTokens.length;
    }

    function getWcFixedTokenAt(uint i) external view returns (uint) {
        return IToken(wcFixedTokens[i]).getValue();
    }

    function getWcTokenMapAt(uint i) external view returns (uint) {
        return IToken(wcTokenMap[i]).getValue();
    }

    function getWcPrecedenceAt(uint i) external view returns (uint) {
        return IToken(wcPrecedence[i]).getValue();
    }

    function wcPrecedenceLength() external view returns (uint) {
        return wcPrecedence.length;
    }

    function getWcStructItemTokenAt(uint i) external view returns (uint) {
        return IToken(wcStructItems[i].token).getValue();
    }

    function wcStructItemsLength() external view returns (uint) {
        return wcStructItems.length;
    }

    // Wildcard mapping + struct accessor functions
    function getWcStructMapTokenAt(uint i) external view returns (uint) {
        return IToken(wcStructMap[i].token).getValue();
    }

    function getWcStructMapPrecTokenAt(uint i) external view returns (uint) {
        return IToken(wcStructMapPrec[i].token).getValue();
    }

    // Precision test accessor functions
    function getWcTwoAddrMapA(uint i) external view returns (uint) {
        return IToken(wcTwoAddrMap[i].addrA).getValue();
    }

    function getWcTwoAddrMapB(uint i) external view returns (uint) {
        return IToken(wcTwoAddrMap[i].addrB).getValue();
    }

    function getWcTwoAddrArrA(uint i) external view returns (uint) {
        return IToken(wcTwoAddrArr[i].addrA).getValue();
    }

    function getWcTwoAddrArrB(uint i) external view returns (uint) {
        return IToken(wcTwoAddrArr[i].addrB).getValue();
    }

    function wcTwoAddrArrLength() external view returns (uint) {
        return wcTwoAddrArr.length;
    }
}
