package id.kyou.tycoon;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;

final class ConfigManager {
    private final KyouTycoon p; private FileConfiguration machines,recipes,shops,research,contracts,messages,gui;
    ConfigManager(KyouTycoon p){this.p=p;}
    void load(){p.saveDefaultConfig();for(String n:new String[]{"machines.yml","recipes.yml","shops.yml","research.yml","contracts.yml","messages.yml","gui.yml"}){File f=new File(p.getDataFolder(),n);if(!f.exists())p.saveResource(n,false);}reload();}
    void reload(){p.reloadConfig();machines=load("machines.yml");recipes=load("recipes.yml");shops=load("shops.yml");research=load("research.yml");contracts=load("contracts.yml");messages=load("messages.yml");gui=load("gui.yml");}
    private FileConfiguration load(String n){return YamlConfiguration.loadConfiguration(new File(p.getDataFolder(),n));}
    FileConfiguration machines(){return machines;}FileConfiguration recipes(){return recipes;}FileConfiguration shops(){return shops;}FileConfiguration research(){return research;}FileConfiguration contracts(){return contracts;}FileConfiguration messages(){return messages;}FileConfiguration gui(){return gui;}
}
