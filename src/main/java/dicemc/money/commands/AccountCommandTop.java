package dicemc.money.commands;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Pair;

import dicemc.money.MoneyMod.AcctTypes;
import dicemc.money.setup.Config;
import dicemc.money.storage.MoneyWSD;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class AccountCommandTop implements Command<CommandSourceStack>{
	private static final AccountCommandTop CMD = new AccountCommandTop();
	
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("top").executes(CMD));
	}
	
	@Override
	public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		if (Config.TOP_SIZE.get() <= 0) return 0;
		Map<UUID, Double> unsorted = MoneyWSD.get().getAccountMap(AcctTypes.PLAYER.key);
		DecimalFormat df = new DecimalFormat("###,###,###,##0.00");
		
		List<Pair<UUID, Double>> sorted = unsorted.entrySet().stream()
				.sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
				.limit(Config.TOP_SIZE.get())
				.map(x -> Pair.of(x.getKey(), x.getValue()))
				.toList();
		int limit = sorted.size();
		
		String tkey = limit == 1 ? "message.command.top1" : "message.command.top";
		context.getSource().sendSuccess(() -> Component.translatable(tkey, limit), false);
		for (int i = 0; i < limit; i++) {
			Pair<UUID, Double> p = sorted.get(i);
			String name = context.getSource().getServer().getProfileCache().get(p.getFirst()).map(GameProfile::getName).orElse(p.getFirst().toString());
			int finalI = i;
			context.getSource().sendSuccess(() -> Component.literal("#"+(finalI +1)+" "+name+": "+Config.getFormattedCurrency(df, p.getSecond())), false);
		}
		return 0;
	}
}
