rule add(uint16 id) {
    env e;
    require currentContract.list1.length == 0;
    add(e, id);
    assert currentContract.list1[0] == id;
}
