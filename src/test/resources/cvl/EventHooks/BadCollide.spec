hook event SignatureCollision(Target.MyType a) {
  assert true;
}

hook event SignatureCollision(OtherTarget.OtherType a) {
  assert true;
}

hook event BasicEvent(uint a) {
   assert true;
}

hook event Target.BasicEvent(uint b) {
   assert true;
}
