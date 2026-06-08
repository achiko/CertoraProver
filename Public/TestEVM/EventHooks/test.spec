using OtherContract as other;

ghost mathint seenTransfers;

ghost address seenFrom;
ghost address seenTo;
ghost uint seenAmount;
ghost address emitterContract;

hook event Transferred(address indexed from, address indexed to, uint amount) {
	assert seenTransfers == 0;
	seenTransfers = 1;
	seenAmount = amount;
	seenFrom = from;
	seenTo = to;
	emitterContract = executingContract;
}

hook event Counter.OrderPlaced(Counter.Order payload) {
	assert executingContract == currentContract;
	assert payload.sender == seenFrom;
	assert payload.recipient == seenTo;
}

hook event OtherContract.OrderPlaced(OtherContract.MyOrder payload) {
	assert executingContract == other;
	assert payload.sender == seenFrom;
	assert payload.receiver == seenTo;
}
/**
* This rule tests multiple things:
1. Hook matching on emitter contract
2. Complex decoding out of the send buffer
3. proper binding of executingContract
4. Basic binding of topics/signature matching
*/
rule test_event_emission() {
	seenTransfers = 0;
	env e;
	address to;
	uint amount;
	transfer(e, to, amount);
	other.delegateOrder(e, to);
	assert seenTransfers == 1 && seenAmount == amount && seenTo == to && seenFrom == e.msg.sender;
	assert emitterContract == currentContract;
}

ghost bytes32 pathPart1;
ghost bytes32 pathPart2;
ghost bool seenSwapEvent;
ghost uint expectedAmount;
ghost address expectedSender;

hook event SwapInitiated(address indexed initiator, uint amount, address[] path) {
	assert path.length == 3;
	assert amount == expectedAmount;
	assert initiator == expectedSender;
	assert path[0] == currentContract.swaps[pathPart1].market1;
	assert path[1] == currentContract.swaps[pathPart1].market2;
	assert path[2] == currentContract.swaps[pathPart2].market2;
	assert currentContract.swaps[pathPart2].market1 == path[1];
	seenSwapEvent = true;
}

/**
* Tests decoding slightly more complex payloads (dynamically sized types)
*/
rule test_complex_payload(env e, bytes32 p1, bytes32 p2, uint amount) {
	seenSwapEvent = false;
	pathPart1 = p1;
	pathPart2 = p2;
	expectedAmount = amount;
	expectedSender = e.msg.sender;
	swap(e, [p1, p2], amount);
	assert seenSwapEvent;
}
