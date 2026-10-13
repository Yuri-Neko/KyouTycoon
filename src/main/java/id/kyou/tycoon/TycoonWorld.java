package id.kyou.tycoon;

import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import java.util.Random;
import java.io.File;

final class TycoonWorld {
    private final KyouTycoon p;
    TycoonWorld(KyouTycoon p){this.p=p;}
    World getOrCreate(){
        String name=p.getConfig().getString("world.name","tycoon_world");
        World loaded=Bukkit.getWorld(name);
        return loaded!=null?loaded:create();
    }
    boolean exists(){
        String name=p.getConfig().getString("world.name","tycoon_world");
        return Bukkit.getWorld(name)!=null||worldFolder(name).isDirectory();
    }
    private File worldFolder(String name){return new File(Bukkit.getWorldContainer(),name);}
    World create(){
        String name=p.getConfig().getString("world.name","tycoon_world");
        World loaded=Bukkit.getWorld(name);
        if(loaded!=null)return loaded;
        boolean worldExisted=worldFolder(name).isDirectory();
        WorldCreator creator=new WorldCreator(name);
        creator.generateStructures(false);
        creator.generator(new FlatGenerator());
        World world=creator.createWorld();
        if(world==null)throw new IllegalStateException("Failed to load or create Tycoon world: "+name);
        // Existing world folders are only loaded and reconciled: never repaint their ground or hub.
        p.service().generatePlots(world,!worldExisted);
        if(!worldExisted){
            world.setDifficulty(Difficulty.PEACEFUL);
            world.setGameRule(GameRule.DO_MOB_SPAWNING,false);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);
            world.setGameRule(GameRule.DO_WEATHER_CYCLE,false);
            world.setTime(6000);
            int configuredHubSize=Math.max(1,p.getConfig().getInt("world.hub.size",7));
            int roadSize=Math.max(1,p.getConfig().getInt("world.road_size",7));
            int radius=Math.min((configuredHubSize-1)/2,(roadSize-1)/2);
            Material hub=p.service().mat(p.getConfig().getString("world.hub.material"),Material.STONE_BRICKS);
            for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)world.getBlockAt(x,64,z).setType(hub,false);
            world.setSpawnLocation(0,65,0);
        }
        return world;
    }
    static final class FlatGenerator extends ChunkGenerator { @Override public void generateNoise(WorldInfo i,Random r,int cx,int cz,ChunkGenerator.ChunkData d){for(int x=0;x<16;x++)for(int z=0;z<16;z++){d.setBlock(x,0,z,Material.BEDROCK);for(int y=1;y<63;y++)d.setBlock(x,y,z,Material.DIRT);d.setBlock(x,63,z,Material.GRASS_BLOCK);}} }
}
