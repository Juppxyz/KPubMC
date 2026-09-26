package xyz.jupp.minecraft.config;

public record ShopItem(String name, String material, int price, boolean sell, int amount, String description) {
}
