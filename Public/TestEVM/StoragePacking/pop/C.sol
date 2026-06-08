contract C {

    uint16[] public a;

    function add(uint16 v) external {
        a.push(v);
    }

    function remove() external {
        a.pop();
    }

    function set(uint i, uint16 v) external {
        a[i] = v;
    }

    function get(uint i) external view returns (uint16) {
        return a[i];
    }

    function len() external view returns (uint) {
        return a.length;
    }
}