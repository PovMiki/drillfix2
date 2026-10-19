package pl.drillfix;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Komendy (działają tylko u Ciebie, nic nie idzie na serwer):
 *   /drillfix          - pokaż statystyki
 *   /drillfix on|off   - włącz/wyłącz poprawkę (do testu A/B)
 *   /drillfix reset    - wyzeruj statystyki
 */
public final class DrillFix implements ClientModInitializer {
	public static boolean enabled = true;

	/** Statystyki osobno dla trybu ON [1] i OFF [0]. */
	public static final long[] miningTicks = new long[2];
	public static final long[] blocksMined = new long[2];
	/** Ile razy gra zresetowałaby kopanie przez zmianę danych przedmiotu. */
	public static final long[] itemResets = new long[2];

	public static int mode() {
		return enabled ? 1 : 0;
	}

	@Override
	public void onInitializeClient() {
		PingMiner.init();
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
				ClientCommands.literal("drillfix")
						.executes(c -> stats(c.getSource()))
						.then(ClientCommands.literal("on").executes(c -> setEnabled(c.getSource(), true)))
						.then(ClientCommands.literal("off").executes(c -> setEnabled(c.getSource(), false)))
						.then(ClientCommands.literal("reset").executes(c -> {
							for (int i = 0; i < 2; i++) {
								miningTicks[i] = 0;
								blocksMined[i] = 0;
								itemResets[i] = 0;
							}
							c.getSource().sendFeedback(Component.literal("§a[DrillFix] Statystyki wyzerowane."));
							return 1;
						}))
		));
	}

	private static int setEnabled(FabricClientCommandSource src, boolean value) {
		enabled = value;
		src.sendFeedback(Component.literal("§a[DrillFix] Poprawka: " + (value ? "§2WŁĄCZONA" : "§cWYŁĄCZONA")));
		return 1;
	}

	private static int stats(FabricClientCommandSource src) {
		src.sendFeedback(Component.literal("§6[DrillFix] Poprawka teraz: " + (enabled ? "§2ON" : "§cOFF")));
		line(src, "ON ", 1);
		line(src, "OFF", 0);
		if (miningTicks[0] > 0 && miningTicks[1] > 0 && blocksMined[0] > 0) {
			double on = perMinute(1), off = perMinute(0);
			double diff = (on / off - 1.0) * 100.0;
			src.sendFeedback(Component.literal(String.format("§e  Różnica: %+.1f%% bloków/min z poprawką", diff)));
		}
		return 1;
	}

	private static void line(FabricClientCommandSource src, String name, int m) {
		double minutes = miningTicks[m] / 1200.0;
		src.sendFeedback(Component.literal(String.format(
				"§7  %s: §f%d bloków §7w §f%.1f min §7kopania → §b%.1f bl/min§7, resety przez przedmiot: §f%d",
				name, blocksMined[m], minutes, perMinute(m), itemResets[m])));
	}

	private static double perMinute(int m) {
		return miningTicks[m] == 0 ? 0 : blocksMined[m] / (miningTicks[m] / 1200.0);
	}
}
