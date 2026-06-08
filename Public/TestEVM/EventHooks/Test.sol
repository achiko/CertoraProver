// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.29;

contract OtherContract {
	struct MyOrder {
		address sender;
		address receiver;
	}

	event OrderPlaced(MyOrder payload);

	function delegateOrder(address to) external {
		emit OrderPlaced(MyOrder({ sender: msg.sender, receiver: to }));
	}
}

contract Counter {
	event Transferred(address indexed from, address indexed to, uint256 amount);

	struct Order {
		address sender;
		address recipient;
	}

	event OrderPlaced(Order payload);

	event SwapInitiated(address indexed initiator, uint amount, address[] swapPath);

	mapping(address => uint256) amounts;

	function transfer(address to, uint amount) external {
		require(amounts[msg.sender] > amount);
		amounts[msg.sender] -= amount;
		amounts[to] += amount;
		emit Transferred(msg.sender, to, amount);
		emit OrderPlaced(Order({sender: msg.sender, recipient: to}));
	}

	struct SwapProvider {
		address market1;
		address market2;
		uint160 exchangeBasis;
		bool enabled;
	}

	mapping(bytes32 => SwapProvider) swaps;

	function swap(
		bytes32[] calldata requestedPath,
		uint toSwap
	) external {
		require(amounts[msg.sender] >= toSwap);
		address[] memory path = new address[](requestedPath.length + 1);
		if(requestedPath.length == 0) {
			revert("Empty swap path");
		}
		address prev;
		uint swapPointer = 0;
		for(uint i = 0; i < requestedPath.length; i++) {
			SwapProvider storage prov = swaps[requestedPath[i]];
			if(!prov.enabled) {
				revert("Provider Not Enabled");
			}
			if(i > 0) {
				if(prev != prov.market1) {
					revert("Invalid swap path");
				} else {
					path[swapPointer] = prov.market2;
					swapPointer += 1;
					prev = prov.market2;
				}
			} else {
				path[swapPointer] = prov.market1;
				path[swapPointer+1] = prov.market2;
				swapPointer += 2;
				prev = prov.market2;
			}

		}
		emit SwapInitiated(msg.sender, toSwap, path);
	}
}
