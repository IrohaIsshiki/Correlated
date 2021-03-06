package com.elytradev.correlated.world;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.elytradev.correlated.CLog;
import com.elytradev.correlated.init.CConfig;
import com.elytradev.correlated.init.CDimensions;
import com.elytradev.correlated.math.Vec2i;
import com.elytradev.correlated.network.fx.SetGlitchStateMessage;
import com.elytradev.correlated.network.fx.SetGlitchStateMessage.GlitchState;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Biomes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class LimboProvider extends WorldProvider {

	private static final DamageSource constraint_violation = new DamageSource("correlated.constraint_violation")
			.setDamageAllowedInCreativeMode().setDamageBypassesArmor().setDamageIsAbsolute();
	
	private DungeonGrid grid;
	private DungeonScribe scribe;
	private final Map<EntityPlayerMP, Vec2i> constraints = new WeakHashMap<>();
	private final Map<UUID, DungeonPlayer> entering = Maps.newHashMap();
	private final Set<UUID> leaving = Sets.newHashSet();
	private LimboTeleporter teleporter;
	
	@Override
	public DimensionType getDimensionType() {
		return CDimensions.LIMBO;
	}

	@Override
	protected void init() {
		this.biomeProvider = new BiomeProviderSingle(Biomes.VOID);
		this.hasSkyLight = false;
		grid = new DungeonGrid();
		grid.deserializeNBT(world.getWorldInfo().getDimensionData(getDimensionType().getId()));
		scribe = new DungeonScribe(world);
		if (world instanceof WorldServer) {
			teleporter = new LimboTeleporter((WorldServer)world, scribe, grid);
		}
	}

	@Override
	public IChunkGenerator createChunkGenerator() {
		return new LimboChunkGenerator(this.world, this.world.getWorldInfo().isMapFeaturesEnabled(), this.world.getSeed(), grid);
	}

	@Override
	public float calculateCelestialAngle(long worldTime, float partialTicks) {
		return 0.0F;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public float[] calcSunriseSunsetColors(float celestialAngle, float partialTicks) {
		return null;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public Vec3d getFogColor(float p_76562_1_, float p_76562_2_) {
		return new Vec3d(0.4, 0.4, 0.4);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public boolean isSkyColored() {
		return false;
	}
	
	@Override
	public boolean canRespawnHere() {
		return false;
	}
	
	@Override
	public int getRespawnDimension(EntityPlayerMP player) {
		if (leaving.remove(player.getGameProfile().getId())) {
			return 0;
		}
		return CConfig.limboDimId;
	}
	
	public LimboTeleporter getTeleporter() {
		return teleporter;
	}
	
	@Override
	public boolean isSurfaceWorld() {
		return false;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public float getCloudHeight() {
		return 8.0F;
	}

	@Override
	public boolean canCoordinateBeSpawn(int x, int z) {
		return this.world.getGroundAboveSeaLevel(new BlockPos(x, 0, z)).getMaterial().blocksMovement();
	}

	@Override
	public BlockPos getSpawnCoordinate() {
		return new BlockPos(0, 64, 0);
	}

	@Override
	public int getAverageGroundLevel() {
		return 50;
	}
	
	@Override
	public boolean canDropChunk(int x, int z) {
		Dungeon d = grid.getFromChunk(x, z);
		return d == null || d.noPlayersOnline();
	}
	
	@Override
	public void onPlayerAdded(EntityPlayerMP player) {
		int x = (int)Math.floor((player.posX/Dungeon.NODE_SIZE)/Dungeon.DUNGEON_SIZE);
		int z = (int)Math.floor((player.posZ/Dungeon.NODE_SIZE)/Dungeon.DUNGEON_SIZE);
		constraints.put(player, new Vec2i(x, z));
		CLog.info("Constraining {} to dungeon {}", player.getName(), constraints.get(player));
	}
	
	@Override
	public void onPlayerRemoved(EntityPlayerMP player) {
	}
	
	@Override
	public void onWorldSave() {
		world.getWorldInfo().setDimensionData(getDimensionType(), grid.serializeNBT());
	}
	
	@Override
	public void onWorldUpdateEntities() {
		Iterator<Map.Entry<EntityPlayerMP, Vec2i>> iter = constraints.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<EntityPlayerMP, Vec2i> en = iter.next();
			EntityPlayerMP player = en.getKey();
			if (player.isDead) {
				CLog.info("Removing constraints on {}", player.getName());
				if (leaving.contains(player.getGameProfile().getId())) {
					Dungeon d = grid.getFromBlock((int)player.posX, (int)player.posZ);
					if (d != null) {
						DungeonPlayer p = d.getPlayer(player);
						if (p != null) {
							d.removePlayer(p);
							player.setDead();
							MinecraftServer srv = player.mcServer;
							player.getServerWorld().addScheduledTask(() -> {
								EntityPlayerMP nw = srv.getPlayerList().getPlayerByUUID(p.getProfile().getId());
								nw.readFromNBT(p.getOldPlayer());
							});
						}
					}
				}
				iter.remove();
				continue;
			}
			player.setGameType(GameType.ADVENTURE);
			int dX = (int)Math.floor((player.posX/Dungeon.NODE_SIZE)/Dungeon.DUNGEON_SIZE);
			int dZ = (int)Math.floor((player.posZ/Dungeon.NODE_SIZE)/Dungeon.DUNGEON_SIZE);
			if (dX != en.getValue().x || dZ != en.getValue().y) {
				// mess with them a bit
				new SetGlitchStateMessage(GlitchState.CORRUPTING).sendTo(player);
				en.getKey().attackEntityFrom(constraint_violation, 75000);
				en.getKey().setDead(); // just in case
			}
		}
	}

	public void addLeavingPlayer(UUID id) {
		leaving.add(id);
	}

	public void addEnteringPlayer(DungeonPlayer player) {
		entering.put(player.getProfile().getId(), player);
	}
	
	public boolean isEntering(UUID id) {
		return entering.containsKey(id);
	}
	
	public DungeonPlayer popEntering(UUID id) {
		return entering.remove(id);
	}

}
