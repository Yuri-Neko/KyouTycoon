package id.kyou.tycoon;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class KyouTycoon extends JavaPlugin implements Listener {
    private ConfigManager config; private Database database; private TycoonService service; private TycoonWorld world; private GUI gui; private Commands commands;
    @Override public void onEnable(){
        saveDefaultConfig(); config=new ConfigManager(this);config.load();
        try{database=new Database(this);database.open();}catch(Exception e){getLogger().severe("SQLite initialization failed: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        service=new TycoonService(this,database);service.load();world=new TycoonWorld(this);gui=new GUI(this);commands=new Commands(this);commands.register();getServer().getPluginManager().registerEvents(this,this);
        if(getConfig().getBoolean("world.enabled",true))world.getOrCreate();
        long interval=Math.max(1,getConfig().getLong("machines.update_interval",20));getServer().getScheduler().runTaskTimer(this,service::processMachines,interval,interval);
        long market=Math.max(20,getConfig().getLong("market.price_update_interval",300)*20L);getServer().getScheduler().runTaskTimer(this,service::marketTick,market,market);
        long save=Math.max(20,getConfig().getLong("performance.database_save_interval",300)*20L);getServer().getScheduler().runTaskTimerAsynchronously(this,service::saveAll,save,save);
        getLogger().info("KyouTycoon enabled.");
    }
    @Override public void onDisable(){if(service!=null)service.saveAll();if(database!=null)database.close();}
    void reloadPlugin(){config.reload();service.loadConfigData();}
    ConfigManager getConfigManager(){return config;}
    String msg(String key){return msg(key,Map.of());}
    String msg(String key,Map<String,?> vals){String s=config.messages().getString(key,"&cMissing message: "+key);java.util.HashMap<String,Object> m=new java.util.HashMap<>(vals);m.put("prefix",config.messages().getString("prefix","&8[&aKyouTycoon&8] "));for(var e:m.entrySet())s=s.replace("{"+e.getKey()+"}",String.valueOf(e.getValue()));return ChatColor.translateAlternateColorCodes('&',s);}
    ConfigManager config(){return config;} TycoonService service(){return service;} TycoonWorld world(){return world;} GUI gui(){return gui;}

    @EventHandler public void join(PlayerJoinEvent e){service.markOnline(e.getPlayer().getUniqueId());service.ensurePlayer(e.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent e){service.markOffline(e.getPlayer().getUniqueId());service.savePlayer(e.getPlayer().getUniqueId());}
    @EventHandler public void interact(PlayerInteractEvent e){
        if(e.getClickedBlock()==null)return; Block b=e.getClickedBlock();TycoonService.Machine m=service.machine(b);if(m==null)return;e.setCancelled(true);
        if(!m.owner.equals(e.getPlayer().getUniqueId())){e.getPlayer().sendMessage(msg("not_your_plot"));return;}gui.machine(e.getPlayer(),m);
    }
    @EventHandler public void place(BlockPlaceEvent e){
        Player p=e.getPlayer();Block b=e.getBlock();TycoonService.Plot q=service.at(b.getWorld(),b.getX(),b.getZ());
        if(q!=null&&q.owner!=null&&q.owner.equals(p.getUniqueId())){TycoonService.MachineDef d=service.defs.values().stream().filter(x->x.block()==b.getType()).findFirst().orElse(null);if(d!=null&&d.enabled()&&service.machineCount(p.getUniqueId())<getConfig().getInt("plot.max_machines_by_level."+q.level,30))service.createMachine(p,b,d.id());}
        if(isProtectedArea(p,b.getWorld(),b.getX(),b.getZ())&&q!=null&&q.owner!=null&&!q.owner.equals(p.getUniqueId()))e.setCancelled(true);
    }
    @EventHandler public void breakBlock(BlockBreakEvent e){Block b=e.getBlock();TycoonService.Machine m=service.machine(b);if(m!=null&&!m.owner.equals(e.getPlayer().getUniqueId())){e.setCancelled(true);return;}if(isProtectedArea(e.getPlayer(),b.getWorld(),b.getX(),b.getZ())){TycoonService.Plot q=service.at(b.getWorld(),b.getX(),b.getZ());if(q!=null&&q.owner!=null&&!q.owner.equals(e.getPlayer().getUniqueId()))e.setCancelled(true);}}
    @EventHandler public void inventory(InventoryClickEvent e){String t=e.getView().getTitle();if(t.contains("KyouTycoon")||t.contains("Machine")||t.contains("Warehouse"))e.setCancelled(true);}
    @EventHandler public void damage(EntityDamageByEntityEvent e){if(!getConfig().getBoolean("world_protection.pvp",false))if(e.getEntity().getWorld().getName().equals(getConfig().getString("world.name","tycoon_world"))&&e.getDamager() instanceof org.bukkit.entity.Player)e.setCancelled(true);}
    @EventHandler public void spawn(CreatureSpawnEvent e){if(!getConfig().getBoolean("world_protection.mob_spawn",false)&&e.getLocation().getWorld().getName().equals(getConfig().getString("world.name","tycoon_world")))e.setCancelled(true);}
    private boolean isProtectedArea(org.bukkit.entity.Player p,World w,int x,int z){return w.getName().equals(getConfig().getString("world.name","tycoon_world"));}
}
