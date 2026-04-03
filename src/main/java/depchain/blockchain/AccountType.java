package depchain.blockchain;

/**
 * Discriminates between the two account kinds in DepChain.
 *
 * Classification rule: an account whose code is empty is an EOA;
 * an account with non-empty EVM bytecode is a CONTRACT.
 */

public enum AccountType {
    EOA,
    CONTRACT
}
