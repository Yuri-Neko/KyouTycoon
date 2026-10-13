package id.kyou.tycoon;

import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import java.util.Random;

final class TycoonWorld {
    private final KyouTycoon p;
    TycoonWorld(KyouTycoon p){this.p=p;}
    World getOrCreate(){String n=p.getConfig().getString("world.name","tycoon_world");World w=Bukkit.getWorld(n);return w!=null?w:create();}
    World create(){String n=p.getConfig().getString("world.name","tycoon_world");World old=Bukkit.getWorld(n);if(old!=null)return old;WorldCreator c=new WorldCreator(n);c.generateStructures(false);c.generator(new FlatGenerator());World w=c.createWorld();if(w==null)throw new IllegalStateException("Failed to create "+n);w.setDifficulty(Difficulty.PEACEFUL);w.setGameRule(GameRule.DO_MOB_SPAWNING,false);w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);w.setGameRule(GameRule.DO_WEATHER_CYCLE,false);w.setTime(6000);p.service().generatePlots(w);int configuredHubSize=Math.max(1,p.getConfig().getInt("world.hub.size",7));int roadSize=Math.max(1,p.getConfig().getInt("world.road_size",7));int s=Math.min((configuredHubSize-1)/2,(roadSize-1)/2);Material m=p.service().mat(p.getConfig().getString("world.hub.material"),Material.STONE_BRICKS);for(int x=-s;x<=s;x++)for(int z=-s;z<=s;z++)w.getBlockAt(x,64,z).setType(m,false);w.setSpawnLocation(0,65,0);return w;}
    static final class FlatGenerator extends ChunkGenerator { @Override public void generateNoise(WorldInfo i,Random r,int cx,int cz,ChunkGenerator.ChunkData d){for(int x=0;x<16;x++)for(int z=0;z<16;z++){d.setBlock(x,0,z,Material.BEDROCK);for(int y=1;y<63;y++)d.setBlock(x,y,z,Material.DIRT);d.setBlock(x,63,z,Material.GRASS_BLOCK);}} }
}
