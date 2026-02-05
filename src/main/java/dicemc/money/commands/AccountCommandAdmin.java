package dicemc.money.commands;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dicemc.money.MoneyMod;
import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.setup.Config;
import dicemc.money.storage.DatabaseManager;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public class AccountCommandAdmin{
	//TODO add in a looper to accept multiple players as arguments and apply the command to all of them
	public static ArgumentBuilder<CommandSourceStack, ?> register(CommandDispatcher<CommandSourceStack> dispatcher) {
		return Commands.literal("admin")
				.requires((p) -> p.hasPermission(Config.ADMIN_LEVEL.get()))
				.then(Commands.literal("byName")
					.then(Commands.literal("balance")
							.then(Commands.argument("player", StringArgumentType.word())
								.executes((p) -> balance(p))))
					.then(Commands.argument("action", StringArgumentType.word())
							.suggests((c, b) -> b
									.suggest("set")
									.suggest("give")
									.suggest("take")
									.buildFuture())
							.then(Commands.argument("player", StringArgumentType.word())
									.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0d))
										.executes((p) -> process(p)))))
					.then(Commands.literal("transfer")
							.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
									.then(Commands.argument("from", StringArgumentType.word())
											.then(Commands.argument("to", StringArgumentType.word())
												.executes((p) -> transfer(p)))))))
				.then(Commands.literal("online")
						.then(Commands.literal("balance")
								.then(Commands.argument("player", EntityArgument.player())
									.executes((p) -> balance(p))))
						.then(Commands.argument("action", StringArgumentType.word())
								.suggests((c, b) -> b
										.suggest("set")
										.suggest("give")
										.suggest("take")
										.buildFuture())
								.then(Commands.argument("player", EntityArgument.player())
										.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0d))
											.executes((p) -> process(p)))))
						.then(Commands.literal("transfer")
								.then(Commands.argument("amount", DoubleArgumentType.doubleArg(0))
										.then(Commands.argument("from", EntityArgument.player())
												.then(Commands.argument("to", EntityArgument.player())
													.executes((p) -> transfer(p)))))));

	}
	
	public static int process(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		GameProfile player = resolveProfile(context, "player");
		if (player == null) {
			context.getSource().sendFailure(Component.translatable("message.command.playernotfound"));
			return 1;
		}
		MoneyWSD wsd = MoneyWSD.get();
		String option = StringArgumentType.getString(context, "action");
		UUID pid = player.getId();
		double value = DoubleArgumentType.getDouble(context, "amount");
		if (pid == null) {
			context.getSource().sendFailure(Component.translatable("message.command.playernotfound"));
			return 1;
		}
		switch (option) {
		case "set": {
			boolean result = wsd.setBalance(AcctTypes.PLAYER.key, pid, value);
			if (result) {
				if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
					boolean isPlayer = context.getSource().getEntity() instanceof ServerPlayer;
					UUID srcID = isPlayer ? context.getSource().getEntity().getUUID() : DatabaseManager.NIL;
					ResourceLocation srcType = isPlayer? AcctTypes.PLAYER.key : AcctTypes.SERVER.key;
					String srcName = isPlayer ? context.getSource().getEntity().getName().getString() : "Console";
					MoneyMod.dbm.postEntry(System.currentTimeMillis(), srcID, srcType, srcName
							, pid, AcctTypes.PLAYER.key, player.getName()
							, value, "Admin Set Command");
				}
				GameProfile finalPlayer = player;
				context.getSource().sendSuccess(() -> Component.translatable("message.command.set.success", finalPlayer.getName(), Config.getFormattedCurrency(value)), true);
				return 0;
			}
			context.getSource().sendFailure(Component.translatable("message.command.set.failure"));
			return 1;
		}
		case "give": {
			boolean result = wsd.changeBalance(AcctTypes.PLAYER.key, pid, value);
			if (result) {
				if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
					boolean isPlayer = context.getSource().getEntity() instanceof ServerPlayer;
					UUID srcID = isPlayer ? context.getSource().getEntity().getUUID() : DatabaseManager.NIL;
					ResourceLocation srcType = isPlayer? AcctTypes.PLAYER.key : AcctTypes.SERVER.key;
					String srcName = isPlayer ? context.getSource().getEntity().getName().getString() : "Console";
					MoneyMod.dbm.postEntry(System.currentTimeMillis(), srcID, srcType, srcName
							, pid, AcctTypes.PLAYER.key, player.getName()
							, value, "Admin Give Command");
				}
				GameProfile finalPlayer1 = player;
				context.getSource().sendSuccess(() -> Component.translatable("message.command.give.success", Config.getFormattedCurrency(value), finalPlayer1.getName()), true);
				return 0;
			}
			context.getSource().sendFailure(Component.translatable("message.command.change.failure"));
			return 1;
		}
		case "take": {
			boolean result = wsd.changeBalance(AcctTypes.PLAYER.key, pid, -value);
			if (result) {
				if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
					boolean isPlayer = context.getSource().getEntity() instanceof ServerPlayer;
					UUID srcID = isPlayer ? context.getSource().getEntity().getUUID() : DatabaseManager.NIL;
					ResourceLocation srcType = isPlayer? AcctTypes.PLAYER.key : AcctTypes.SERVER.key;
					String srcName = isPlayer ? context.getSource().getEntity().getName().getString() : "Console";
					MoneyMod.dbm.postEntry(System.currentTimeMillis(), srcID, srcType, srcName
							, pid, AcctTypes.PLAYER.key, player.getName()
							, value, "Admin Take Command");
				}
				GameProfile finalPlayer2 = player;
				context.getSource().sendSuccess(() -> Component.translatable("message.command.take.success", Config.getFormattedCurrency(value), finalPlayer2.getName()), true);
				return 0;
			}
			context.getSource().sendFailure(Component.translatable("message.command.change.failure"));
			return 1;
		}
		default:}
		return 0;
	}
	
	public static int balance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		GameProfile player = resolveProfile(context, "player");
		MoneyWSD wsd = MoneyWSD.get();
		if (player == null) {
			context.getSource().sendFailure(Component.translatable("message.command.playernotfound"));
			return 1;
		}
		double balP = wsd.getBalance(AcctTypes.PLAYER.key, player.getId()); 
		context.getSource().sendSuccess(() -> Component.literal(Config.getFormattedCurrency(balP)), true);
		return 0;
	}
	
	public static int transfer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		GameProfile fromplayer = resolveProfile(context, "from");
		GameProfile toplayer = resolveProfile(context, "to");
		MoneyWSD wsd = MoneyWSD.get();
		double value = DoubleArgumentType.getDouble(context, "amount");
		if (fromplayer == null) {
			context.getSource().sendFailure(Component.translatable("message.command.playernotfound"));
			return 1;
		}
		if (toplayer == null) {
			context.getSource().sendFailure(Component.translatable("message.command.playernotfound"));
			return 1;
		}
		boolean result = wsd.transferFunds(AcctTypes.PLAYER.key, fromplayer.getId(), AcctTypes.PLAYER.key, toplayer.getId(), value);
		if (result) {
			if (Config.ENABLE_HISTORY.get() && MoneyMod.dbm != null) {
				boolean isPlayer = context.getSource().getEntity() instanceof ServerPlayer;
				UUID srcID = isPlayer ? context.getSource().getEntity().getUUID() : DatabaseManager.NIL;
				String srcName = isPlayer ? context.getSource().getEntity().getName().getString() : "Console";
				MoneyMod.dbm.postEntry(System.currentTimeMillis(), fromplayer.getId(), AcctTypes.PLAYER.key, fromplayer.getName()
						, toplayer.getId(), AcctTypes.PLAYER.key, toplayer.getName(), value, "Admin Transfer Command Executed by: "+ srcName);
			}
			GameProfile finalToplayer = toplayer;
			context.getSource().sendSuccess(() -> Component.translatable("message.command.transfer.success", Config.getFormattedCurrency(value), finalToplayer.getName()), true);
			return 0;
		}
		context.getSource().sendFailure(Component.translatable("message.command.transfer.failure"));
		return 1;
	}

	private static GameProfile resolveProfile(CommandContext<CommandSourceStack> context, String argName) throws CommandSyntaxException {
		GameProfile profile = null;
		try {
			String name = StringArgumentType.getString(context, argName);
			profile = context.getSource().getServer().getProfileCache().get(name).orElse(null);
		} catch (IllegalArgumentException ignored) {}
		if (profile == null) {
			try {
				profile = EntityArgument.getPlayer(context, argName).getGameProfile();
			} catch (IllegalArgumentException ignored) {}
		}
		return profile;
	}

}
