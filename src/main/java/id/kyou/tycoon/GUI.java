package id.kyou.tycoon;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/** Interactive vanilla-inventory menus for the core tycoon systems. */
final class GUI {
    private final KyouTycoon p;
    GUI(KyouTycoon plugin) { p = Objects.requireNonNull(plugin); }

    void main(Player player) {
        Menu h = open("main", size("main_size",27), title("main","&8KyouTycoon"));
        Material[] icons = {Material.GOLD_INGOT,Material.GRASS_BLOCK,Material.FURNACE,Material.CHEST,
                Material.LIGHTNING_ROD,Material.IRON_HELMET,Material.ENCHANTING_TABLE,Material.EMERALD,
                Material.PAPER,Material.IRON_BLOCK,Material.NETHER_STAR,Material.NETHERITE_INGOT};
        String[] labels = {"Economy","Plot","Machines","Warehouse","Energy","Employees","Research","Market","Contracts","Company","Achievements","Prestige"};
        String[] pages = {"economy","plot","machines","warehouse","energy","employees","research","market","contracts","company","achievements","prestige"};
        int[] slots = {10,11,12,13,14,15,16,17,19,20,21,22};
        for(int i=0;i<labels.length;i++){item(h,slots[i],icons[i],"&a"+labels[i],List.of("&7Click to open"));h.actions.put(slots[i],pages[i]);}
        player.openInventory(h.inv);
    }

    void machine(Player player, TycoonService.Machine machine) {
        Menu h=open("machine",size("machine_size",27),title("machine","&8Machine"));
        h.machineId=machine.id;
        item(h,4,icon(machine.type),"&e"+machine.type,List.of("&7Level: &f"+machine.level,"&7State: &f"+machine.state,
                "&7Progress: &f"+String.format(Locale.ROOT,"%.1f",machine.progress)+"s",
                "&7Location: &f"+machine.x+", "+machine.y+", "+machine.z));
        int next=machine.level+1;
        int max=p.config().machines().getInt("upgrade.max_level",5);
        if(next<=max){
            double cost=p.config().machines().getDouble("upgrade.levels."+next+".cost",-1);
            item(h,11,Material.ANVIL,"&eUpgrade",List.of("&7Next level: &f"+next,"&7Cost: &f$"+money(cost),"&8Click to upgrade"));
            h.actions.put(11,"upgrade-machine");
        }else item(h,11,Material.BARRIER,"&cMaximum level",List.of("&7Fully upgraded"));
        item(h,22,Material.ARROW,"&eBack",List.of());
        h.actions.put(22,"machines");
        player.openInventory(h.inv);
    }

    boolean handleClick(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Menu h))return false;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||event.getClickedInventory()!=event.getView().getTopInventory())return true;
        int slot=event.getRawSlot();
        if(slot<0||slot>=h.inv.getSize())return true;
        String action=h.actions.get(slot);
        if(action!=null){
            switch(action){
                case "main"->main(player);
                case "economy"->info(player,"economy","&8Economy",Material.GOLD_INGOT,"&aBalance: &f$"+money(p.service().balance(player.getUniqueId())),"&7Use /balance and /pay <player> <amount>.");
                case "plot"->plot(player);
                case "machines"->machines(player);
                case "warehouse"->warehouse(player);
                case "energy"->{TycoonService.Energy e=p.service().energy(player.getUniqueId());info(player,"energy","&8Energy",Material.LIGHTNING_ROD,"&7Stored: &f"+e.stored+"/"+e.capacity,"&7Place a registered generator and keep its fuel in your warehouse.");}
                case "employees"->info(player,"employees","&8Employees",Material.IRON_HELMET,"&7Employee payroll is linked to company management.","&7Use /company info.");
                case "research"->info(player,"research","&8Research",Material.ENCHANTING_TABLE,"&7Research unlocks production bonuses.","&7Use /research unlock <research_id>.");
                case "market"->market(player);
                case "contracts"->info(player,"contracts","&8Contracts",Material.PAPER,"&7Complete timed production orders for rewards.","&7Use /contract accept <contract_id>.");
                case "company"->info(player,"company","&8Company",Material.IRON_BLOCK,"&7Company membership and employee commands.","&7Use /company create, /company invite, /company members, /company leave.");
                case "achievements"->info(player,"achievements","&8Achievements",Material.NETHER_STAR,"&7Achievement unlocks are stored in SQLite.");
                case "prestige"->info(player,"prestige","&8Prestige",Material.NETHERITE_INGOT,"&7Prestige resets configured progress and grants permanent bonuses.","&7Use /prestige.");
                case "plot-claim"->claim(player);
                case "plot-home"->home(player);
                case "plot-info"->plotInfo(player);
                case "plot-upgrade"->upgradePlot(player);
                case "warehouse-help"->{player.closeInventory();player.sendMessage(color("&e/tycoon deposit <MATERIAL> <amount>"));player.sendMessage(color("&e/tycoon withdraw <MATERIAL> <amount>"));}
                case "upgrade-machine"->upgradeMachine(player,h.machineId);
                default->player.sendMessage(color("&cAction not available."));
            }
            return true;
        }
        if(h.page.equals("machines")){
            UUID id=h.machineIds.get(slot);
            TycoonService.Machine m=id==null?null:p.service().machines.get(id);
            if(m!=null&&m.owner.equals(player.getUniqueId()))machine(player,m);
        }else if(h.page.equals("market")){
            Material m=h.materials.get(slot);
            if(m!=null){
                if(p.service().sellFromWarehouse(player.getUniqueId(),m,1)){player.sendMessage(p.msg("market_sold",Map.of("amount",1,"material",m.name())));market(player);}
                else player.sendMessage(p.msg("market_sell_failed"));
            }
        }
        return true;
    }

    boolean handleDrag(InventoryDragEvent event) {
        return event.getView().getTopInventory().getHolder() instanceof Menu;
    }

    private void plot(Player player){
        Menu h=open("plot",27,title("plot","&8Your Plot"));
        TycoonService.Plot q=p.service().plot(player.getUniqueId());
        if(q==null){item(h,13,Material.GRASS_BLOCK,"&aClaim plot",List.of("&7Claim the next available plot"));h.actions.put(13,"plot-claim");}
        else{
            item(h,10,Material.COMPASS,"&aPlot home",List.of("&7Teleport to plot #"+q.id));h.actions.put(10,"plot-home");
            item(h,12,Material.BOOK,"&aPlot info",List.of("&7Level: &f"+q.level,"&7Size: &f"+(q.maxX-q.minX+1)+"x"+(q.maxZ-q.minZ+1),"&7Machines: &f"+p.service().machineCount(player.getUniqueId())));h.actions.put(12,"plot-info");
            item(h,14,Material.ANVIL,"&eUpgrade plot",List.of("&7Next cost: &f$"+money(p.getConfig().getDouble("plot.upgrade_costs."+(q.level+1),-1))));h.actions.put(14,"plot-upgrade");
        }
        item(h,22,Material.ARROW,"&eBack",List.of());h.actions.put(22,"main");player.openInventory(h.inv);
    }

    private void machines(Player player){
        Menu h=open("machines",54,title("machines","&8Your Machines"));
        List<TycoonService.Machine> owned=p.service().machines.values().stream().filter(m->m.owner.equals(player.getUniqueId()))
                .sorted(Comparator.comparingLong(m->m.createdAt)).limit(45).toList();
        int slot=0;
        for(TycoonService.Machine m:owned){item(h,slot,icon(m.type),"&e"+m.type,List.of("&7Level: &f"+m.level,"&7State: &f"+m.state,"&7Progress: &f"+String.format(Locale.ROOT,"%.1f",m.progress)+"s","&8Click for details"));h.machineIds.put(slot,m.id);slot++;}
        if(owned.isEmpty())item(h,22,Material.FURNACE,"&7No machines",List.of("&7Register a block with /tycoon machine register <id>."));
        item(h,49,Material.ARROW,"&eBack",List.of());h.actions.put(49,"main");player.openInventory(h.inv);
    }

    private void warehouse(Player player){
        Menu h=open("warehouse",size("warehouse_size",54),title("warehouse","&8Warehouse"));
        item(h,4,Material.CHEST,"&6Storage capacity",List.of("&7Used: &f"+p.service().warehouseUsed(player.getUniqueId()),"&7Capacity: &f"+p.service().warehouseCapacity(player.getUniqueId())));
        int slot=9;
        for(var entry:p.service().warehouseItems(player.getUniqueId()).entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name))).limit(36).toList()){
            item(h,slot++,entry.getKey(),"&e"+entry.getKey().name(),List.of("&7Amount: &f"+entry.getValue()));
        }
        item(h,45,Material.HOPPER,"&eDeposit / Withdraw",List.of("&7Use the commands to transfer vanilla items."));h.actions.put(45,"warehouse-help");
        item(h,49,Material.ARROW,"&eBack",List.of());h.actions.put(49,"main");player.openInventory(h.inv);
    }

    private void market(Player player){
        Menu h=open("market",54,title("market","&8Market"));
        int slot=0;
        for(var entry:p.service().shop.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name))).limit(45).toList()){
            Material material=entry.getKey();
            item(h,slot,material,"&eSell "+material.name(),List.of("&7Price each: &f$"+money(p.service().marketPrice(material)),
                    "&7Warehouse stock: &f"+p.service().warehouseAmount(player.getUniqueId(),material),"&8Click to sell one."));
            h.materials.put(slot,material);slot++;
        }
        item(h,49,Material.ARROW,"&eBack",List.of());h.actions.put(49,"main");player.openInventory(h.inv);
    }

    private void info(Player player,String page,String menuTitle,Material icon,String... lines){
        Menu h=open(page,27,menuTitle);
        item(h,13,icon,"&e"+ChatColor.stripColor(color(menuTitle)),Arrays.stream(lines).map(this::color).toList());
        item(h,22,Material.ARROW,"&eBack",List.of());h.actions.put(22,"main");player.openInventory(h.inv);
    }

    private void claim(Player player){
        TycoonService.Plot q=p.service().claim(player.getUniqueId());
        if(q==null)player.sendMessage(p.msg(p.service().plot(player.getUniqueId())!=null?"plot_already_owned":"no_empty_plot"));
        else{player.sendMessage(p.msg("plot_claimed",Map.of("plot",q.id)));teleport(player,q);}
        plot(player);
    }
    private void home(Player player){TycoonService.Plot q=p.service().plot(player.getUniqueId());if(q==null)player.sendMessage(p.msg("no_plot"));else teleport(player,q);plot(player);}
    private void plotInfo(Player player){
        TycoonService.Plot q=p.service().plot(player.getUniqueId());
        if(q!=null)player.sendMessage(color("&aPlot #"+q.id+" &7Level: &f"+q.level+" &7Size: &f"+(q.maxX-q.minX+1)+"x"+(q.maxZ-q.minZ+1)+" &7Machines: &f"+p.service().machineCount(player.getUniqueId())));
        plot(player);
    }
    private void upgradePlot(Player player){
        TycoonService.Plot q=p.service().plot(player.getUniqueId());
        if(q==null){player.sendMessage(p.msg("no_plot"));plot(player);return;}
        int next=q.level+1;double cost=p.getConfig().getDouble("plot.upgrade_costs."+next,-1);
        if(cost<0||!p.service().withdraw(player.getUniqueId(),cost)){player.sendMessage(p.msg("insufficient_money"));plot(player);return;}
        q.level=next;p.service().savePlot(q);p.service().savePlayer(player.getUniqueId());
        player.sendMessage(p.msg("plot_upgraded",Map.of("level",q.level)));plot(player);
    }
    private void upgradeMachine(Player player,UUID id){
        if(id==null)return;
        switch(p.service().upgradeMachine(player.getUniqueId(),id)){
            case UPGRADED->player.sendMessage(color("&aMachine upgraded."));
            case MAX_LEVEL->player.sendMessage(color("&eMachine already at max level."));
            case NOT_OWNER->player.sendMessage(p.msg("not_your_plot"));
            case INSUFFICIENT_FUNDS->player.sendMessage(p.msg("insufficient_money"));
            case NOT_FOUND->player.sendMessage(color("&cMachine not found."));
        }
        TycoonService.Machine m=p.service().machines.get(id);if(m==null)machines(player);else machine(player,m);
    }
    private void teleport(Player player,TycoonService.Plot q){
        World w=Bukkit.getWorld(q.world);if(w==null){player.sendMessage(color("&cTycoon world is not loaded."));return;}
        player.teleport(new Location(w,(q.minX+q.maxX+1)/2.0,65,(q.minZ+q.maxZ+1)/2.0));
    }

    private Menu open(String page,int size,String menuTitle){Menu h=new Menu(page);h.inv=Bukkit.createInventory(h,size,color(menuTitle));return h;}
    private int size(String key,int fallback){int n=p.config().gui().getInt(key,fallback);if(n<9||n>54)return fallback;return n-n%9;}
    private String title(String key,String fallback){return p.config().gui().getString("titles."+key,fallback);}
    private Material icon(String id){TycoonService.MachineDef d=p.service().defs.get(id);return d==null?Material.FURNACE:d.block();}
    private void item(Menu h,int slot,Material material,String display,List<String> lore){
        if(slot<0||slot>=h.inv.getSize())return;
        ItemStack stack=new ItemStack(material==null?Material.PAPER:material);ItemMeta meta=stack.getItemMeta();
        if(meta!=null){meta.setDisplayName(color(display));if(lore!=null&&!lore.isEmpty())meta.setLore(lore.stream().map(this::color).toList());stack.setItemMeta(meta);}
        h.inv.setItem(slot,stack);
    }
    private String money(double n){return n<0?"N/A":String.format(Locale.US,"%,.2f",n);}
    private String color(String text){return ChatColor.translateAlternateColorCodes('&',text==null?"":text);}

    private static final class Menu implements InventoryHolder {
        private final String page;private Inventory inv;private UUID machineId;
        private final Map<Integer,String> actions=new HashMap<>();
        private final Map<Integer,UUID> machineIds=new HashMap<>();
        private final Map<Integer,Material> materials=new HashMap<>();
        private Menu(String page){this.page=page;}
        @Override public Inventory getInventory(){return inv;}
    }
}
