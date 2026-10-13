package id.kyou.tycoon;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import java.util.List;

final class GUI {
    private final KyouTycoon p; GUI(KyouTycoon p){this.p=p;}
    void main(Player x){Inventory i=Bukkit.createInventory(null,27,color(p.config().gui().getString("titles.main","&8KyouTycoon")));Material[] ms={Material.GOLD_INGOT,Material.GRASS_BLOCK,Material.FURNACE,Material.CHEST,Material.LIGHTNING_ROD,Material.IRON_HELMET,Material.ENCHANTING_TABLE,Material.EMERALD,Material.PAPER,Material.IRON_BLOCK,Material.NETHER_STAR,Material.NETHERITE_INGOT};String[] ns={"Economy","Plot","Machines","Warehouse","Energy","Employees","Research","Market","Contracts","Company","Achievements","Prestige"};for(int n=0;n<ns.length;n++){ItemStack it=new ItemStack(ms[n]);var m=it.getItemMeta();m.setDisplayName(color("&a"+ns[n]));it.setItemMeta(m);i.setItem(n,it);}x.openInventory(i);}
    void machine(Player x,TycoonService.Machine m){Inventory i=Bukkit.createInventory(null,27,color("&8"+m.type));ItemStack it=new ItemStack(Material.PAPER);var meta=it.getItemMeta();meta.setDisplayName(color("&e"+m.type));meta.setLore(List.of(color("&7Level: &f"+m.level),color("&7State: &f"+m.state),color("&7Progress: &f"+String.format("%.1f",m.progress))));it.setItemMeta(meta);i.setItem(13,it);x.openInventory(i);}
    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s==null?"":s);}
}
