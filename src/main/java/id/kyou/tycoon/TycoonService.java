package id.kyou.tycoon;

import org.bukkit.*;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import id.kyou.tycoon.economy.ContractManager;
import id.kyou.tycoon.progression.AchievementManager;
import id.kyou.tycoon.progression.PrestigeManager;
import id.kyou.tycoon.progression.ResearchManager;
import id.kyou.tycoon.company.CompanyManager;
import id.kyou.tycoon.company.CompanyRole;
import id.kyou.tycoon.company.EmployeeManager;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class TycoonService {
    enum State { IDLE,RUNNING,WAITING_INPUT,WAITING_OUTPUT,NO_POWER,DISABLED }
    record PlayerData(UUID uuid,String name,double balance,double revenue,double expense,long lastSeen){}
    static final class Plot {final int id,minX,maxX,minZ,maxZ;final String world;UUID owner;int level;final long createdAt;Plot(int id,String world,int minX,int maxX,int minZ,int maxZ,UUID owner,int level,long createdAt){this.id=id;this.world=world;this.minX=minX;this.maxX=maxX;this.minZ=minZ;this.maxZ=maxZ;this.owner=owner;this.level=level;this.createdAt=createdAt;}boolean contains(int x,int z){return x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ;}}
    static final class Machine {final UUID id,owner;final String world,type;final int x,y,z;int level;State state;double progress;final long createdAt;long lastProcessed;Machine(UUID id,UUID owner,String world,int x,int y,int z,String type,int level,State state,double progress,long createdAt,long lastProcessed){this.id=id;this.owner=owner;this.world=world;this.x=x;this.y=y;this.z=z;this.type=type;this.level=level;this.state=state;this.progress=progress;this.createdAt=createdAt;this.lastProcessed=lastProcessed;}}
    record MachineDef(String id,Material block,String name,long seconds,int energy,Map<Material,Integer> input,Map<Material,Integer> output,boolean enabled){}
    enum MachineUpgradeResult { UPGRADED, MAX_LEVEL, NOT_OWNER, INSUFFICIENT_FUNDS, NOT_FOUND }

    record Company(long id,String name,UUID owner,double revenue,double assets,int reputation,long createdAt){}
    static final class Warehouse {int capacity;final Map<Material,Integer> items=new ConcurrentHashMap<>();Warehouse(int c){capacity=c;}int total(){return items.values().stream().mapToInt(Integer::intValue).sum();}int amount(Material m){return items.getOrDefault(m,0);}boolean add(Material m,int n){if(n<0||total()+n>capacity)return false;items.merge(m,n,Integer::sum);return true;}boolean remove(Material m,int n){int x=amount(m);if(x<n)return false;if(x==n)items.remove(m);else items.put(m,x-n);return true;}}
    static final class Energy {int stored,capacity;Energy(int c){capacity=c;}boolean consume(int n){if(stored<n)return false;stored-=n;return true;}void add(int n){stored=Math.min(capacity,stored+n);}}

    private final KyouTycoon p; private final Database db;
    final Map<UUID,PlayerData> players=new ConcurrentHashMap<>(); final Map<Integer,Plot> plots=new ConcurrentHashMap<>(); final Map<UUID,Machine> machines=new ConcurrentHashMap<>(); final Map<UUID,Warehouse> warehouses=new ConcurrentHashMap<>(); final Map<UUID,Energy> energies=new ConcurrentHashMap<>(); final Map<String,MachineDef> defs=new ConcurrentHashMap<>(); final Map<Material,Double> shop=new ConcurrentHashMap<>();
    final Map<Material,Double> marketMult=new ConcurrentHashMap<>();
    private volatile ContractManager contractManager;
    private final Map<UUID,Double> researchBonusCache=new ConcurrentHashMap<>();
    private final Map<UUID,Double> prestigeBonusCache=new ConcurrentHashMap<>();
    private final Map<UUID,ConcurrentHashMap<Material,Long>> pendingContractOutputs=new ConcurrentHashMap<>();
    final Set<UUID> dirtyMachines=ConcurrentHashMap.newKeySet();
    final Set<UUID> dirtyWarehouses=ConcurrentHashMap.newKeySet();
    final Set<UUID> dirtyEnergies=ConcurrentHashMap.newKeySet();
    final Map<UUID,Long> offlineStarted=new ConcurrentHashMap<>();
    TycoonService(KyouTycoon p,Database db){this.p=p;this.db=db;}
    void load(){loadPlayers();loadPlots();loadMachines();machines.values().forEach(m->offlineStarted.merge(m.owner,m.lastProcessed,Math::min));loadConfigData();}
    private void loadPlayers(){try(Statement s=db.c().createStatement();ResultSet r=s.executeQuery("SELECT * FROM players")){while(r.next()){UUID u=UUID.fromString(r.getString("uuid"));players.put(u,new PlayerData(u,r.getString("name"),r.getDouble("balance"),r.getDouble("revenue"),r.getDouble("expense"),r.getLong("last_seen")));}}catch(Exception e){p.getLogger().warning("Player load: "+e.getMessage());}}
    private void loadPlots(){try(Statement s=db.c().createStatement();ResultSet r=s.executeQuery("SELECT * FROM plots ORDER BY id")){while(r.next()){String o=r.getString("owner_uuid");Plot x=new Plot(r.getInt("id"),r.getString("world"),r.getInt("min_x"),r.getInt("max_x"),r.getInt("min_z"),r.getInt("max_z"),o==null?null:UUID.fromString(o),r.getInt("level"),r.getLong("created_at"));plots.put(x.id,x);}}catch(Exception e){p.getLogger().warning("Plot load: "+e.getMessage());}}
    private void loadMachines(){try(Statement s=db.c().createStatement();ResultSet r=s.executeQuery("SELECT * FROM machines")){while(r.next()){Machine m=new Machine(UUID.fromString(r.getString("uuid")),UUID.fromString(r.getString("owner_uuid")),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("machine_type"),r.getInt("level"),State.valueOf(r.getString("state")),r.getDouble("progress"),r.getLong("created_at"),r.getLong("last_processed"));machines.put(m.id,m);}}catch(Exception e){p.getLogger().warning("Machine load: "+e.getMessage());}}
    void loadConfigData(){
        defs.clear();
        ConfigurationSection root=p.getConfigManager().machines().getConfigurationSection("machines");
        if(root!=null){
            for(String id:root.getKeys(false)){
                String path="machines."+id;
                Material material=Material.matchMaterial(p.getConfigManager().machines().getString(path+".block","STONE"));
                if(material==null){p.getLogger().warning("Ignoring machine "+id+": invalid block material.");continue;}
                defs.put(id,new MachineDef(id,material,
                    p.getConfigManager().machines().getString(path+".display_name",id),
                    Math.max(1,p.getConfigManager().machines().getLong(path+".production_time",10)),
                    Math.max(0,p.getConfigManager().machines().getInt(path+".energy_usage",0)),
                    items(p.getConfigManager().machines().getConfigurationSection(path+".input")),
                    items(p.getConfigManager().machines().getConfigurationSection(path+".output")),
                    p.getConfigManager().machines().getBoolean(path+".enabled",true)));
            }
        }
        shop.clear();
        ConfigurationSection sell=p.getConfigManager().shops().getConfigurationSection("sell");
        if(sell!=null){
            for(String key:sell.getKeys(false)){
                Material material=Material.matchMaterial(key);
                double price=sell.getDouble(key+".price",-1);
                if(material!=null&&Double.isFinite(price)&&price>=0)shop.put(material,price);
                else p.getLogger().warning("Ignoring invalid shop item: "+key);
            }
        }
        for(Material material:shop.keySet())marketMult.putIfAbsent(material,1d);
        List<ContractManager.ContractDefinition> loadedContracts=new ArrayList<>();
        ConfigurationSection contractRoot=p.getConfigManager().contracts().getConfigurationSection("contracts");
        if(contractRoot!=null&&p.getConfig().getBoolean("contracts.enabled",true)){
            for(String id:contractRoot.getKeys(false)){
                String path="contracts."+id;
                if(!p.getConfigManager().contracts().getBoolean(path+".enabled",true))continue;
                Map<Material,Integer> requirements=items(p.getConfigManager().contracts().getConfigurationSection(path+".requirement"));
                if(requirements.isEmpty()){p.getLogger().warning("Ignoring contract "+id+": no valid requirements.");continue;}
                try{
                    loadedContracts.add(new ContractManager.ContractDefinition(id,requirements,
                        Math.max(0,p.getConfigManager().contracts().getDouble(path+".reward",0)),
                        p.getConfigManager().contracts().getInt(path+".reputation",0),
                        Math.max(1,p.getConfigManager().contracts().getLong(path+".duration",p.getConfig().getLong("contracts.default_duration",3600)))));
                }catch(IllegalArgumentException ex){p.getLogger().warning("Ignoring invalid contract "+id+": "+ex.getMessage());}
            }
        }
        contractManager=new ContractManager(db.c(),loadedContracts,
            p.getConfig().getInt("reputation.minimum",0),p.getConfig().getInt("reputation.maximum",100));
        researchBonusCache.clear();
        prestigeBonusCache.clear();
    }
    private Map<Material,Integer> items(ConfigurationSection s){
        Map<Material,Integer> x=new HashMap<>();
        if(s==null)return x;
        for(String k:s.getKeys(false)){
            Material m=Material.matchMaterial(k);
            if(m==null)continue;
            Object value=s.get(k);
            int amount=value instanceof ConfigurationSection nested?nested.getInt("amount",1):s.getInt(k,1);
            if(amount>0)x.put(m,amount);
        }
        return x;
    }
    void savePlayer(UUID u){PlayerData d=players.get(u);if(d==null)return;db.executor().execute(()->{try(PreparedStatement q=db.c().prepareStatement("INSERT INTO players(uuid,name,balance,revenue,expense,last_seen) VALUES(?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,balance=excluded.balance,revenue=excluded.revenue,expense=excluded.expense,last_seen=excluded.last_seen")){q.setString(1,u.toString());q.setString(2,d.name());q.setDouble(3,d.balance());q.setDouble(4,d.revenue());q.setDouble(5,d.expense());q.setLong(6,System.currentTimeMillis()/1000);q.executeUpdate();}catch(Exception e){p.getLogger().warning("Player save: "+e.getMessage());}});}
    void saveMachine(Machine m){db.executor().execute(()->{try(PreparedStatement q=db.c().prepareStatement("INSERT INTO machines(uuid,owner_uuid,world,x,y,z,machine_type,level,state,progress,created_at,last_processed) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET state=excluded.state,progress=excluded.progress,last_processed=excluded.last_processed,level=excluded.level")){q.setString(1,m.id.toString());q.setString(2,m.owner.toString());q.setString(3,m.world);q.setInt(4,m.x);q.setInt(5,m.y);q.setInt(6,m.z);q.setString(7,m.type);q.setInt(8,m.level);q.setString(9,m.state.name());q.setDouble(10,m.progress);q.setLong(11,m.createdAt);q.setLong(12,m.lastProcessed);q.executeUpdate();}catch(Exception e){p.getLogger().warning("Machine save: "+e.getMessage());}});}
    void saveAll(){
        List<PlayerData> playerSnapshot=new ArrayList<>(players.values());
        List<Machine> machineSnapshot=machines.values().stream().map(m->new Machine(m.id,m.owner,m.world,m.x,m.y,m.z,m.type,m.level,m.state,m.progress,m.createdAt,m.lastProcessed)).toList();
        Map<UUID,Warehouse> warehouseSnapshot=new HashMap<>();
        warehouses.forEach((u,w)->{Warehouse copy=new Warehouse(w.capacity);copy.items.putAll(w.items);warehouseSnapshot.put(u,copy);});
        Map<UUID,Energy> energySnapshot=new HashMap<>();
        energies.forEach((u,e)->{Energy copy=new Energy(e.capacity);copy.stored=e.stored;energySnapshot.put(u,copy);});
        db.executor().execute(()->{
            synchronized(db){
                Connection c=db.c();
                boolean oldAuto=true;
                try{
                    oldAuto=c.getAutoCommit();
                    c.setAutoCommit(false);
                    try(PreparedStatement q=c.prepareStatement("INSERT INTO players(uuid,name,balance,revenue,expense,last_seen) VALUES(?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,balance=excluded.balance,revenue=excluded.revenue,expense=excluded.expense,last_seen=excluded.last_seen")){
                        for(PlayerData d:playerSnapshot){q.setString(1,d.uuid().toString());q.setString(2,d.name());q.setDouble(3,d.balance());q.setDouble(4,d.revenue());q.setDouble(5,d.expense());q.setLong(6,System.currentTimeMillis()/1000);q.addBatch();}q.executeBatch();
                    }
                    try(PreparedStatement q=c.prepareStatement("INSERT INTO machines(uuid,owner_uuid,world,x,y,z,machine_type,level,state,progress,created_at,last_processed) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET state=excluded.state,progress=excluded.progress,last_processed=excluded.last_processed,level=excluded.level")){
                        for(Machine m:machineSnapshot){q.setString(1,m.id.toString());q.setString(2,m.owner.toString());q.setString(3,m.world);q.setInt(4,m.x);q.setInt(5,m.y);q.setInt(6,m.z);q.setString(7,m.type);q.setInt(8,m.level);q.setString(9,m.state.name());q.setDouble(10,m.progress);q.setLong(11,m.createdAt);q.setLong(12,m.lastProcessed);q.addBatch();}q.executeBatch();
                    }
                    try(PreparedStatement q=c.prepareStatement("INSERT INTO warehouses(owner_uuid,capacity,items) VALUES(?,?,?) ON CONFLICT(owner_uuid) DO UPDATE SET capacity=excluded.capacity,items=excluded.items")){
                        for(var e:warehouseSnapshot.entrySet()){q.setString(1,e.getKey().toString());q.setInt(2,e.getValue().capacity);q.setString(3,serializeItems(e.getValue().items));q.addBatch();}q.executeBatch();
                    }
                    try(PreparedStatement q=c.prepareStatement("INSERT INTO player_energy(owner_uuid,stored,capacity) VALUES(?,?,?) ON CONFLICT(owner_uuid) DO UPDATE SET stored=excluded.stored,capacity=excluded.capacity")){
                        for(var e:energySnapshot.entrySet()){q.setString(1,e.getKey().toString());q.setInt(2,e.getValue().stored);q.setInt(3,e.getValue().capacity);q.addBatch();}q.executeBatch();
                    }
                    c.commit();
                    dirtyMachines.clear();dirtyWarehouses.clear();dirtyEnergies.clear();
                }catch(Exception e){
                    try{c.rollback();}catch(SQLException ignored){}
                    p.getLogger().warning("Batch save failed: "+e.getMessage());
                }finally{
                    try{c.setAutoCommit(oldAuto);}catch(SQLException ignored){}
                }
            }
        });
    }
    private String serializeItems(Map<Material,Integer> items){
        return items.entrySet().stream().filter(e->e.getValue()>0).sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name)))
            .map(e->e.getKey().name()+"="+e.getValue()).collect(java.util.stream.Collectors.joining(";"));
    }
    private void loadItems(Warehouse target,String encoded){
        if(encoded==null||encoded.isBlank())return;
        for(String part:encoded.split(";")){
            int cut=part.indexOf('=');if(cut<1)continue;
            Material m=Material.matchMaterial(part.substring(0,cut));if(m==null)continue;
            try{int amount=Integer.parseInt(part.substring(cut+1));if(amount>0)target.add(m,amount);}catch(NumberFormatException ignored){}
        }
    }
    void ensurePlayer(Player pl){players.computeIfAbsent(pl.getUniqueId(),u->{double b=p.getConfig().getDouble("economy.starting_balance",10000);PlayerData d=new PlayerData(u,pl.getName(),b,0,0,System.currentTimeMillis()/1000);savePlayer(u);return d;});}
    double balance(UUID u){return players.getOrDefault(u,new PlayerData(u,"",0,0,0,0)).balance();}
    void setBalance(UUID u,double b){PlayerData d=players.get(u);if(d!=null)players.put(u,new PlayerData(u,d.name(),Math.max(0,b),d.revenue(),d.expense(),d.lastSeen()));}
    boolean withdraw(UUID u,double a){PlayerData d=players.get(u);if(d==null||a<0||d.balance()<a)return false;players.put(u,new PlayerData(u,d.name(),d.balance()-a,d.revenue(),d.expense()+a,d.lastSeen()));return true;}
    void deposit(UUID u,double a){PlayerData d=players.get(u);if(d!=null){players.put(u,new PlayerData(u,d.name(),d.balance()+a,d.revenue()+a,d.expense(),d.lastSeen()));checkMoneyAchievements(u);}}
    Plot plot(UUID u){return plots.values().stream().filter(x->u.equals(x.owner)).findFirst().orElse(null);} Plot at(World w,int x,int z){return plots.values().stream().filter(q->q.world.equals(w.getName())&&q.contains(x,z)).findFirst().orElse(null);}
    Plot claim(UUID u){if(plot(u)!=null)return null;Plot x=plots.values().stream().filter(q->q.owner==null).min(Comparator.comparingInt(q->q.id)).orElse(null);if(x==null)return null;x.owner=u;savePlot(x);unlockAchievement(u,"first_plot");return x;}
    void savePlot(Plot x){db.executor().execute(()->{try(PreparedStatement q=db.c().prepareStatement("UPDATE plots SET owner_uuid=?,level=? WHERE id=?")){q.setString(1,x.owner==null?null:x.owner.toString());q.setInt(2,x.level);q.setInt(3,x.id);q.executeUpdate();}catch(Exception e){p.getLogger().warning("Plot save: "+e.getMessage());}});}
    void generatePlots(World w){
        int px=p.getConfig().getInt("world.plots_x",10),pz=p.getConfig().getInt("world.plots_z",10);
        int size=p.getConfig().getInt("world.plot_size",100),road=p.getConfig().getInt("world.road_size",7);
        List<PlotLayout.Bounds> layout=PlotLayout.create(px,pz,size,road);
        long now=System.currentTimeMillis()/1000;
        Material ground=mat(p.getConfig().getString("world.ground.material"),Material.GRASS_BLOCK);
        for(PlotLayout.Bounds b:layout){
            Plot q=plots.get(b.id());
            if(q==null){
                q=new Plot(b.id(),w.getName(),b.minX(),b.maxX(),b.minZ(),b.maxZ(),null,
                    p.getConfig().getInt("plot.starting_level",1),now);
                plots.put(q.id,q);
                insertPlot(q);
            }
            for(int x=b.minX();x<=b.maxX();x++){
                for(int z=b.minZ();z<=b.maxZ();z++){
                    w.getBlockAt(x,63,z).setType(ground,false);
                }
            }
        }
        generateRoads(w,layout,road);
    }
    void insertPlot(Plot x){db.executor().execute(()->{try(PreparedStatement q=db.c().prepareStatement("INSERT OR IGNORE INTO plots(id,world,min_x,max_x,min_z,max_z,owner_uuid,level,created_at) VALUES(?,?,?,?,?,?,?,?,?)")){q.setInt(1,x.id);q.setString(2,x.world);q.setInt(3,x.minX);q.setInt(4,x.maxX);q.setInt(5,x.minZ);q.setInt(6,x.maxZ);q.setString(7,null);q.setInt(8,x.level);q.setLong(9,x.createdAt);q.executeUpdate();}catch(Exception e){p.getLogger().warning("Plot insert: "+e.getMessage());}});}
    private void generateRoads(World w,List<PlotLayout.Bounds> layout,int road){
        if(layout.isEmpty())return;
        int minX=layout.stream().mapToInt(PlotLayout.Bounds::minX).min().orElse(0);
        int maxX=layout.stream().mapToInt(PlotLayout.Bounds::maxX).max().orElse(0);
        int minZ=layout.stream().mapToInt(PlotLayout.Bounds::minZ).min().orElse(0);
        int maxZ=layout.stream().mapToInt(PlotLayout.Bounds::maxZ).max().orElse(0);
        Material border=mat(p.getConfig().getString("world.road.border_material"),Material.STONE);
        Material roadMat=mat(p.getConfig().getString("world.road.material"),Material.STONE_BRICKS);
        Material center=mat(p.getConfig().getString("world.road.center_material"),Material.WHITE_CONCRETE);
        // Horizontal roads: exactly the configured number of blocks between plot rows.
        Set<Integer> roadRows=new HashSet<>();
        for(PlotLayout.Bounds b:layout){
            if(b.maxZ()>=maxZ||!roadRows.add(b.maxZ()))continue;
            int start=b.maxZ()+1;
            for(int z=start;z<start+road;z++){
                for(int x=minX;x<=maxX;x++){
                    int lane=z-start;
                    Material m=(lane==0||lane==road-1)?border:(lane==road/2?center:roadMat);
                    w.getBlockAt(x,63,z).setType(m,false);
                }
            }
        }
        // Vertical roads: exactly the configured number of blocks between plot columns.
        Set<Integer> roadColumns=new HashSet<>();
        for(PlotLayout.Bounds b:layout){
            if(b.maxX()>=maxX||!roadColumns.add(b.maxX()))continue;
            int start=b.maxX()+1;
            for(int x=start;x<start+road;x++){
                for(int z=minZ;z<=maxZ;z++){
                    int lane=x-start;
                    Material m=(lane==0||lane==road-1)?border:(lane==road/2?center:roadMat);
                    w.getBlockAt(x,63,z).setType(m,false);
                }
            }
        }
    }
    Material mat(String n,Material f){Material m=Material.matchMaterial(n==null?"":n);return m==null?f:m;}
    Machine createMachine(Player pl,Block b,String type){
        MachineDef d=defs.get(type);
        if(d==null||!d.enabled()||b.getType()!=d.block()||machine(b)!=null)return null;
        Plot q=at(b.getWorld(),b.getX(),b.getZ());
        if(q==null||!pl.getUniqueId().equals(q.owner))return null;
        if(machineCount(pl.getUniqueId())>=p.getConfig().getInt("plot.max_machines_by_level."+q.level,30))return null;
        Machine m=new Machine(UUID.randomUUID(),pl.getUniqueId(),b.getWorld().getName(),b.getX(),b.getY(),b.getZ(),type,1,State.IDLE,0,System.currentTimeMillis()/1000,System.currentTimeMillis()/1000);
        machines.put(m.id,m);
        persistMachineIdentity(b,m);
        saveMachine(m);
        unlockAchievement(pl.getUniqueId(),"first_machine");
        if(machineCount(pl.getUniqueId())>=10)unlockAchievement(pl.getUniqueId(),"industrialist");
        return m;
    }
    private void persistMachineIdentity(Block block,Machine m){
        if(!(block.getState() instanceof org.bukkit.block.TileState state))return;
        var pdc=state.getPersistentDataContainer();
        pdc.set(new NamespacedKey(p,"machine_id"),org.bukkit.persistence.PersistentDataType.STRING,m.type);
        pdc.set(new NamespacedKey(p,"machine_uuid"),org.bukkit.persistence.PersistentDataType.STRING,m.id.toString());
        pdc.set(new NamespacedKey(p,"owner_uuid"),org.bukkit.persistence.PersistentDataType.STRING,m.owner.toString());
        state.update(true,false);
    }
    MachineUpgradeResult upgradeMachine(UUID owner,UUID machineId){
        Machine m=machines.get(machineId);
        if(m==null)return MachineUpgradeResult.NOT_FOUND;
        if(!m.owner.equals(owner))return MachineUpgradeResult.NOT_OWNER;
        int max=Math.max(1,p.getConfigManager().machines().getInt("upgrade.max_level",5));
        int next=m.level+1;
        if(next>max)return MachineUpgradeResult.MAX_LEVEL;
        double cost=p.getConfigManager().machines().getDouble("upgrade.levels."+next+".cost",-1);
        if(!Double.isFinite(cost)||cost<0)return MachineUpgradeResult.MAX_LEVEL;
        if(!withdraw(owner,cost))return MachineUpgradeResult.INSUFFICIENT_FUNDS;
        m.level=next;
        dirtyMachines.add(m.id);
        savePlayer(owner);
        saveMachine(m);
        return MachineUpgradeResult.UPGRADED;
    }
    void removeMachine(Block block){
        Machine m=machine(block);if(m==null)return;
        machines.remove(m.id);
        if(block.getState() instanceof org.bukkit.block.TileState state){
            var pdc=state.getPersistentDataContainer();
            pdc.remove(new NamespacedKey(p,"machine_id"));
            pdc.remove(new NamespacedKey(p,"machine_uuid"));
            pdc.remove(new NamespacedKey(p,"owner_uuid"));
            state.update(true,false);
        }
        db.executor().execute(()->{synchronized(db){try(PreparedStatement q=db.c().prepareStatement("DELETE FROM machines WHERE uuid=?")){q.setString(1,m.id.toString());q.executeUpdate();}catch(Exception e){p.getLogger().warning("Machine delete: "+e.getMessage());}}});
    }
    Machine machine(Block b){for(Machine m:machines.values())if(m.world.equals(b.getWorld().getName())&&m.x==b.getX()&&m.y==b.getY()&&m.z==b.getZ())return m;return null;}
    int machineCount(UUID u){return (int)machines.values().stream().filter(m->m.owner.equals(u)).count();}
    Warehouse warehouse(UUID u){return warehouses.computeIfAbsent(u,k->{
        int capacity=p.getConfig().getInt("warehouse.starting_capacity",500);
        Warehouse w=new Warehouse(capacity);
        synchronized(db){try(PreparedStatement q=db.c().prepareStatement("SELECT capacity,items FROM warehouses WHERE owner_uuid=?")){q.setString(1,k.toString());try(ResultSet r=q.executeQuery()){if(r.next()){w.capacity=Math.max(0,r.getInt("capacity"));loadItems(w,r.getString("items"));}}}catch(Exception e){p.getLogger().warning("Warehouse load: "+e.getMessage());}}
        return w;
    });}
    Energy energy(UUID u){return energies.computeIfAbsent(u,k->{
        Energy e=new Energy(Math.max(0,p.getConfig().getInt("energy.default_capacity",100)));
        synchronized(db){try(PreparedStatement q=db.c().prepareStatement("SELECT stored,capacity FROM player_energy WHERE owner_uuid=?")){q.setString(1,k.toString());try(ResultSet r=q.executeQuery()){if(r.next()){e.capacity=Math.max(0,r.getInt("capacity"));e.stored=Math.max(0,Math.min(e.capacity,r.getInt("stored")));}}}catch(Exception ex){p.getLogger().warning("Energy load: "+ex.getMessage());}}
        return e;
    });}
    void markOffline(UUID owner){offlineStarted.putIfAbsent(owner,System.currentTimeMillis()/1000);}
    void markOnline(UUID owner){offlineStarted.remove(owner);}
    void processMachines(){
        int batch=Math.max(1,p.getConfig().getInt("performance.machine_processing_batch",100));
        long now=System.currentTimeMillis()/1000;
        boolean playerOfflineMeansPaused=p.getConfig().getBoolean("offline_machine.player_offline",false);
        long maxOffline=Math.max(0,p.getConfig().getLong("offline_machine.maximum_offline_time",21600));
        int processed=0;
        for(Machine m:machines.values()){
            if(processed++>=batch)break;
            Player online=Bukkit.getPlayer(m.owner);
            boolean isOnline=online!=null;
            MachineDef d=defs.get(m.type);
            if(d==null||!d.enabled()){
                m.state=State.DISABLED;
                continue;
            }
            if(!isOnline&&playerOfflineMeansPaused){
                m.state=State.DISABLED;
                m.lastProcessed=now;
                continue;
            }
            long offlineSince=isOnline?now:offlineStarted.computeIfAbsent(m.owner,ignored->m.lastProcessed);
            double levelMultiplier=p.getConfigManager().machines().getDouble("upgrade.levels."+m.level+".production_time_multiplier",1);
            double bonus=Math.min(0.90,researchSpeedBonus(m.owner)+prestigeSpeedBonus(m.owner));
            long cycleSeconds=Math.max(1,Math.round(d.seconds()*levelMultiplier*(1.0-bonus)));
            OfflineProductionCalculator.Result timing=OfflineProductionCalculator.calculate(
                m.progress, m.lastProcessed, now, offlineSince, isOnline, playerOfflineMeansPaused, maxOffline, cycleSeconds);
            long cycles=timing.completeCycles();
            double totalProgress=m.progress+timing.elapsedSeconds();
            if(cycles<=0){
                m.progress=totalProgress;
                m.lastProcessed=now;
                continue;
            }
            long done=0;
            boolean blocked=false;
            long cappedCycles=Math.min(cycles,Math.max(1,p.getConfig().getInt("machines.processing_batch",100)));
            for(long i=0;i<cappedCycles;i++){
                if(!(isGenerator(m.type)?produceEnergy(m):produce(m,d))){blocked=true;break;}
                done++;
            }
            if(blocked){
                // Do not bank hours of work while a machine cannot accept its next cycle.
                m.progress=cycleSeconds;
            }else{
                m.progress=Math.max(0,totalProgress-(done*cycleSeconds));
                if(cycles>cappedCycles)m.progress=Math.min(m.progress,cycleSeconds);
                m.state=done>0?State.RUNNING:m.state;
            }
            m.lastProcessed=now;
            if(done>0)dirtyMachines.add(m.id);
        }
        flushContractProgress();
    }
    private boolean isGenerator(String type){return p.getConfig().getConfigurationSection("energy.generators."+type)!=null;}
    private boolean produceEnergy(Machine m){
        ConfigurationSection generator=p.getConfig().getConfigurationSection("energy.generators."+m.type);
        if(generator==null)return false;
        Energy buffer=energy(m.owner);
        if(buffer.stored>=buffer.capacity){m.state=State.WAITING_OUTPUT;return false;}
        ConfigurationSection fuel=generator.getConfigurationSection("fuel");
        if(fuel==null||fuel.getKeys(false).isEmpty()){m.state=State.WAITING_INPUT;return false;}
        Warehouse stock=warehouse(m.owner);
        for(String key:fuel.getKeys(false)){
            Material material=Material.matchMaterial(key);
            if(material==null||stock.amount(material)<1)continue;
            Object raw=fuel.get(key);
            int energyPerItem=raw instanceof ConfigurationSection nested?nested.getInt("energy",100):fuel.getInt(key,100);
            if(energyPerItem<1)continue;
            if(!stock.remove(material,1))continue;
            buffer.add(energyPerItem);
            dirtyWarehouses.add(m.owner);
            dirtyEnergies.add(m.owner);
            m.state=State.RUNNING;
            return true;
        }
        m.state=State.WAITING_INPUT;
        return false;
    }
    boolean produce(Machine m,MachineDef d){Warehouse w=warehouse(m.owner);for(var e:d.input.entrySet())if(w.amount(e.getKey())<e.getValue()){m.state=State.WAITING_INPUT;return false;}double om=p.getConfigManager().machines().getDouble("upgrade.levels."+m.level+".output_multiplier",1);for(var e:d.output.entrySet())if(w.total()+(int)Math.ceil(e.getValue()*om)>w.capacity){m.state=State.WAITING_OUTPUT;return false;}int energy=(int)Math.ceil(d.energy*p.getConfigManager().machines().getDouble("upgrade.levels."+m.level+".energy_multiplier",1));if(p.getConfig().getBoolean("energy.enabled",true)&&!energy(m.owner).consume(energy)){m.state=State.NO_POWER;return false;}
        if(energy>0)dirtyEnergies.add(m.owner);
        for(var e:d.input.entrySet())w.remove(e.getKey(),e.getValue());
        for(var e:d.output.entrySet()){
            int produced=(int)Math.ceil(e.getValue()*om);
            w.add(e.getKey(),produced);
            recordContractOutput(m.owner,e.getKey(),produced);
        }
        dirtyWarehouses.add(m.owner);
        return true;}
    int warehouseAmount(UUID owner,Material type){return warehouse(owner).amount(type);}
    int warehouseCapacity(UUID owner){return warehouse(owner).capacity;}
    int warehouseUsed(UUID owner){return warehouse(owner).total();}
    Map<Material,Integer> warehouseItems(UUID owner){return Map.copyOf(warehouse(owner).items);}
    boolean depositInventory(Player player,Material type,int amount){
        if(type==null||amount<=0)return false;
        Warehouse w=warehouse(player.getUniqueId());
        int available=Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull).filter(i->i.getType()==type).mapToInt(ItemStack::getAmount).sum();
        if(available<amount||w.total()+amount>w.capacity)return false;
        int left=amount;
        ItemStack[] slots=player.getInventory().getStorageContents();
        for(int i=0;i<slots.length&&left>0;i++){
            ItemStack item=slots[i];
            if(item==null||item.getType()!=type)continue;
            int take=Math.min(left,item.getAmount());
            if(take==item.getAmount())slots[i]=null;else item.setAmount(item.getAmount()-take);
            left-=take;
        }
        player.getInventory().setStorageContents(slots);
        w.add(type,amount);dirtyWarehouses.add(player.getUniqueId());
        return true;
    }
    boolean withdrawToInventory(Player player,Material type,int amount){
        if(type==null||amount<=0)return false;
        Warehouse w=warehouse(player.getUniqueId());
        if(w.amount(type)<amount)return false;
        ItemStack template=new ItemStack(type);
        int space=0;
        for(ItemStack item:player.getInventory().getStorageContents()){
            if(item==null)space+=template.getMaxStackSize();
            else if(item.isSimilar(template))space+=Math.max(0,item.getMaxStackSize()-item.getAmount());
        }
        if(space<amount)return false;
        if(!w.remove(type,amount))return false;
        Map<Integer,ItemStack> leftover=player.getInventory().addItem(new ItemStack(type,amount));
        if(!leftover.isEmpty()){
            for(ItemStack item:leftover.values())w.add(type,item.getAmount());
            dirtyWarehouses.add(player.getUniqueId());
            return false;
        }
        dirtyWarehouses.add(player.getUniqueId());
        return true;
    }
    boolean sellFromWarehouse(UUID owner,Material type,int amount){
        if(type==null||amount<=0||!shop.containsKey(type))return false;
        Warehouse w=warehouse(owner);
        if(!w.remove(type,amount))return false;
        double total=marketPrice(type)*amount;
        deposit(owner,total);dirtyWarehouses.add(owner);recordTransaction(owner,"SELL",total,amount+" "+type.name());
        double current=marketMult.getOrDefault(type,1d);
        double min=p.getConfig().getDouble("market.minimum_price_multiplier",0.5);
        marketMult.put(type,Math.max(min,current*Math.max(0.80,1.0-amount/10000.0)));
        return true;
    }
    private void recordTransaction(UUID owner,String type,double amount,String note){
        db.executor().execute(()->{synchronized(db){try(PreparedStatement q=db.c().prepareStatement("INSERT INTO transactions(player_uuid,type,amount,note,created_at) VALUES(?,?,?,?,?)")){q.setString(1,owner.toString());q.setString(2,type);q.setDouble(3,amount);q.setString(4,note);q.setLong(5,System.currentTimeMillis()/1000);q.executeUpdate();}catch(Exception e){p.getLogger().warning("Transaction save: "+e.getMessage());}}});
    }

    private void refreshPlayerFromDatabase(UUID uuid) throws SQLException {
        try(PreparedStatement query=db.c().prepareStatement("SELECT name,balance,revenue,expense,last_seen FROM players WHERE uuid=?")){
            query.setString(1,uuid.toString());
            try(ResultSet rows=query.executeQuery()){
                if(rows.next())players.put(uuid,new PlayerData(uuid,rows.getString("name"),rows.getDouble("balance"),
                    rows.getDouble("revenue"),rows.getDouble("expense"),rows.getLong("last_seen")));
            }
        }
    }

    ResearchManager.UnlockResult unlockResearch(UUID player,String researchId){
        synchronized(db){
            try{
                ResearchManager manager=new ResearchManager(db.c(),p.getConfigManager().research());
                ResearchManager.UnlockResult result=manager.unlock(player,researchId,System.currentTimeMillis()/1000);
                if(result==ResearchManager.UnlockResult.UNLOCKED){
                    researchBonusCache.remove(player);
                    refreshPlayerFromDatabase(player);
                }
                return result;
            }catch(Exception e){p.getLogger().warning("Research unlock failed: "+e.getMessage());return ResearchManager.UnlockResult.INVALID;}
        }
    }

    List<String> researchIds(){
        ConfigurationSection section=p.getConfigManager().research().getConfigurationSection("research");
        if(section==null||!p.getConfig().getBoolean("research.enabled",true))return List.of();
        return section.getKeys(false).stream().filter(id->p.getConfigManager().research().getBoolean("research."+id+".enabled",true)).sorted().toList();
    }

    Set<String> researchUnlocked(UUID player){
        synchronized(db){
            try{return Set.copyOf(new ResearchManager(db.c(),p.getConfigManager().research()).unlocked(player));}
            catch(SQLException e){p.getLogger().warning("Research list failed: "+e.getMessage());return Set.of();}
        }
    }

    private double researchSpeedBonus(UUID player){
        return researchBonusCache.computeIfAbsent(player,id->{
            synchronized(db){
                try{return new ResearchManager(db.c(),p.getConfigManager().research()).productionSpeedBonus(id);}
                catch(SQLException e){p.getLogger().warning("Research bonus lookup failed: "+e.getMessage());return 0d;}
            }
        });
    }

    private double prestigeSpeedBonus(UUID player){
        return prestigeBonusCache.computeIfAbsent(player,id->{
            synchronized(db){
                try{return new PrestigeManager(db.c(),p.getConfig()).productionBonus(id);}
                catch(SQLException e){p.getLogger().warning("Prestige bonus lookup failed: "+e.getMessage());return 0d;}
            }
        });
    }

    boolean unlockAchievement(UUID player,String id){
        boolean unlocked;
        synchronized(db){
            try{unlocked=new AchievementManager(db.c(),p.getConfig()).unlock(player,id,System.currentTimeMillis()/1000);}
            catch(SQLException e){p.getLogger().warning("Achievement save failed: "+e.getMessage());return false;}
        }
        if(unlocked&&p.getConfig().getBoolean("notifications.achievement_unlocked",true)){
            Player online=Bukkit.getPlayer(player);
            if(online!=null){
                String name=p.getConfig().getString("achievements.items."+id+".name",id);
                online.sendMessage(p.msg("achievement_unlocked",Map.of("name",name)));
            }
        }
        return unlocked;
    }

    private void checkMoneyAchievements(UUID player){
        double value=balance(player);
        if(value>=1_000_000)unlockAchievement(player,"millionaire");
        if(value>=10_000_000)unlockAchievement(player,"tycoon");
    }

    private void recordContractOutput(UUID owner,Material material,int amount){
        if(amount<1||contractManager==null||!p.getConfig().getBoolean("contracts.enabled",true))return;
        pendingContractOutputs.computeIfAbsent(owner,ignored->new ConcurrentHashMap<>())
            .merge(material,(long)amount,(left,right)->left>Long.MAX_VALUE-right?Long.MAX_VALUE:left+right);
    }

    private void flushContractProgress(){
        ContractManager manager=contractManager;
        if(manager==null||pendingContractOutputs.isEmpty())return;
        Map<UUID,Map<Material,Long>> batch=new HashMap<>();
        pendingContractOutputs.forEach((owner,pending)->{
            if(pendingContractOutputs.remove(owner,pending)&&!pending.isEmpty())batch.put(owner,Map.copyOf(pending));
        });
        if(batch.isEmpty())return;
        long now=System.currentTimeMillis()/1000;
        db.executor().execute(()->{
            synchronized(db){
                for(var playerBatch:batch.entrySet()){
                    for(var output:playerBatch.getValue().entrySet()){
                        int amount=(int)Math.min(Integer.MAX_VALUE,output.getValue());
                        try{manager.recordProduction(playerBatch.getKey(),output.getKey(),amount,now);}
                        catch(SQLException e){p.getLogger().warning("Contract progress save failed: "+e.getMessage());}
                    }
                }
            }
        });
    }

    List<ContractManager.ContractDefinition> contractDefinitions(){
        ContractManager manager=contractManager;
        return manager==null?List.of():manager.definitions();
    }

    boolean acceptContract(UUID player,String contractId){
        ContractManager manager=contractManager;
        if(manager==null)return false;
        synchronized(db){
            try{return manager.accept(player,contractId,System.currentTimeMillis()/1000);}
            catch(SQLException e){p.getLogger().warning("Contract accept failed: "+e.getMessage());return false;}
        }
    }

    List<ContractManager.ContractStatus> activeContracts(UUID player){
        ContractManager manager=contractManager;
        if(manager==null)return List.of();
        synchronized(db){
            try{return manager.active(player,System.currentTimeMillis()/1000);}
            catch(SQLException e){p.getLogger().warning("Contract list failed: "+e.getMessage());return List.of();}
        }
    }

    Optional<ContractManager.Completion> completeContract(UUID player,String contractId){
        ContractManager manager=contractManager;
        if(manager==null)return Optional.empty();
        synchronized(db){
            try{
                Optional<ContractManager.Completion> result=manager.complete(player,contractId,System.currentTimeMillis()/1000);
                if(result.isPresent()&&result.get().success())refreshPlayerFromDatabase(player);
                return result;
            }catch(SQLException e){p.getLogger().warning("Contract completion failed: "+e.getMessage());return Optional.empty();}
        }
    }

    void expireContracts(){
        ContractManager manager=contractManager;
        if(manager==null)return;
        synchronized(db){
            try{manager.expireDue(System.currentTimeMillis()/1000);}
            catch(SQLException e){p.getLogger().warning("Contract expiry failed: "+e.getMessage());}
        }
    }

    double companyValue(UUID player){
        synchronized(db){
            double value=balance(player);
            Warehouse warehouse=warehouses.get(player);
            if(warehouse!=null)for(var item:warehouse.items.entrySet())value+=item.getValue()*marketPrice(item.getKey());
            for(Machine machine:machines.values())if(machine.owner.equals(player)){
                value+=100_000.0*machine.level;
                value+=Math.max(0,p.getConfigManager().machines().getDouble("upgrade.levels."+machine.level+".cost",0))*0.5;
            }
            Company company=company(player);
            if(company!=null)value+=Math.max(0,company.assets)+Math.max(0,company.revenue);
            return Double.isFinite(value)?Math.max(0,value):Double.MAX_VALUE;
        }
    }

    String playerName(UUID player){
        Player online=Bukkit.getPlayer(player);
        if(online!=null)return online.getName();
        synchronized(db){
            try(PreparedStatement query=db.c().prepareStatement("SELECT name FROM players WHERE uuid=?")){
                query.setString(1,player.toString());
                try(ResultSet rows=query.executeQuery()){return rows.next()?rows.getString(1):player.toString();}
            }catch(SQLException e){return player.toString();}
        }
    }

    private UUID findPlayerUuid(String name){
        if(name==null||name.isBlank())return null;
        Player online=Bukkit.getPlayerExact(name);
        if(online!=null){ensurePlayer(online);return online.getUniqueId();}
        synchronized(db){
            try(PreparedStatement query=db.c().prepareStatement("SELECT uuid FROM players WHERE name=? COLLATE NOCASE ORDER BY last_seen DESC LIMIT 1")){
                query.setString(1,name);
                try(ResultSet rows=query.executeQuery()){return rows.next()?UUID.fromString(rows.getString(1)):null;}
            }catch(SQLException e){p.getLogger().warning("Player lookup failed: "+e.getMessage());return null;}
        }
    }

    boolean inviteCompanyMember(UUID actor,String targetName){
        Company company=company(actor);
        UUID target=findPlayerUuid(targetName);
        if(company==null||target==null||actor.equals(target))return false;
        synchronized(db){
            try{return new CompanyManager(db.c(),Math.max(1,p.getConfig().getInt("company.max_members",20)))
                .addMember(company.id(),actor,target,CompanyRole.WORKER,System.currentTimeMillis()/1000);}
            catch(SQLException|IllegalArgumentException e){p.getLogger().warning("Company invite failed: "+e.getMessage());return false;}
        }
    }

    boolean removeCompanyMember(UUID actor,String targetName){
        Company company=company(actor);
        UUID target=findPlayerUuid(targetName);
        if(company==null||target==null)return false;
        synchronized(db){
            try{return new CompanyManager(db.c(),Math.max(1,p.getConfig().getInt("company.max_members",20))).removeMember(company.id(),actor,target);}
            catch(SQLException e){p.getLogger().warning("Company removal failed: "+e.getMessage());return false;}
        }
    }

    boolean leaveCompany(UUID player){
        synchronized(db){
            try{return new CompanyManager(db.c(),Math.max(1,p.getConfig().getInt("company.max_members",20))).leave(player);}
            catch(SQLException e){p.getLogger().warning("Company leave failed: "+e.getMessage());return false;}
        }
    }

    List<CompanyManager.Member> companyMembers(UUID player){
        Company company=company(player);
        if(company==null)return List.of();
        synchronized(db){
            try{return new CompanyManager(db.c(),Math.max(1,p.getConfig().getInt("company.max_members",20))).members(company.id());}
            catch(SQLException e){p.getLogger().warning("Company members lookup failed: "+e.getMessage());return List.of();}
        }
    }

    id.kyou.tycoon.company.Employee hireEmployee(UUID player,String type){
        Company company=company(player);
        if(company==null||type==null)return null;
        String key=type.trim().toLowerCase(Locale.ROOT);
        double salary=p.getConfig().getDouble("employees."+key+".salary",-1);
        if(!p.getConfig().getBoolean("employees.enabled",true)||salary<0||!Double.isFinite(salary))return null;
        synchronized(db){
            try{return new EmployeeManager(db.c()).hire(company.id(),player,key,salary);}
            catch(SQLException|IllegalArgumentException e){p.getLogger().warning("Employee hire failed: "+e.getMessage());return null;}
        }
    }

    List<id.kyou.tycoon.company.Employee> employees(UUID player){
        Company company=company(player);
        if(company==null)return List.of();
        synchronized(db){
            try{return new EmployeeManager(db.c()).list(company.id());}
            catch(SQLException e){p.getLogger().warning("Employee list failed: "+e.getMessage());return List.of();}
        }
    }

    void payEmployeePayroll(){
        if(!p.getConfig().getBoolean("employees.enabled",true))return;
        synchronized(db){
            List<Long> companies=new ArrayList<>();
            Map<Long,UUID> owners=new HashMap<>();
            try(Statement query=db.c().createStatement();ResultSet rows=query.executeQuery(
                    "SELECT DISTINCT c.id,c.owner_uuid FROM companies c JOIN employees e ON e.company_id=c.id WHERE e.active=1")){
                while(rows.next()){long id=rows.getLong(1);companies.add(id);owners.put(id,UUID.fromString(rows.getString(2)));}
            }catch(SQLException e){p.getLogger().warning("Payroll company lookup failed: "+e.getMessage());return;}
            EmployeeManager manager=new EmployeeManager(db.c());
            for(long companyId:companies){
                try{
                    EmployeeManager.PayrollResult result=manager.payPayroll(companyId,System.currentTimeMillis()/1000);
                    if(result.paid())refreshPlayerFromDatabase(owners.get(companyId));
                }catch(SQLException e){p.getLogger().warning("Payroll failed for company "+companyId+": "+e.getMessage());}
            }
        }
    }

    double marketPrice(Material m){return shop.getOrDefault(m,0d)*marketMult.getOrDefault(m,1d);}
    void marketTick(){if(!p.getConfig().getBoolean("market.dynamic_price",true))return;double min=p.getConfig().getDouble("market.minimum_price_multiplier",.5),max=p.getConfig().getDouble("market.maximum_price_multiplier",2);for(Material m:marketMult.keySet())marketMult.put(m,Math.max(min,Math.min(max,marketMult.get(m)+(Math.random()-.5)*.05)));}
    Company company(UUID u){try(PreparedStatement q=db.c().prepareStatement("SELECT c.* FROM companies c JOIN company_members m ON c.id=m.company_id WHERE m.player_uuid=?")){q.setString(1,u.toString());try(ResultSet r=q.executeQuery()){if(!r.next())return null;return new Company(r.getLong("id"),r.getString("name"),UUID.fromString(r.getString("owner_uuid")),r.getDouble("revenue"),r.getDouble("assets"),r.getInt("reputation"),r.getLong("created_at"));}}catch(Exception e){return null;}}
    Company createCompany(UUID u,String name){try{if(company(u)!=null)return null;try(PreparedStatement q=db.c().prepareStatement("INSERT INTO companies(name,owner_uuid,created_at) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)){q.setString(1,name);q.setString(2,u.toString());q.setLong(3,System.currentTimeMillis()/1000);q.executeUpdate();try(ResultSet r=q.getGeneratedKeys()){long id=r.next()?r.getLong(1):-1;try(PreparedStatement m=db.c().prepareStatement("INSERT INTO company_members(company_id,player_uuid,role,joined_at) VALUES(?,?,?,?)")){m.setLong(1,id);m.setString(2,u.toString());m.setString(3,"CEO");m.setLong(4,System.currentTimeMillis()/1000);m.executeUpdate();}return new Company(id,name,u,0,0,50,System.currentTimeMillis()/1000);}}}catch(Exception e){p.getLogger().warning("Company create: "+e.getMessage());return null;}}
    int prestige(UUID u){try(PreparedStatement q=db.c().prepareStatement("SELECT level FROM prestige WHERE player_uuid=?")){q.setString(1,u.toString());try(ResultSet r=q.executeQuery()){return r.next()?r.getInt(1):0;}}catch(Exception e){return 0;}}
    boolean prestige(UUID u,double value){
        synchronized(db){
            try{
                PrestigeManager manager=new PrestigeManager(db.c(),p.getConfig());
                PrestigeManager.PrestigeResult result=manager.prestige(u,value,System.currentTimeMillis()/1000);
                if(!result.success())return false;
                if(p.getConfig().getBoolean("prestige.reset.machines",true))machines.values().removeIf(m->m.owner.equals(u));
                if(p.getConfig().getBoolean("prestige.reset.warehouse",true)){warehouses.remove(u);dirtyWarehouses.remove(u);}
                if(p.getConfig().getBoolean("prestige.reset.research",true))researchBonusCache.remove(u);
                prestigeBonusCache.remove(u);
                if(p.getConfig().getBoolean("prestige.reset.machines",true))dirtyMachines.removeIf(id->!machines.containsKey(id));
                refreshPlayerFromDatabase(u);
                return true;
            }catch(Exception e){p.getLogger().warning("Prestige failed: "+e.getMessage());return false;}
        }
    }
}
