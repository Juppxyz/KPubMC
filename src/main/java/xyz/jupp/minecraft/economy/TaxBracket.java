package xyz.jupp.minecraft.economy;

/** Marginal tax rate for the part of a balance from {@code from} up to the next bracket. */
public record TaxBracket(int from, double rate) {}
