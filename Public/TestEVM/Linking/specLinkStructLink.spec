import "StructLink.spec";

using StructLink as structLink;
using True as trueContract;
using False as falseContract;

use rule r;

links {
    structLink.s.boolPointer => trueContract;
}
