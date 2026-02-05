package dicemc.money.storage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.authlib.GameProfile;
import dicemc.money.MoneyMod;
import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.api.IMoneyManager;
import dicemc.money.setup.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.server.ServerLifecycleHooks;

public class MoneyWSD extends SavedData implements IMoneyManager {
	private static final String DATA_NAME = MoneyMod.MOD_ID + "_data";

	public MoneyWSD() {}
	
	private final Map<ResourceLocation, Map<UUID, Double>> accounts = new ConcurrentHashMap<>();
	
	public Map<UUID, Double> getAccountMap(ResourceLocation res) {
		if (res == null) return Map.of();
		synchronized (this) {
			Map<UUID, Double> data = accounts.get(res);
			if (data == null) return Map.of();
			return Map.copyOf(data);
		}
	}
	
	@Override
	public synchronized double getBalance(ResourceLocation type, UUID owner) {
		if (type == null || owner == null) return 0d;
		accountChecker(type, owner);
		return accounts.get(type).getOrDefault(owner, Config.STARTING_FUNDS.get());
	}
	
	@Override
	public synchronized boolean setBalance(ResourceLocation type, UUID id, double value) {
		if (type == null || id == null) return false;
		accounts.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(id, value);
		this.setDirty();
		return true;
	}

	@Override
	public synchronized boolean changeBalance(ResourceLocation type, UUID id, double value) {
		if (type == null || id == null) return false;
		double current = getBalance(type, id);
		double future = current + value;
		return setBalance(type, id, future);
	}
	
	@Override
	public synchronized boolean transferFunds(ResourceLocation fromType, UUID fromID, ResourceLocation toType, UUID toID, double value) {
		if (fromType == null || fromID == null || toType == null || toID == null) return false;
		double funds = Math.abs(value);
		double fromBal = getBalance(fromType, fromID);
		if (fromBal < funds) return false;
		if (changeBalance(fromType, fromID, -funds) && changeBalance(toType, toID, funds)) { 
			this.setDirty();
			return true;
		}
		else 
			return false;
	}
	
	public synchronized void accountChecker(ResourceLocation type, UUID owner) {
		if (type == null) return;
		Map<UUID, Double> account = accounts.computeIfAbsent(type, k -> new ConcurrentHashMap<>());
		if (owner == null) return;
		if (!account.containsKey(owner)) {
			account.put(owner, Config.STARTING_FUNDS.get());
			if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null && MoneyMod.dbm.server != null) {
				String ownerName = MoneyMod.dbm.server.getProfileCache().get(owner).map(GameProfile::getName).orElse(owner.toString());
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), DatabaseManager.NIL, AcctTypes.SERVER.key, "Server"
						, owner, type, ownerName
						, Config.STARTING_FUNDS.get(), "Starting Funds Deposit");
			}
			this.setDirty();
		}
	}

	public MoneyWSD(CompoundTag nbt) {
		ListTag baseList = nbt.getList("types", Tag.TAG_COMPOUND);
		for (int b = 0; b < baseList.size(); b++) {
			CompoundTag entry = baseList.getCompound(b);
			ResourceLocation res = new ResourceLocation(entry.getString("type"));
			Map<UUID, Double> data = new ConcurrentHashMap<>();
			ListTag list = entry.getList("data", Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				CompoundTag snbt = list.getCompound(i);
				UUID id = snbt.getUUID("id");
				double balance = snbt.getDouble("balance");
				data.put(id, balance);
			}
			accounts.put(res, data);
		}
	}

	@Override
	public synchronized CompoundTag save(CompoundTag nbt) {
		ListTag baseList = new ListTag();
		for (Map.Entry<ResourceLocation, Map<UUID, Double>> base : accounts.entrySet()) {
			CompoundTag entry = new CompoundTag();
			ListTag list = new ListTag();
			entry.putString("type", base.getKey().toString());
			for (Map.Entry<UUID, Double> data : base.getValue().entrySet()) {
				CompoundTag dataNBT = new CompoundTag();
				dataNBT.putUUID("id", data.getKey());
				dataNBT.putDouble("balance", data.getValue());
				list.add(dataNBT);
			}
			entry.put("data", list);
			baseList.add(entry);
		}
		nbt.put("types", baseList);
		return nbt;
	}
	
	private static MoneyWSD load(CompoundTag nbt) {
		return new MoneyWSD(nbt);
	}
	
	public static MoneyWSD get() {
		if (ServerLifecycleHooks.getCurrentServer() != null)
			return ServerLifecycleHooks.getCurrentServer().overworld().getDataStorage().computeIfAbsent(MoneyWSD::load, MoneyWSD::new, DATA_NAME);
		else
			return new MoneyWSD();
	}
}
