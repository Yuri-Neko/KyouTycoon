package id.kyou.tycoon;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

final class Commands implements CommandExecutor,TabCompleter {
    private final KyouTycoon p; Commands(KyouTycoon p){this.p=p;}
    void register(){for(String n:new String[]{"tycoon","plot","balance","pay","baltop","company","market","contract","research","prestige","tyadmin"}){PluginCommand c=p.getCommand(n);if(c!=null){c.setExecutor(this);c.setTabCompleter(this);}}}
    @Override public boolean onCommand(CommandSender s,Command c,String label,String[] a){
        if(label.equalsIgnoreCase("tyadmin")){return admin(s,a);} if(!(s instanceof Player x)){s.sendMessage(p.msg("player_only"));return true;}
        p.service().ensurePlayer(x);String sub=a.length==0?"":a[0].toLowerCase();
        switch(label.toLowerCase()){
            case "tycoon"->{
                if(sub.equals("reload")){
                    if(!x.hasPermission("kyoutycoon.admin.reload")){x.sendMessage(p.msg("no_permission"));return true;}
                    p.reloadPlugin();x.sendMessage(p.msg("reload"));
                }else if(sub.equals("deposit")){transfer(x,a,true);}
                else if(sub.equals("withdraw")){transfer(x,a,false);}
                else if(sub.equals("warehouse")){warehouse(x);}
                else if(sub.equals("machine")){machine(x,a);}
                else if(sub.equals("help")){help(x);}
                else p.gui().main(x);
            }
            case "plot"->plot(x,sub);
            case "balance"->x.sendMessage("&aBalance: &f$"+String.format("%.2f",p.service().balance(x.getUniqueId())));
            case "pay"->pay(x,a);
            case "baltop"->baltop(x);
            case "company"->company(x,a);
            case "market"->market(x,a);
            case "contract"->contract(x,a);
            case "research"->research(x,a);
            case "prestige"->{double value=p.service().companyValue(x.getUniqueId());boolean ok=p.service().prestige(x.getUniqueId(),value);x.sendMessage(ok?p.msg("prestige_done",Map.of("level",p.service().prestige(x.getUniqueId()))):p.msg("prestige_unavailable"));}
        } return true;
    }
    private void plot(Player x,String sub){TycoonService.Plot q=p.service().plot(x.getUniqueId());if(sub.equals("claim")){TycoonService.Plot n=p.service().claim(x.getUniqueId());if(n==null){x.sendMessage(q!=null?p.msg("plot_already_owned"):p.msg("no_empty_plot"));return;}x.sendMessage(p.msg("plot_claimed",Map.of("plot",n.id)));home(x,n);}else if(sub.equals("home")){if(q==null){x.sendMessage(p.msg("no_plot"));return;}home(x,q);}else if(sub.equals("info")){if(q==null){x.sendMessage(p.msg("no_plot"));return;}x.sendMessage("&aPlot #"+q.id+" &7Level: &f"+q.level+" &7Size: &f"+(q.maxX-q.minX+1)+"x"+(q.maxZ-q.minZ+1)+" &7Machines: &f"+p.service().machineCount(x.getUniqueId())+"/"+p.getConfig().getInt("plot.max_machines_by_level."+q.level,30));}else if(sub.equals("upgrade")){if(q==null){x.sendMessage(p.msg("no_plot"));return;}int next=q.level+1;double cost=p.getConfig().getDouble("plot.upgrade_costs."+next,-1);if(cost<0||!p.service().withdraw(x.getUniqueId(),cost)){x.sendMessage(p.msg("insufficient_money"));return;}q.level=next;p.service().savePlot(q);x.sendMessage(p.msg("plot_upgraded",Map.of("level",q.level)));}else x.sendMessage("/plot claim|home|info|upgrade");}
    private void home(Player x,TycoonService.Plot q){World w=Bukkit.getWorld(q.world);if(w!=null)x.teleport(new Location(w,(q.minX+q.maxX)/2.0,65,(q.minZ+q.maxZ)/2.0));}
    private void pay(Player x,String[] a){if(a.length<2)return;Player t=Bukkit.getPlayerExact(a[0]);if(t==null){x.sendMessage(p.msg("invalid_player"));return;}try{double v=Double.parseDouble(a[1]);if(v<=0||!p.service().withdraw(x.getUniqueId(),v)){x.sendMessage(p.msg("insufficient_money"));return;}p.service().deposit(t.getUniqueId(),v);x.sendMessage(p.msg("payment_sent",Map.of("amount",v,"player",t.getName())));t.sendMessage(p.msg("payment_received",Map.of("amount",v,"player",x.getName())));}catch(NumberFormatException e){x.sendMessage(p.msg("invalid_amount"));}}
    private void baltop(Player x){x.sendMessage("&6Balance Top");p.service().players.values().stream().sorted(Comparator.comparingDouble((TycoonService.PlayerData d)->d.balance()).reversed()).limit(10).forEach(d->x.sendMessage("&e"+d.name()+" &f$"+String.format("%.2f",d.balance())));}
    private void company(Player player,String[] args){
        if(args.length==0||args[0].equalsIgnoreCase("info")){
            TycoonService.Company company=p.service().company(player.getUniqueId());
            if(company==null){player.sendMessage(p.msg("company_not_found"));return;}
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',"&aCompany: &f"+company.name()+" &7Rep: &f"+company.reputation()));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&',"&7Revenue: &f$"+String.format(Locale.US,"%,.2f",company.revenue())
                    +" &7Assets: &f$"+String.format(Locale.US,"%,.2f",company.assets())));
            return;
        }
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "create"->{
                if(args.length<2){player.sendMessage("&eUsage: /company create <name>");return;}
                String name=String.join(" ",Arrays.copyOfRange(args,1,args.length));
                TycoonService.Company created=p.service().createCompany(player.getUniqueId(),name);
                player.sendMessage(created==null?p.msg("company_exists"):p.msg("company_created",Map.of("name",created.name())));
            }
            case "invite"->{
                if(args.length<2){player.sendMessage("&eUsage: /company invite <player>");return;}
                boolean ok=p.service().inviteCompanyMember(player.getUniqueId(),args[1]);
                player.sendMessage(ok?p.msg("invite_sent",Map.of("player",args[1])):p.msg("company_no_permission"));
                if(ok&&Bukkit.getPlayerExact(args[1])!=null){
                    Player target=Bukkit.getPlayerExact(args[1]);
                    target.sendMessage(p.msg("invite_received",Map.of("name",p.service().company(player.getUniqueId()).name())));
                }
            }
            case "members"->{
                List<id.kyou.tycoon.company.CompanyManager.Member> members=p.service().companyMembers(player.getUniqueId());
                if(members.isEmpty()){player.sendMessage(p.msg("company_not_found"));return;}
                player.sendMessage("&6Company members:");
                for(var member:members)player.sendMessage("&e"+p.service().playerName(member.uuid())+" &7- &f"+member.role().name());
            }
            case "leave"->{
                boolean left=p.service().leaveCompany(player.getUniqueId());
                player.sendMessage(left?p.msg("company_left"):p.msg("company_no_permission"));
            }
            case "kick","remove"->{
                if(args.length<2){player.sendMessage("&eUsage: /company kick <player>");return;}
                boolean removed=p.service().removeCompanyMember(player.getUniqueId(),args[1]);
                player.sendMessage(removed?p.msg("company_member_removed",Map.of("player",args[1])):p.msg("company_no_permission"));
            }
            case "employee"->{
                if(args.length>=3&&args[1].equalsIgnoreCase("hire")){
                    var employee=p.service().hireEmployee(player.getUniqueId(),args[2]);
                    player.sendMessage(employee==null?p.msg("company_no_permission"):
                            ChatColor.translateAlternateColorCodes('&',"&aHired "+employee.type()+" employee #"+employee.id()+" for $"+String.format(Locale.US,"%.2f",employee.salary())+"/pay cycle."));
                }else if(args.length>=2&&args[1].equalsIgnoreCase("list")){
                    var employees=p.service().employees(player.getUniqueId());
                    player.sendMessage("&6Employees: "+employees.size());
                    for(var employee:employees)player.sendMessage("&e#"+employee.id()+" "+employee.type()+" &7$"+employee.salary()+" &f"+(employee.active()?"ACTIVE":"PAUSED"));
                }else player.sendMessage("&eUsage: /company employee hire <type> | /company employee list");
            }
            default->player.sendMessage("&e/company create <name> | invite <player> | members | info | leave | kick <player> | employee hire <type>");
        }
    }

    private void research(Player player,String[] args){
        List<String> ids=p.service().researchIds();
        Set<String> unlocked=p.service().researchUnlocked(player.getUniqueId());
        if(args.length>=2&&args[0].equalsIgnoreCase("unlock")){
            String id=args[1].toLowerCase(Locale.ROOT);
            var result=p.service().unlockResearch(player.getUniqueId(),id);
            switch(result){
                case UNLOCKED->player.sendMessage(p.msg("research_unlocked",Map.of("research",id)));
                case ALREADY_UNLOCKED->player.sendMessage(p.msg("research_already_unlocked"));
                case MISSING_PREREQUISITE->player.sendMessage(p.msg("research_missing_prerequisite"));
                case INSUFFICIENT_FUNDS->player.sendMessage(p.msg("research_insufficient_funds"));
                case UNKNOWN,INVALID,PLAYER_NOT_FOUND->player.sendMessage(p.msg("research_unknown"));
            }
            return;
        }
        player.sendMessage("&6Research tree:");
        for(String id:ids){
            String name=p.config().research().getString("research."+id+".name",id);
            double cost=p.config().research().getDouble("research."+id+".cost",0);
            player.sendMessage((unlocked.contains(id)?"&a[UNLOCKED] ":"&c[LOCKED] ")+ChatColor.translateAlternateColorCodes('&',name)
                    +" &7- &f$"+String.format(Locale.US,"%,.2f",cost));
        }
        player.sendMessage("&7Use /research unlock <research_id>.");
    }

    private void contract(Player player,String[] args){
        long now=System.currentTimeMillis()/1000;
        if(args.length>=2&&args[0].equalsIgnoreCase("accept")){
            String id=args[1].toLowerCase(Locale.ROOT);
            boolean known=p.service().contractDefinitions().stream().anyMatch(c->c.id().equals(id));
            if(!known){player.sendMessage(p.msg("contract_not_found"));return;}
            if(p.service().acceptContract(player.getUniqueId(),id)){player.sendMessage(p.msg("contract_accepted",Map.of("contract",id)));return;}
            boolean active=p.service().activeContracts(player.getUniqueId()).stream().anyMatch(c->c.id().equals(id));
            player.sendMessage(active?p.msg("contract_already_active"):p.msg("contract_not_found"));
            return;
        }
        if(args.length>=2&&args[0].equalsIgnoreCase("complete")){
            var result=p.service().completeContract(player.getUniqueId(),args[1].toLowerCase(Locale.ROOT));
            if(result.isEmpty()){player.sendMessage(p.msg("contract_not_found"));return;}
            var completion=result.get();
            if(completion.success())player.sendMessage(p.msg("contract_complete",Map.of("amount",String.format(Locale.US,"%,.2f",completion.reward()))));
            else if(completion.reason().equals("expired"))player.sendMessage(p.msg("contract_expired"));
            else if(completion.reason().equals("requirements-incomplete"))player.sendMessage(p.msg("contract_incomplete"));
            else player.sendMessage(p.msg("contract_not_found"));
            return;
        }
        player.sendMessage("&6Available contracts:");
        for(var definition:p.service().contractDefinitions()){
            player.sendMessage("&e"+definition.id()+" &7- &f$"+String.format(Locale.US,"%,.2f",definition.reward())
                    +" &7Requirements: &f"+definition.requirements()+" &7Time: &f"+definition.durationSeconds()+"s");
        }
        var active=p.service().activeContracts(player.getUniqueId());
        if(!active.isEmpty()){
            player.sendMessage("&6Your active contracts:");
            for(var contract:active)player.sendMessage("&e"+contract.id()+" &7Progress: &f"+contract.progress()
                    +" &7Expires in: &f"+Math.max(0,contract.expiresAt()-now)+"s"+(contract.readyToComplete()?" &aREADY":""));
        }
        player.sendMessage("&7Use /contract accept <id> then /contract complete <id>.");
    }
    private void transfer(Player x,String[] a,boolean intoWarehouse){
        if(a.length<3){x.sendMessage("&eUsage: /tycoon "+(intoWarehouse?"deposit":"withdraw")+" <MATERIAL> <amount>");return;}
        Material material=Material.matchMaterial(a[1]);int amount;
        try{amount=Integer.parseInt(a[2]);}catch(NumberFormatException ex){x.sendMessage(p.msg("invalid_amount"));return;}
        if(material==null||amount<1){x.sendMessage(p.msg("invalid_amount"));return;}
        boolean ok=intoWarehouse?p.service().depositInventory(x,material,amount):p.service().withdrawToInventory(x,material,amount);
        if(!ok){x.sendMessage(intoWarehouse?p.msg("warehouse_transfer_failed"):p.msg("warehouse_transfer_failed"));return;}
        x.sendMessage(p.msg(intoWarehouse?"warehouse_deposit":"warehouse_withdraw",Map.of("amount",amount,"material",material.name())));
    }
    private void warehouse(Player x){
        x.sendMessage("&6Warehouse &7("+p.service().warehouseUsed(x.getUniqueId())+"/"+p.service().warehouseCapacity(x.getUniqueId())+")");
        p.service().warehouseItems(x.getUniqueId()).entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name))).forEach(e->x.sendMessage("&e"+e.getKey().name()+" &f"+e.getValue()));
        x.sendMessage("&7Deposit: /tycoon deposit <MATERIAL> <amount> | Withdraw: /tycoon withdraw <MATERIAL> <amount>");
    }
    private void market(Player x,String[] a){
        if(a.length>=3&&a[0].equalsIgnoreCase("sell")){
            Material material=Material.matchMaterial(a[1]);int amount;
            try{amount=Integer.parseInt(a[2]);}catch(NumberFormatException ex){x.sendMessage(p.msg("invalid_amount"));return;}
            if(material==null||amount<1||!p.service().sellFromWarehouse(x.getUniqueId(),material,amount)){x.sendMessage(p.msg("market_sell_failed"));return;}
            x.sendMessage(p.msg("market_sold",Map.of("amount",amount,"material",material.name())));
            return;
        }
        x.sendMessage("&6Market &7- use /market sell <MATERIAL> <amount>");
        p.service().shop.forEach((m,v)->x.sendMessage("&e"+m+" &f$"+String.format("%.2f",p.service().marketPrice(m))));
    }
    private void machine(Player x,String[] a){
        if(a.length<3||!a[1].equalsIgnoreCase("register")){x.sendMessage("&eUsage: /tycoon machine register <machine_id> while looking at the block");return;}
        var block=x.getTargetBlockExact(6);
        if(block==null){x.sendMessage("&cLook at the machine block within 6 blocks.");return;}
        TycoonService.Machine m=p.service().createMachine(x,block,a[2].toLowerCase(Locale.ROOT));
        x.sendMessage(m==null?"&cCould not register this block. Check plot ownership, machine ID, block type and machine limit.":"&aMachine registered: &f"+m.type);
    }
    private void help(Player x){x.sendMessage("&aKyouTycoon commands");for(String line:List.of("/plot claim|home|info|upgrade","/balance","/pay <player> <amount>","/market","/market sell <MATERIAL> <amount>","/tycoon warehouse","/tycoon deposit <MATERIAL> <amount>","/tycoon withdraw <MATERIAL> <amount>","/tycoon machine register <machine_id>","/company create <name>","/research unlock <id>","/contract accept <id>"))x.sendMessage("&e"+line);}
    private boolean admin(CommandSender s,String[] a){if(!s.hasPermission("kyoutycoon.admin")){s.sendMessage(p.msg("no_permission"));return true;}if(a.length>=2&&a[0].equalsIgnoreCase("world")&&a[1].equalsIgnoreCase("create")){if(p.world().exists())s.sendMessage(p.msg("world_exists"));else{p.world().create();s.sendMessage(p.msg("world_created"));}}else if(a.length>0&&a[0].equalsIgnoreCase("reload")){p.reloadPlugin();s.sendMessage(p.msg("reload"));}else if(a.length>0&&a[0].equalsIgnoreCase("debug")){s.sendMessage(p.msg("debug",Map.of("players",p.service().players.size(),"machines",p.service().machines.size(),"plots",p.service().plots.size())));}else s.sendMessage("/tyadmin world create|reload|debug");return true;}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String label,String[] args){
        String command=label.toLowerCase(Locale.ROOT);
        if(args.length==1)return switch(command){
            case "plot"->List.of("claim","home","info","upgrade");
            case "company"->List.of("create","invite","members","info","leave","kick","employee");
            case "tyadmin"->List.of("world","reload","debug");
            case "tycoon"->List.of("help","reload","warehouse","deposit","withdraw","machine");
            case "contract"->List.of("list","accept","complete");
            case "research"->List.of("list","unlock");
            default->List.of();
        };
        if(command.equals("contract")&&args.length==2&&(args[0].equalsIgnoreCase("accept")||args[0].equalsIgnoreCase("complete")))
            return p.service().contractDefinitions().stream().map(id->id.id()).toList();
        if(command.equals("research")&&args.length==2&&args[0].equalsIgnoreCase("unlock"))
            return p.service().researchIds();
        if(command.equals("company")&&args.length==2&&args[0].equalsIgnoreCase("employee"))return List.of("hire","list");
        return List.of();
    }
}
