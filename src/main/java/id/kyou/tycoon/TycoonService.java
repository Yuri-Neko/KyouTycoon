package id.kyou.tycoon;

import org.bukkit.*;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class TycoonService {
    enum State { IDLE,RUNNING,WAITING_INPUT,WAITING_OUTPUT,NO_POWER,DISABLED }
    record PlayerData(UUID uuid,String name,double balance,double revenue,double expense,long lastSeen){}
    static final class Plot {final int id,minX,maxX,minZ,maxZ;final String world;UUID owner;int level;final long createdAt;Plot(int id,String world,int minX,int maxX,int minZ,int maxZ,UUID owner,int level,long createdAt){this.id=id;this.world=world;this.minX=minX;this.maxX=maxX;this.minZ=minZ;this.maxZ=maxZ;this.owner=owner;this.level=level;this.createdAt=createdAt;}boolean contains(int x,int z){return x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ;}}
    static final class Machine {final UUID id,owner;final String world,type;final int x,y,z;int level;State state;double progress;final long createdAt;long lastProcessed;Machine(UUID id,UUID owner,String world,int x,int y,int z,String type,int level,State state,double progress,long createdAt,long lastProcessed){this.id=id;this.owner=owner;this.world=world;this.x=x;this.y=y;this.z=z;this.type=type;this.level=level;this.state=state;this.progress=progress;this.createdAt=createdAt;this.lastProcessed=lastProcessed;}}
    record MachineDef(String id,Material block,String name,long seconds,int energy,Map<Material,Integer> input,Map<Material,Integer> output,boolean enabled){}
    record Company(long id,String name,UUID owner,double revenue,double assets,int reputation,long createdAt){}
    static final class Warehouse {int capacity;final Map<Material,Integer> items=new ConcurrentHashMap<>();Warehouse(int c){capacity=c;}int total(){return items.values().stream().mapToInt(Integer::intValue).sum();}int amount(Material m){return items.getOrDefault(m,0);}boolean add(Material m,int n){if(n<0||total()+n>capacity)return false;items.merge(m,n,Integer::sum);return true;}boolean remove(Material m,int n){int x=amount(m);if(x<n)return false;if(x==n)items.remove(m);else items.put(m,x-n);return true;}}
    static final class Energy {int stored,capacity;Energy(int c){capacity=c;}boolean consume(int n){if(stored<n)return false;stored-=n;return true;}void add(int n){stored=Math.min(capacity,stored+n);}}

    private final KyouTycoon p; private final Database db;
    final Map<UUID,PlayerData> players=new ConcurrentHashMap<>(); final Map<Integer,Plot> plots=new ConcurrentHashMap<>(); final Map<UUID,Machine> machines=new ConcurrentHashMap<>(); final Map<UUID,Warehouse> warehouses=new ConcurrentHashMap<>(); final Map<UUID,Energy> energies=new ConcurrentHashMap<>(); final Map<String,MachineDef> defs=new ConcurrentHashMap<>(); final Map<Material,Double> shop=new ConcurrentHashMap<>();
    final Map<Material,Double> marketMult=new ConcurrentHashMap<>();
    final Set<UUID> dirtyMachines=ConcurrentHashMap.newKeySet();
    final Set<UUID> dirtyWarehouses=ConcurrentHashMap.newKeySet();
    final Set<UUID> dirtyEnergies=ConcurrentHashMap.newKeySet();
    final Map<UUID,Long> offlineStarted=new ConcurrentHashMap<>();
    TycoonService(KyouTycoon p,Database db){this.p=p;this.db=db;}
    void load(){loadPlayers();loadPlots();loadMachines();loadConfigData();}
    private void loadPlayers(){try(Statement s=db.c().createStatement();ResultSet r=s.executeQuery("SELECT * FROM players")){while(r.next()){UUID u=UUID.fromString(r.getString("uuid"));players.put(u,new PlayerData(u,r.getString("name"),r.getDouble("balance"),r.getDouble("revenue"),r.getDouble("expense"),r.getLong("last_seen")));}}catch(Exception e){p.getLogger().warning("Player load: "+e.getMessage());}}
    private void loadPlots(){try(Statement s=db.c().createStatement();ResultSet r=s.executeQuery("SELECT * FROM plots ORDER BY id")){while(r.next()){String o=r.getString("owner_uuid");Plot x=new Plot(r.getInt("id"),r.getString("world"),r.getInt("min_x"),r.getInt("max_x"),r.getInt("min_z"),r.getInt("max_z"),o==null?null:UUID.fromString(o),r.getInt("level"),r.getLong("created_at"));plots.put(x.id,x);}}catch(Exception e){p.getLogger().warning("Plot load: "+e.getMessage());}}
    private void loadMachines(){try(Statement s=db.c().createStatement();ResultSet r=s.executeQuery("SELECT * FROM machines")){while(r.next()){Machine m=new Machine(UUID.fromString(r.getString("uuid")),UUID.fromString(r.getString("owner_uuid")),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("machine_type"),r.getInt("level"),State.valueOf(r.getString("state")),r.getDouble("progress"),r.getLong("created_at"),r.getLong("last_processed"));machines.put(m.id,m);}}catch(Exception e){p.getLogger().warning("Machine load: "+e.getMessage());}}
    void loadConfigData(){defs.clear();ConfigurationSection s=p.getConfigManager().machines().getConfigurationSection("machines");if(s!=null)for(String id:s.getKeys(false)){String b="machines."+id;Material mat=Material.matchMaterial(p.getConfigManager().machines().getString(b+".block","STONE"));if(mat==null)continue;defs.put(id,new MachineDef(id,mat,p.getConfigManager().machines().getString(b+".display_name",id),Math.max(1,p.getConfigManager().machines().getLong(b+".production_time",10)),Math.max(0,p.getConfigManager().machines().getInt(b+".energy_usage",0)),items(p.getConfigManager().machines().getConfigurationSection(b+".input")),items(p.getConfigManager().machines().getConfigurationSection(b+".output")),p.getConfigManager().machines().getBoolean(b+".enabled",true)));}shop.clear();ConfigurationSection sh=p.getConfigManager().shops().getConfigurationSection("sell");if(sh!=null)for(String k:sh.getKeys(false)){Material m=Material.matchMaterial(k);if(m!=null)shop.put(m,p.getConfigManager().shops().getDouble(k+".price"));}for(Material m:shop.keySet())marketMult.putIfAbsent(m,1d);}
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
    void deposit(UUID u,double a){PlayerData d=players.get(u);if(d!=null)players.put(u,new PlayerData(u,d.name(),d.balance()+a,d.revenue()+a,d.expense(),d.lastSeen()));}
    Plot plot(UUID u){return plots.values().stream().filter(x->u.equals(x.owner)).findFirst().orElse(null);} Plot at(World w,int x,int z){return plots.values().stream().filter(q->q.world.equals(w.getName())&&q.contains(x,z)).findFirst().orElse(null);}
    Plot claim(UUID u){if(plot(u)!=null)return null;Plot x=plots.values().stream().filter(q->q.owner==null).min(Comparator.comparingInt(q->q.id)).orElse(null);if(x==null)return null;x.owner=u;savePlot(x);return x;}
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
            long cycleSeconds=Math.max(1,Math.round(d.seconds()*p.getConfigManager().machines().getDouble("upgrade.levels."+m.level+".production_time_multiplier",1)));
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
                if(!produce(m,d)){blocked=true;break;}
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
    }
    boolean produce(Machine m,MachineDef d){Warehouse w=warehouse(m.owner);for(var e:d.input.entrySet())if(w.amount(e.getKey())<e.getValue()){m.state=State.WAITING_INPUT;return false;}double om=p.getConfigManager().machines().getDouble("upgrade.levels."+m.level+".output_multiplier",1);for(var e:d.output.entrySet())if(w.total()+(int)Math.ceil(e.getValue()*om)>w.capacity){m.state=State.WAITING_OUTPUT;return false;}int energy=(int)Math.ceil(d.energy*p.getConfigManager().machines().getDouble("upgrade.levels."+m.level+".energy_multiplier",1));if(p.getConfig().getBoolean("energy.enabled",true)&&!energy(m.owner).consume(energy)){m.state=State.NO_POWER;return false;}
        if(energy>0)dirtyEnergies.add(m.owner);
        for(var e:d.input.entrySet())w.remove(e.getKey(),e.getValue());
        for(var e:d.output.entrySet())w.add(e.getKey(),(int)Math.ceil(e.getValue()*om));
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

    double marketPrice(Material m){return shop.getOrDefault(m,0d)*marketMult.getOrDefault(m,1d);}
    void marketTick(){if(!p.getConfig().getBoolean("market.dynamic_price",true))return;double min=p.getConfig().getDouble("market.minimum_price_multiplier",.5),max=p.getConfig().getDouble("market.maximum_price_multiplier",2);for(Material m:marketMult.keySet())marketMult.put(m,Math.max(min,Math.min(max,marketMult.get(m)+(Math.random()-.5)*.05)));}
    Company company(UUID u){try(PreparedStatement q=db.c().prepareStatement("SELECT c.* FROM companies c JOIN company_members m ON c.id=m.company_id WHERE m.player_uuid=?")){q.setString(1,u.toString());try(ResultSet r=q.executeQuery()){if(!r.next())return null;return new Company(r.getLong("id"),r.getString("name"),UUID.fromString(r.getString("owner_uuid")),r.getDouble("revenue"),r.getDouble("assets"),r.getInt("reputation"),r.getLong("created_at"));}}catch(Exception e){return null;}}
    Company createCompany(UUID u,String name){try{if(company(u)!=null)return null;try(PreparedStatement q=db.c().prepareStatement("INSERT INTO companies(name,owner_uuid,created_at) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)){q.setString(1,name);q.setString(2,u.toString());q.setLong(3,System.currentTimeMillis()/1000);q.executeUpdate();try(ResultSet r=q.getGeneratedKeys()){long id=r.next()?r.getLong(1):-1;try(PreparedStatement m=db.c().prepareStatement("INSERT INTO company_members(company_id,player_uuid,role,joined_at) VALUES(?,?,?,?)")){m.setLong(1,id);m.setString(2,u.toString());m.setString(3,"CEO");m.setLong(4,System.currentTimeMillis()/1000);m.executeUpdate();}return new Company(id,name,u,0,0,50,System.currentTimeMillis()/1000);}}}catch(Exception e){p.getLogger().warning("Company create: "+e.getMessage());return null;}}
    int prestige(UUID u){try(PreparedStatement q=db.c().prepareStatement("SELECT level FROM prestige WHERE player_uuid=?")){q.setString(1,u.toString());try(ResultSet r=q.executeQuery()){return r.next()?r.getInt(1):0;}}catch(Exception e){return 0;}}
    boolean prestige(UUID u,double value){if(!p.getConfig().getBoolean("prestige.enabled",true)||value<p.getConfig().getDouble("prestige.minimum_company_value"))return false;int n=prestige(u)+1;try(PreparedStatement q=db.c().prepareStatement("INSERT INTO prestige(player_uuid,level) VALUES(?,?) ON CONFLICT(player_uuid) DO UPDATE SET level=excluded.level")){q.setString(1,u.toString());q.setInt(2,n);q.executeUpdate();if(p.getConfig().getBoolean("prestige.reset.money",true))setBalance(u,p.getConfig().getDouble("economy.starting_balance"));return true;}catch(Exception e){return false;}}
}
