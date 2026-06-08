import "Top.spec";

using Top as top;
using Data as data;
using Foo as foo;

use rule ok;

links {
    top.dataProvider => data;
    data.s.foo => foo;
}
