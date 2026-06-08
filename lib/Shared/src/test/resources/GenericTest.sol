// These contracts are intended as a generic contract, useful for many unit
// tests.  New features should be added as necessary.

library Library {
    function externalFunction() external {}

    function overloadedFunction()       external returns(uint)  { return 0; }
    function overloadedFunction(uint x) external returns (uint) { return 1; }

}

contract PrimaryContract {
    enum ExampleEnum { MEMBER1, MEMBER2 }

    uint256 privateUintStorage;
    address public immutable immutable_field = address(0);

    function internalFunction() internal {}
    function externalFunction() external {}

    function externalTakesUint(uint x) external {}

    uint   public uint_field;
    uint[] public array_field;
    uint[3] public static_array_field;
    mapping(bool => address) public bool_map;
    mapping(uint => mapping(uint => address)) public nested_map;

    bytes public bytes_field;
    string public string_field;

    function overloadedFunction()       external returns(uint)  { return 0; }
    function overloadedFunction(uint x) external returns (uint) { return 1; }

    struct ExampleStruct {
        uint uint_field;
    }

    ExampleStruct public struct_field;

    function returnsStruct() external returns(ExampleStruct memory) { return ExampleStruct(3); }
    function returnsTwiceUint(uint x) public pure returns(uint) { return 2 * x; }

    struct StructOfArrayOfBytes {
        bytes[] s;
    }

    function returnsStructOfArrayOfBytes() external returns (StructOfArrayOfBytes memory) {
        StructOfArrayOfBytes memory s;
        return s;
    }

    struct StructOfArrayOfUints {
        uint[] uArr;
    }

    function returnsStructOfArrayOfUints() external returns (StructOfArrayOfUints memory) {
        StructOfArrayOfUints memory s;
        return s;
    }

    struct StructWithStructOfArrayOfBytes {
        StructOfArrayOfBytes sOfB;
    }

    function differentReturnTypes() external returns (uint256) { return 0; }

    function voidFunction() external { return; }
}

contract SecondaryContract {
    function internalFunction() internal {}
    function externalFunction() external {}

    function differentReturnTypes() external returns (int256) { return 0; }
}

contract Extension {
    function externalExtensionFunction() external {}
}
