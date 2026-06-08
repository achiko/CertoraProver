contract C {
    uint16[] public list1;
    uint16[] public list2;
    uint16[] public list3;

    function add(uint16 n) external {
        list1.push(n);
        list2.push(n);
        list3.push(n);
    }
}
