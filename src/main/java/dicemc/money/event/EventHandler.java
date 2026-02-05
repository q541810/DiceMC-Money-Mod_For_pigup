package dicemc.money.event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dicemc.money.MoneyMod;
import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.setup.Config;
import dicemc.money.storage.DatabaseManager;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;

@Mod.EventBusSubscriber(modid = MoneyMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class EventHandler {
	public static final String IS_SHOP = "is-shop";
	public static final String ACTIVATED = "shop-activated";
	public static final String OWNER = "owner";
	public static final String ITEMS = "items";
	public static final String TYPE = "shop-type";
	public static final String PRICE = "price";

	@SuppressWarnings("resource")
	@SubscribeEvent
	public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!event.getEntity().level().isClientSide && event.getEntity() instanceof ServerPlayer player) {
			double balP = MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, player.getUUID());
			player.sendSystemMessage(Component.literal(Config.getFormattedCurrency(balP)));
		}
	}
	
	@SuppressWarnings("resource")
	@SubscribeEvent
	public static void onPlayerDeath(LivingDeathEvent event) {
		if (!event.getEntity().level().isClientSide && event.getEntity() instanceof Player player) {
			double balp = MoneyWSD.get().getBalance(AcctTypes.PLAYER.key, player.getUUID());
			double loss = balp * Config.LOSS_ON_DEATH.get();
			if (loss > 0) {
				MoneyWSD.get().changeBalance(AcctTypes.PLAYER.key, player.getUUID(), -loss);
					if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
					MoneyMod.dbm.postEntry(System.currentTimeMillis(), DatabaseManager.NIL, AcctTypes.SERVER.key, "Server"
							, player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
							, -loss, "Loss on Death Event");
				}
				player.sendSystemMessage(Component.translatable("message.death", Config.getFormattedCurrency(loss)));
			}
		}
	}

	/**checks if the block being placed would border a shop sign and cancels the placement.
	 */
	@SubscribeEvent
	public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
		if (event.getLevel().isClientSide() || event.isCanceled()) return;
		boolean cancel = Arrays.stream(Direction.values()).anyMatch(direction -> {
			BlockEntity be = event.getLevel().getBlockEntity(event.getPos().relative(direction));
			return be != null && be.getPersistentData().contains(IS_SHOP);
		});
		event.setCanceled(cancel);
	}

	@SubscribeEvent
	public static void onShopBreak(BlockEvent.BreakEvent event) {
		if (!event.getLevel().isClientSide() && event.getLevel().getBlockState(event.getPos()).getBlock() instanceof WallSignBlock) {
			SignBlockEntity tile = (SignBlockEntity) event.getLevel().getBlockEntity(event.getPos());
			CompoundTag nbt = tile.getPersistentData();
			if (!nbt.isEmpty() && nbt.contains(ACTIVATED)) {
				Player player = event.getPlayer();
				boolean hasPerms = player.hasPermissions(Config.ADMIN_LEVEL.get());
				if (!nbt.getUUID(OWNER).equals(player.getUUID())) {
					event.setCanceled(!hasPerms);					
				}
				else if(nbt.getUUID(OWNER).equals(player.getUUID()) || hasPerms) {
					BlockPos backBlock = BlockPos.of(BlockPos.offset(event.getPos().asLong(), tile.getBlockState().getValue(WallSignBlock.FACING).getOpposite()));
					event.getLevel().getBlockEntity(backBlock).getPersistentData().remove(IS_SHOP);
				}
			}
		}
		else if (!event.getLevel().isClientSide() && event.getLevel().getBlockEntity(event.getPos()) != null) {
			BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
			IItemHandler inv = be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
			if (inv != null && be.getPersistentData().contains(IS_SHOP)) {
				Player player = event.getPlayer();
				event.setCanceled(!player.hasPermissions(Config.ADMIN_LEVEL.get()));
			}
		}
	}
	
	@SubscribeEvent
	public static void onStorageOpen(PlayerInteractEvent.RightClickBlock event) {
		BlockEntity invTile = event.getLevel().getBlockEntity(event.getPos());
		IItemHandler inv = invTile == null ? null : invTile.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
		if (invTile != null && inv != null) {
			if (invTile.getPersistentData().contains(IS_SHOP)) {
				if (!invTile.getPersistentData().getUUID(OWNER).equals(event.getEntity().getUUID())) {
					event.setCanceled(!event.getEntity().hasPermissions(Config.ADMIN_LEVEL.get()));					
				}
			}
		}
	}
	
	@SuppressWarnings("resource")
	@SubscribeEvent
	public static void onSignLeftClick(PlayerInteractEvent.LeftClickBlock event) {
		if (!event.getLevel().isClientSide
				&& event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START
				&& event.getLevel().getBlockState(event.getPos()).getBlock() instanceof WallSignBlock) {
			SignBlockEntity tile = (SignBlockEntity) event.getLevel().getBlockEntity(event.getPos());
			CompoundTag nbt = tile.getPersistentData();
			if (nbt.contains(ACTIVATED))
				getSaleInfo(nbt, event.getEntity());
		}
	}

	@SubscribeEvent
	public static void onSignRightClick(PlayerInteractEvent.RightClickBlock event) {
		BlockState state = event.getLevel().getBlockState(event.getPos());
		if (!event.getLevel().isClientSide && state.getBlock() instanceof WallSignBlock) {
			BlockPos backBlock = BlockPos.of(BlockPos.offset(event.getPos().asLong(), state.getValue(WallSignBlock.FACING).getOpposite()));
			BlockEntity invTile = event.getLevel().getBlockEntity(backBlock);
			if (invTile != null) {
				SignBlockEntity tile = (SignBlockEntity) event.getLevel().getBlockEntity(event.getPos());
				if (!tile.getPersistentData().contains(ACTIVATED)) {
					if (activateShop(invTile, tile, event.getLevel(), event.getPos(), event.getEntity())) {
						event.setCanceled(true);
						event.setCancellationResult(InteractionResult.SUCCESS);
					}
				}
				else {
					processTransaction(invTile, tile, event.getEntity());
					event.setCanceled(true);
					event.setCancellationResult(InteractionResult.SUCCESS);
				}
			}
		}
	}
	
	private static boolean activateShop(BlockEntity storage, SignBlockEntity tile, Level world, BlockPos pos, Player player) {
		Component actionEntry = tile.getFrontText().getMessage(0, true);
		double price = 0.0;
		try {
			price = Math.abs(Double.valueOf(tile.getFrontText().getMessage(3, true).getString()));
		}
		catch(NumberFormatException e) {
			player.sendSystemMessage(Component.translatable("message.activate.failure.money"));
			world.destroyBlock(pos, true, player);
			return false;
		}
		//first confirm the action type is valid
		String shopString = switch (actionEntry.getString().toLowerCase()) {
			case "[buy]" -> player.hasPermissions(Config.SHOP_LEVEL.get()) ? "buy" : null;
			case "[sell]" -> player.hasPermissions(Config.SHOP_LEVEL.get()) ? "sell": null;
			case "[server-buy]", "[server-sell]" -> {
				if (!player.hasPermissions(Config.ADMIN_LEVEL.get())) {
					player.sendSystemMessage(Component.translatable("message.activate.failure.admin"));
					yield null;
				}
				yield actionEntry.getString().toLowerCase().replace("[","").replace("]","");
			}
			default ->  null;
		};
		if (shopString == null) return false;
		//check if the storage block has an item in the inventory
		IItemHandler inv = null;
		for (Direction direction : Direction.values()) {
			IItemHandler handler = storage.getCapability(ForgeCapabilities.ITEM_HANDLER, direction).orElse(null);
			if (handler == null) continue;
			for (int i = 0; i < handler.getSlots(); i++) {
				if (!handler.getStackInSlot(i).isEmpty()) {
					inv = handler;
					break;
				}
			}
			if (inv != null) break;
		}
		if (inv == null) return false;

		//store shop data on sign
		tile.getPersistentData().putDouble(PRICE, price);

		Component[] signText = new Component[] {
				Component.literal(actionEntry.getString()).withStyle(ChatFormatting.BLUE),
				tile.getFrontText().getMessage(1, true),
				tile.getFrontText().getMessage(2, true),
				Component.literal(Config.getFormattedCurrency(price)).withStyle(ChatFormatting.GOLD)
		};

		tile.setText(new SignText(signText, signText, DyeColor.BLACK, false), true);
		tile.getPersistentData().putString(TYPE, shopString);
		tile.getPersistentData().putBoolean(ACTIVATED, true);
		tile.getPersistentData().putUUID(OWNER, player.getUUID());
		//Serialize all items in the TE and store them in a ListNBT
		ListTag lnbt = new ListTag();
		for (int i = 0; i < inv.getSlots(); i++) {
			ItemStack inSlot = inv.getStackInSlot(i);
			if (inSlot.isEmpty()) continue;
			if (inSlot.getItem() instanceof WritableBookItem)
				lnbt.add(getItemFromBook(inSlot));
			else
				lnbt.add(inSlot.save(new CompoundTag()));
		}
		tile.getPersistentData().put(ITEMS, lnbt);
		tile.setChanged();
		storage.getPersistentData().putBoolean(IS_SHOP, true);
		storage.getPersistentData().putUUID(OWNER, player.getUUID());
		storage.setChanged();
		BlockState state = world.getBlockState(pos);
		world.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
		return true;
	}
	
	private static CompoundTag getItemFromBook(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("pages", Tag.TAG_LIST)) return stack.save(new CompoundTag());
		ListTag pages = tag.getList("pages", Tag.TAG_STRING);
		if (pages.isEmpty()) return stack.save(new CompoundTag());
		String page = pages.getString(0);
		if (page.length() >= 7 && page.substring(0, 7).equalsIgnoreCase("vending")) {
			String subStr = page.length() > 8 ? page.substring(8) : "";
			try {
				CompoundTag parsed = TagParser.parseTag(subStr);
				stack = ItemStack.of(parsed);
				return stack.save(new CompoundTag());
			}
			catch(CommandSyntaxException | NoSuchElementException e) {e.printStackTrace();}
		}
		return stack.save(new CompoundTag());
	}
	
	private static void getSaleInfo(CompoundTag nbt, Player player) {
		String type = nbt.getString(TYPE);
		boolean isBuy = type.equalsIgnoreCase("buy") || type.equalsIgnoreCase("server-buy");
		List<ItemStack> transItems = new ArrayList<>();
		ListTag itemsList = nbt.getList(ITEMS, Tag.TAG_COMPOUND);
		for (int i = 0; i < itemsList.size(); i++) {
			transItems.add(ItemStack.of(itemsList.getCompound(i)));
		}
		double value = nbt.getDouble(PRICE);
		MutableComponent itemComponent = getTransItemsDisplayString(transItems);
		if (isBuy)
			player.sendSystemMessage(Component.translatable("message.shop.info", itemComponent, Config.getFormattedCurrency(value)));
		else
			player.sendSystemMessage(Component.translatable("message.shop.info", Config.getFormattedCurrency(value), itemComponent));
	}
	
	private static MutableComponent getTransItemsDisplayString(List<ItemStack> list ) {
		List<ItemStack> items = new ArrayList<>();
		for (int l = 0; l < list.size(); l++) {
			boolean hadMatch = false;
			for (int i = 0; i < items.size(); i++) {
				if (list.get(l).is(items.get(i).getItem()) && ItemStack.matches(list.get(l), items.get(i))) {
					items.get(i).grow(list.get(l).getCount());
					hadMatch = true;
					break;
				}
			}
			if (!hadMatch) items.add(list.get(l));
		}
		MutableComponent itemComponent = Component.literal("");
		boolean isFirst = true;
		for (ItemStack item : items) {
			if (!isFirst) itemComponent.append(", ");
			itemComponent.append(item.getCount()+"x ");
			itemComponent.append(item.getDisplayName());
			isFirst = false;
		}
		return itemComponent;
	}
	
	private static void processTransaction(BlockEntity tile, SignBlockEntity sign, Player player) {
		MoneyWSD wsd = MoneyWSD.get();
		CompoundTag nbt = sign.getPersistentData();
		IItemHandler inv = tile.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
		if (inv == null) return;
		List<ItemStack> transItems = new ArrayList<>();
		Map<ItemStack, ItemStack> consolidatedItems = new HashMap<>();
		ListTag itemsList = nbt.getList(ITEMS, Tag.TAG_COMPOUND);
		for (int i = 0; i < itemsList.size(); i++) {
			ItemStack srcStack = ItemStack.of(itemsList.getCompound(i));
			ItemStack keyStack = srcStack.copy();
			keyStack.setCount(1);
			boolean hasEntry = false;
			for (Map.Entry<ItemStack, ItemStack> map : consolidatedItems.entrySet()) {
				if (map.getKey().is(srcStack.getItem()) && ItemStack.matches(map.getKey(), srcStack)) {
					map.getValue().grow(srcStack.getCount());
					hasEntry = true;
				}
			}
			if (!hasEntry) consolidatedItems.put(keyStack, srcStack);
		}
		for (Map.Entry<ItemStack, ItemStack> map : consolidatedItems.entrySet()) {
			transItems.add(map.getValue());
		}
		//ItemStack transItem = ItemStack.of(nbt.getCompound("item"));
		String action = nbt.getString(TYPE);
		double value = nbt.getDouble(PRICE);
		//================BUY=================================================================================
		if (action.equalsIgnoreCase("buy")) { //BUY
			//First check the available funds and stock for trade
			double balP = wsd.getBalance(AcctTypes.PLAYER.key, player.getUUID());
			if (value > balP) {
				player.sendSystemMessage(Component.translatable("message.shop.buy.failure.funds"));
				return;
			}
			Map<Integer, ItemStack> slotMap = new HashMap<>();
			for (int tf = 0; tf < transItems.size(); tf++) {
				int[] stackSize = {transItems.get(tf).getCount()};
				final Integer t = Integer.valueOf(tf);
				boolean test = false;
				for (int i = 0; i < inv.getSlots(); i++) {
					ItemStack inSlot;
					if (slotMap.containsKey(i) && transItems.get(t).getItem().equals(slotMap.get(i).getItem()) && ItemStack.matches(transItems.get(t), slotMap.get(i))) {
						inSlot = inv.extractItem(i, stackSize[0]+slotMap.get(i).getCount(), true);
						inSlot.shrink(slotMap.get(i).getCount());
					}
					else inSlot = inv.extractItem(i, stackSize[0], true);
					if (inSlot.getItem().equals(transItems.get(t).getItem()) && ItemStack.matches(inSlot, transItems.get(t))) {
						slotMap.merge(i, inSlot, (s, o) -> {s.grow(o.getCount()); return s;});
						stackSize[0] -= inSlot.getCount();
					}
					if (stackSize[0] <= 0) break;
				}
				test =  stackSize[0] <= 0;
				if (!test) {
					player.sendSystemMessage(Component.translatable("message.shop.buy.failure.stock"));
					return;
				}
			}
			//Test if container has inventory to process.
			//If so, process transfer of items and funds.			
			UUID shopOwner = nbt.getUUID(OWNER);
			boolean transferOk = wsd.transferFunds(AcctTypes.PLAYER.key, player.getUUID(), AcctTypes.PLAYER.key, shopOwner, value);
			if (!transferOk) {
				player.sendSystemMessage(Component.translatable("message.command.transfer.failure"));
				return;
			}
			if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
				String itemPrint = "";
				itemsList.forEach((a) -> {itemPrint.concat(a.getAsString());});
				String shopOwnerName = player.getServer().getProfileCache().get(shopOwner).map(GameProfile::getName).orElse(shopOwner.toString());
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
						, shopOwner, AcctTypes.PLAYER.key, shopOwnerName
						, value, itemsList.getAsString());
			}
			for (Map.Entry<Integer, ItemStack> map : slotMap.entrySet()) {
				ItemStack pStack = inv.extractItem(map.getKey(), map.getValue().getCount(), false);
				if (!player.addItem(pStack))
					player.drop(pStack, false);
			}
			MutableComponent msg =  Component.translatable("message.shop.buy.success"
					, getTransItemsDisplayString(transItems), Config.getFormattedCurrency(value));
			player.displayClientMessage(msg, true);
			player.getServer().sendSystemMessage(msg);
			return;
		}
		//================SELL=================================================================================
		else if (action.equalsIgnoreCase("sell")) { //SELL
			//First check the available funds and stock for trade
			UUID shopOwner = nbt.getUUID(OWNER);
			double balP = wsd.getBalance(AcctTypes.PLAYER.key, shopOwner);
			if (value > balP) {
				player.sendSystemMessage(Component.translatable("message.shop.sell.failure.funds"));
				return;
			}
			//test if player has item in inventory to sell
			//next test that the inventory has space
			Map<Integer, ItemStack> slotMap = new HashMap<>();
			for (int t = 0; t < transItems.size(); t++) {
				int stackSize = transItems.get(t).getCount();
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					ItemStack inSlot = player.getInventory().getItem(i).copy();
					int count = stackSize > inSlot.getCount() ? inSlot.getCount() : stackSize;
					inSlot.setCount(count);
					if (slotMap.containsKey(i) && transItems.get(t).getItem().equals(slotMap.get(i).getItem()) && ItemStack.matches(transItems.get(t), slotMap.get(i))) {
						count = stackSize+slotMap.get(i).getCount() > inSlot.getCount() ? inSlot.getCount() : stackSize+slotMap.get(i).getCount();
						inSlot.setCount(count);
					}
					if (inSlot.getItem().equals(transItems.get(t).getItem()) && ItemStack.matches(inSlot, transItems.get(t))) {
						slotMap.merge(i, inSlot, (s, o) -> {s.grow(o.getCount()); return s;});
						stackSize -= inSlot.getCount();
					}						
					if (stackSize <= 0) break;
				}
				if (stackSize > 0) {
					player.sendSystemMessage(Component.translatable("message.shop.sell.failure.stock"));
					return;
				}
				
			}
			Map<Integer, ItemStack> invSlotMap = new HashMap<>();
            for (ItemStack transItem : transItems) {
                ItemStack sim = transItem.copy();
                boolean test = false;
                for (int i = 0; i < inv.getSlots(); i++) {
                    ItemStack insertResult = inv.insertItem(i, sim, true);
                    if (insertResult.isEmpty()) {
                        invSlotMap.merge(i, sim.copy(), (s, o) -> {
                            s.grow(o.getCount());
                            return s;
                        });
                        sim.setCount(0);
                        break;
                    } else if (insertResult.getCount() == sim.getCount()) {
                        continue;
                    } else {
                        ItemStack insertSuccess = sim.copy();
                        insertSuccess.shrink(insertResult.getCount());
                        sim.setCount(insertResult.getCount());
                        invSlotMap.merge(i, insertSuccess, (s, o) -> {
                            s.grow(insertSuccess.getCount());
                            return s;
                        });
                    }
                }
                if (!sim.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("message.shop.sell.failure.space"));
                } else
                    test = true;
                if (!test) return;
            }
			//Process Transfers now that reqs have been met
			boolean transferOk = wsd.transferFunds(AcctTypes.PLAYER.key, shopOwner, AcctTypes.PLAYER.key, player.getUUID(), value);
			if (!transferOk) {
				player.sendSystemMessage(Component.translatable("message.command.transfer.failure"));
				return;
			}
			if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
				String itemPrint = "";
				itemsList.forEach((a) -> {itemPrint.concat(a.getAsString());});
				String shopOwnerName = player.getServer().getProfileCache().get(shopOwner).map(GameProfile::getName).orElse(shopOwner.toString());
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), shopOwner, AcctTypes.PLAYER.key, shopOwnerName
						, player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
						, value, itemsList.getAsString());
			}
			for (Map.Entry<Integer, ItemStack> pSlots : slotMap.entrySet()) {
				player.getInventory().removeItem(pSlots.getKey(), pSlots.getValue().getCount());
			}
			for (Map.Entry<Integer, ItemStack> map : invSlotMap.entrySet()) {
				inv.insertItem(map.getKey(), map.getValue(), false);
			}
			player.sendSystemMessage(Component.translatable("message.shop.sell.success"
					, Config.getFormattedCurrency(value), getTransItemsDisplayString(transItems)));
			return;
		}
		//================SERVER BUY=================================================================================
		else if (action.equalsIgnoreCase("server-buy")) { //SERVER BUY
			//First check the available funds and stock for trade
			double balP = wsd.getBalance(AcctTypes.PLAYER.key, player.getUUID());
			if (value > balP) {
				player.sendSystemMessage(Component.translatable("message.shop.buy.failure.funds"));
				return;
			}
			wsd.changeBalance(AcctTypes.PLAYER.key, player.getUUID(), -value);
			if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
				String itemPrint = "";
				itemsList.forEach((a) -> {itemPrint.concat(a.getAsString());});
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), DatabaseManager.NIL, AcctTypes.SERVER.key, "Server"
						, player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
						, -value, itemsList.getAsString());
			}
			for (int i = 0; i < transItems.size(); i++) {
				ItemStack pStack = transItems.get(i).copy();
				if (!player.addItem(pStack))
					player.drop(pStack, false);
			}
			player.sendSystemMessage(Component.translatable("message.shop.buy.success"
					, getTransItemsDisplayString(transItems), Config.getFormattedCurrency(value)));
			return;
		}
		//================SERVER SELL=================================================================================
		else if (action.equalsIgnoreCase("server-sell")) { //SERVER SELL
			Map<Integer, ItemStack> slotMap = new HashMap<>();
			for (int t = 0; t < transItems.size(); t++) {
				int stackSize = transItems.get(t).getCount();
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					ItemStack inSlot = player.getInventory().getItem(i).copy();
					int count = stackSize > inSlot.getCount() ? inSlot.getCount() : stackSize;
					inSlot.setCount(count);
					if (slotMap.containsKey(i) && transItems.get(t).getItem().equals(slotMap.get(i).getItem()) && ItemStack.matches(transItems.get(t), slotMap.get(i))) {
						count = stackSize+slotMap.get(i).getCount() > inSlot.getCount() ? inSlot.getCount() : stackSize+slotMap.get(i).getCount();
						inSlot.setCount(count);
					}
					if (inSlot.getItem().equals(transItems.get(t).getItem()) && ItemStack.matches(inSlot, transItems.get(t))) {
						slotMap.merge(i, inSlot, (s, o) -> {s.grow(o.getCount()); return s;});
						stackSize -= inSlot.getCount();
					}						
					if (stackSize <= 0) break;
				}
				if (stackSize > 0) {
					player.sendSystemMessage(Component.translatable("message.shop.sell.failure.stock"));
					return;
				}
				
			}
			wsd.changeBalance(AcctTypes.PLAYER.key, player.getUUID(), value);
			if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
				String itemPrint = "";
				itemsList.forEach((a) -> {itemPrint.concat(a.getAsString());});
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), DatabaseManager.NIL, AcctTypes.SERVER.key, "Server"
						, player.getUUID(), AcctTypes.PLAYER.key, player.getName().getString()
						, value, itemsList.getAsString());
			}
			for (Map.Entry<Integer, ItemStack> pSlots : slotMap.entrySet()) {
				player.getInventory().getItem(pSlots.getKey()).shrink(pSlots.getValue().getCount());
			}
			player.sendSystemMessage(Component.translatable("message.shop.sell.success"
					, Config.getFormattedCurrency(value), getTransItemsDisplayString(transItems)));
			return;
		}
	}

}
