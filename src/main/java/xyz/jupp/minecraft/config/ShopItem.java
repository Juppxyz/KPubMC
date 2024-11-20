package xyz.jupp.minecraft.config;

public class ShopItem {

    private String description;
    private boolean sell;
    private String material;
    private String name;
    private int price;
    private int amount;


    public ShopItem(String name, String material, int price, boolean sell, int amount, String description) {
        this.description = description;
        this.material = material;
        this.amount = amount;
        this.price = price;
        this.sell = sell;
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public int getPrice() {
        return price;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSell() {
        return sell;
    }

    public int getAmount() {
        return amount;
    }

    public String getMaterial() {return material;}
}
