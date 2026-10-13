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
            case "tycoon"->{if(sub.equals("reload")){if(!x.hasPermission("kyoutycoon.admin.reload")){x.sendMessage(p.msg("no_permission"));return true;}p.reloadPlugin();x.sendMessage(p.msg("reload"));}else p.gui().main(x);}
            case "plot"->plot(x,sub);
            case "balance"->x.sendMessage("&aBalance: &f$"+String.format("%.2f",p.service().balance(x.getUniqueId())));
            case "pay"->pay(x,a);
            case "baltop"->baltop(x);
            case "company"->company(x,a);
            case "market"->market(x);
            case "contract"->x.sendMessage("&eAvailable contracts: bakery_contract, iron_contract");
            case "research"->x.sendMessage("&bResearch: automation_1, automation_2");
            case "prestige"->{boolean ok=p.service().prestige(x.getUniqueId(),0);x.sendMessage(ok?p.msg("prestige_done",Map.of("level",p.service().prestige(x.getUniqueId()))):p.msg("prestige_unavailable"));}
        } return true;
    }
    private void plot(Player x,String sub){TycoonService.Plot q=p.service().plot(x.getUniqueId());if(sub.equals("claim")){TycoonService.Plot n=p.service().claim(x.getUniqueId());if(n==null){x.sendMessage(q!=null?p.msg("plot_already_owned"):p.msg("no_empty_plot"));return;}x.sendMessage(p.msg("plot_claimed",Map.of("plot",n.id)));home(x,n);}else if(sub.equals("home")){if(q==null){x.sendMessage(p.msg("no_plot"));return;}home(x,q);}else if(sub.equals("info")){if(q==null){x.sendMessage(p.msg("no_plot"));return;}x.sendMessage("&aPlot #"+q.id+" &7Level: &f"+q.level+" &7Size: &f"+(q.maxX-q.minX+1)+"x"+(q.maxZ-q.minZ+1)+" &7Machines: &f"+p.service().machineCount(x.getUniqueId())+"/"+p.getConfig().getInt("plot.max_machines_by_level."+q.level,30));}else if(sub.equals("upgrade")){if(q==null){x.sendMessage(p.msg("no_plot"));return;}int next=q.level+1;double cost=p.getConfig().getDouble("plot.upgrade_costs."+next,-1);if(cost<0||!p.service().withdraw(x.getUniqueId(),cost)){x.sendMessage(p.msg("insufficient_money"));return;}q.level=next;p.service().savePlot(q);x.sendMessage(p.msg("plot_upgraded",Map.of("level",q.level)));}else x.sendMessage("/plot claim|home|info|upgrade");}
    private void home(Player x,TycoonService.Plot q){World w=Bukkit.getWorld(q.world);if(w!=null)x.teleport(new Location(w,(q.minX+q.maxX)/2.0,65,(q.minZ+q.maxZ)/2.0));}
    private void pay(Player x,String[] a){if(a.length<2)return;Player t=Bukkit.getPlayerExact(a[0]);if(t==null){x.sendMessage(p.msg("invalid_player"));return;}try{double v=Double.parseDouble(a[1]);if(v<=0||!p.service().withdraw(x.getUniqueId(),v)){x.sendMessage(p.msg("insufficient_money"));return;}p.service().deposit(t.getUniqueId(),v);x.sendMessage(p.msg("payment_sent",Map.of("amount",v,"player",t.getName())));t.sendMessage(p.msg("payment_received",Map.of("amount",v,"player",x.getName())));}catch(NumberFormatException e){x.sendMessage(p.msg("invalid_amount"));}}
    private void baltop(Player x){x.sendMessage("&6Balance Top");p.service().players.values().stream().sorted(Comparator.comparingDouble((TycoonService.PlayerData d)->d.balance()).reversed()).limit(10).forEach(d->x.sendMessage("&e"+d.name()+" &f$"+String.format("%.2f",d.balance())));}
    private void company(Player x,String[] a){TycoonService.Company c=p.service().company(x.getUniqueId());if(a.length==0||a[0].equalsIgnoreCase("info")){x.sendMessage(c==null?p.msg("company_not_found"):"&aCompany: &f"+c.name+" &7Rep: &f"+c.reputation);return;}if(a[0].equalsIgnoreCase("create")&&a.length>1){TycoonService.Company n=p.service().createCompany(x.getUniqueId(),a[1]);x.sendMessage(n==null?p.msg("company_exists"):p.msg("company_created",Map.of("name",n.name)));}else if(a[0].equalsIgnoreCase("members")&&c!=null)x.sendMessage("&aCompany ID: "+c.id);else x.sendMessage("/company create <name>|members|info");}
    private void market(Player x){x.sendMessage("&6Market");p.service().shop.forEach((m,v)->x.sendMessage("&e"+m+" &f$"+String.format("%.2f",p.service().marketPrice(m))));}
    private boolean admin(CommandSender s,String[] a){if(!s.hasPermission("kyoutycoon.admin")){s.sendMessage(p.msg("no_permission"));return true;}if(a.length>=2&&a[0].equalsIgnoreCase("world")&&a[1].equalsIgnoreCase("create")){World old=Bukkit.getWorld(p.getConfig().getString("world.name","tycoon_world"));if(old!=null)s.sendMessage(p.msg("world_exists"));else{p.world().create();s.sendMessage(p.msg("world_created"));}}else if(a.length>0&&a[0].equalsIgnoreCase("reload")){p.reloadPlugin();s.sendMessage(p.msg("reload"));}else if(a.length>0&&a[0].equalsIgnoreCase("debug")){s.sendMessage(p.msg("debug",Map.of("players",p.service().players.size(),"machines",p.service().machines.size(),"plots",p.service().plots.size())));}else s.sendMessage("/tyadmin world create|reload|debug");return true;}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String l,String[] a){if(a.length!=1)return List.of();return switch(l.toLowerCase()){case "plot"->List.of("claim","home","info","upgrade");case "company"->List.of("create","members","info");case "tyadmin"->List.of("world","reload","debug");case "tycoon"->List.of("help","reload");default->List.of();};}
}
