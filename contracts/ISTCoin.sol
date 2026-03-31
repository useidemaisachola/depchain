// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * IST Coin - ERC-20 token for DepChain
 *
 * Name:     IST Coin
 * Symbol:   IST
 * Decimals: 2
 * Supply:   100,000,000 (10_000_000_000 in smallest unit)
 *
 * Frontrunning protection:
 *   The standard ERC-20 `approve` is vulnerable to an approval frontrunning
 *   attack. When an owner reduces an allowance, a malicious spender can watch
 *   the mempool and call `transferFrom` before the new approval is mined,
 *   spending the old allowance, and then also spend the new allowance —
 *   effectively stealing old + new tokens.
 *
 *   Fix: replace direct `approve` with `increaseAllowance` /
 *   `decreaseAllowance`. The owner can only increment or decrement the
 *   allowance atomically, so there is no window where two allowances can be
 *   drained. The raw `approve` is kept for ERC-20 interface compatibility but
 *   requires the current allowance to be 0 first (must-be-zero guard).
 */
contract ISTCoin {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    string public constant name     = "IST Coin";
    string public constant symbol   = "IST";
    uint8  public constant decimals = 2;

    uint256 public totalSupply;

    mapping(address => uint256) private _balances;
    // owner => spender => amount
    mapping(address => mapping(address => uint256)) private _allowances;

    // -------------------------------------------------------------------------
    // Events (ERC-20 standard)
    // -------------------------------------------------------------------------

    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner, address indexed spender, uint256 value);

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    constructor(address initialOwner) {
        // 100,000,000 tokens × 10^2 = 10,000,000,000 smallest units
        uint256 initialSupply = 100_000_000 * (10 ** decimals);
        totalSupply = initialSupply;
        _balances[initialOwner] = initialSupply;
        emit Transfer(address(0), initialOwner, initialSupply);
    }

    // -------------------------------------------------------------------------
    // ERC-20 view functions
    // -------------------------------------------------------------------------

    function balanceOf(address account) public view returns (uint256) {
        return _balances[account];
    }

    function allowance(address owner, address spender) public view returns (uint256) {
        return _allowances[owner][spender];
    }

    // -------------------------------------------------------------------------
    // ERC-20 transfer
    // -------------------------------------------------------------------------

    function transfer(address to, uint256 amount) public returns (bool) {
        _transfer(msg.sender, to, amount);
        return true;
    }

    function transferFrom(address from, address to, uint256 amount) public returns (bool) {
        uint256 currentAllowance = _allowances[from][msg.sender];
        require(currentAllowance >= amount, "ISTCoin: insufficient allowance");
        unchecked {
            _allowances[from][msg.sender] = currentAllowance - amount;
        }
        emit Approval(from, msg.sender, _allowances[from][msg.sender]);
        _transfer(from, to, amount);
        return true;
    }

    // -------------------------------------------------------------------------
    // Frontrunning-resistant allowance functions
    // -------------------------------------------------------------------------

    /**
     * @dev Safe approve: only allowed when the current allowance is 0.
     *      This prevents the classic approval frontrunning attack.
     *      To change an existing allowance, use increaseAllowance /
     *      decreaseAllowance, or first set it to 0 then to the new value.
     */
    function approve(address spender, uint256 amount) public returns (bool) {
        require(
            amount == 0 || _allowances[msg.sender][spender] == 0,
            "ISTCoin: use increaseAllowance or decreaseAllowance to change a non-zero allowance"
        );
        _approve(msg.sender, spender, amount);
        return true;
    }

    /**
     * @dev Atomically increase the allowance of `spender`.
     *      Safe against frontrunning: the spender can never observe a window
     *      where both the old and the new allowance are simultaneously usable.
     */
    function increaseAllowance(address spender, uint256 addedValue) public returns (bool) {
        _approve(msg.sender, spender, _allowances[msg.sender][spender] + addedValue);
        return true;
    }

    /**
     * @dev Atomically decrease the allowance of `spender`.
     *      Reverts if the result would be negative.
     */
    function decreaseAllowance(address spender, uint256 subtractedValue) public returns (bool) {
        uint256 currentAllowance = _allowances[msg.sender][spender];
        require(currentAllowance >= subtractedValue, "ISTCoin: decreased allowance below zero");
        unchecked {
            _approve(msg.sender, spender, currentAllowance - subtractedValue);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    function _transfer(address from, address to, uint256 amount) internal {
        require(from != address(0), "ISTCoin: transfer from zero address");
        require(to   != address(0), "ISTCoin: transfer to zero address");
        require(_balances[from] >= amount, "ISTCoin: insufficient balance");
        unchecked {
            _balances[from] -= amount;
            _balances[to]   += amount;
        }
        emit Transfer(from, to, amount);
    }

    function _approve(address owner, address spender, uint256 amount) internal {
        require(owner   != address(0), "ISTCoin: approve from zero address");
        require(spender != address(0), "ISTCoin: approve to zero address");
        _allowances[owner][spender] = amount;
        emit Approval(owner, spender, amount);
    }
}
