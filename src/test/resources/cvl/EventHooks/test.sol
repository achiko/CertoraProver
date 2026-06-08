contract Target {
   struct MyType {
      address foo;
      uint256 bar;
   }
   event SignatureCollision(MyType payload);
}

contract OtherTarget {
   struct OtherType {
      address baz;
      uint256 gorp;
   }
   event SignatureCollision(OtherType myPayload);
}
