package pl.drillfix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.mojang.brigadier.arguments.IntegerArgumentType;

/**
 * Ping offset miner.
 *
 * Pomiary (zegar klienta, t0 = chwila wysłania START):
 *  - ping (RTT) z odpowiedzi na pakiety ping (jak w Ping Offset Minerze),
 *  - pęknięcia 0-9 od serwera -> dopasowanie prostej: L (opóźnienie startu) i D (czas kopania na serwerze),
 *  - prognoza przyjścia potwierdzenia C = t0 + L + D, poprawiana średnim błędem z poprzednich bloków.
 *
 * Serwer kopie blok w chwili ~C - ping/2 (na naszym zegarze). Nasz kolejny START leci do serwera pół pingu,
 * więc żeby dotarł dokładnie w chwili wykopania, trzeba go wysłać w C - ping. Do tego dochodzi zapas "m":
 * START dociera m ms PO wykopaniu.
 *
 * Tryb AUTO wybiera m tak, żeby średnio tracić jak najmniej czasu:
 *     koszt(m) = m + P(pudło | m) * (D + m)
 * gdzie P(pudło) wynika ze zmierzonego rozrzutu prognoz i wahań pingu. Dodatkowo P(pudło) <= 1%.
 * Tryb POL = stary, sztywny zapas pół pingu.
 */
public final class PingMiner {
	public interface GameModeAccess {
		void drillfix$forgetCurrentBlock();
	}

	private record Ghost(BlockPos pos, BlockState original, long ghostTime, long deadline, boolean preview, double margin) {}

	public static boolean enabled = false;
	public static boolean debug = false;
	/** false = podgląd (blok zostaje, tylko sygnał "WYKOPANY"), true = usuwanie bloku. */
	public static boolean removeMode = false;
	/** true = zapas liczony automatycznie z opłacalności, false = sztywne pół pingu. */
	public static boolean autoMargin = true;
	public static boolean logPackets = false;
	/** Ręczna korekta w ms: + = wcześniej, - = później. */
	public static int userOffsetMs = 0;

	/** Ustawiane przez mixin, gdy gra sama rysuje własne (lokalne) pęknięcia - te ignorujemy. */
	public static boolean localCall = false;

	private static final double MAX_MISS_CHANCE = 0.01;

	// --- pomiary ---
	private static double rttMs = -1;
	private static double rttDev = 10;      // wahania pingu
	private static double errMean = 0;
	private static double errDev = 40;      // rozrzut błędu prognozy (startowo ostrożnie)
	private static int samples = 0;
	private static int hits = 0, misses = 0, skipped = 0;
	private static double lastMargin = -1, lastMissChance = 0;

	private static final List<Ghost> pending = new ArrayList<>();
	private static final Map<BlockPos, long[]> recentPredictions = new HashMap<>(); // pos -> {t0, predictedC}

	// --- bieżący blok ---
	private static BlockPos pos = null;
	private static BlockState state = null;
	private static long t0;
	private static boolean waitingAck = false;
	private static final long[] stageTime = new long[10];
	private static boolean ghosted = false;
	private static long lastPongMs = -1;

	private PingMiner() {}

	static long now() {
		return System.nanoTime() / 1_000_000L;
	}

	public static double rtt() {
		return rttMs;
	}

	public static void init() {
		ClientTickEvents.START_CLIENT_TICK.register(mc -> tick());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
				ClientCommands.literal("pom")
						.executes(c -> status())
						.then(ClientCommands.literal("on").executes(c -> { enabled = true; msg("§aPing miner §2WŁĄCZONY"); return 1; }))
						.then(ClientCommands.literal("off").executes(c -> { enabled = false; msg("§aPing miner §cWYŁĄCZONY"); return 1; }))
						.then(ClientCommands.literal("debug").executes(c -> { debug = !debug; msg("§aDebug: " + (debug ? "ON" : "OFF")); return 1; }))
						.then(ClientCommands.literal("podglad").executes(c -> { removeMode = false; msg("§aTryb: §fPODGLĄD §7(blok zostaje, dostajesz sygnał)"); return 1; }))
						.then(ClientCommands.literal("usun").executes(c -> { removeMode = true; msg("§aTryb: §fUSUWANIE §7(blok znika u Ciebie)"); return 1; }))
						.then(ClientCommands.literal("auto").executes(c -> { autoMargin = true; msg("§aZapas: §fAUTO §7(liczony z opłacalności)"); return 1; }))
						.then(ClientCommands.literal("pol").executes(c -> { autoMargin = false; msg("§aZapas: §fPÓŁ PINGU §7(sztywny)"); return 1; }))
						.then(ClientCommands.literal("pakiety").executes(c -> { logPackets = !logPackets; msg("§aLog pakietów: " + (logPackets ? "ON" : "OFF")); return 1; }))
						.then(ClientCommands.literal("offset").then(ClientCommands.argument("ms", IntegerArgumentType.integer(-500, 500)).executes(c -> {
							userOffsetMs = IntegerArgumentType.getInteger(c, "ms");
							msg("§aKorekta: " + userOffsetMs + " ms (+ = wcześniej, ryzykowniej)");
							return 1;
						})))
						.then(ClientCommands.literal("test")
								.executes(c -> MiningTest.start(120))
								.then(ClientCommands.argument("sekundy", IntegerArgumentType.integer(20, 1800))
										.executes(c -> MiningTest.start(IntegerArgumentType.getInteger(c, "sekundy"))))
								.then(ClientCommands.literal("stop").executes(c -> MiningTest.stop()))
								.then(ClientCommands.literal("wynik").executes(c -> MiningTest.compare())))
						.then(ClientCommands.literal("reset").executes(c -> {
							rttMs = -1; rttDev = 10; errMean = 0; errDev = 40; samples = 0; hits = 0; misses = 0; skipped = 0;
							msg("§aPomiary wyzerowane.");
							return 1;
						}))
		));
	}

	private static int status() {
		msg("§6Ping miner: " + (enabled ? "§2ON" : "§cOFF")
				+ (removeMode ? " §7[usuwanie]" : " §7[podgląd]")
				+ (autoMargin ? " §7[zapas auto]" : " §7[zapas pół pingu]"));
		msg(String.format("§7  ping §f%s±%.0f§7 | błąd prognozy §f%+.0f±%.0fms §7(%d próbek)",
				rttMs < 0 ? "?" : Math.round(rttMs) + "ms", rttDev, errMean, errDev, samples));
		msg(String.format("§7  ostatni zapas §f%s§7, ryzyko pudła §f%.1f%%§7 | trafienia §a%d§7, pudła §c%d§7, pominięte §e%d",
				lastMargin < 0 ? "-" : Math.round(lastMargin) + "ms", lastMissChance * 100, hits, misses, skipped));
		return 1;
	}

	static void msg(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("§8[§6POM§8] §r" + text));
		}
	}

	private static void playDing(Minecraft mc) {
		BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse("minecraft:block.note_block.pling")).ifPresent(ev ->
				mc.getSoundManager().play(new SimpleSoundInstance(ev.location(), SoundSource.MASTER, 0.5f, 1.5f,
						SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0d, 0.0d, 0.0d, true)));
	}

	// ---------------- zdarzenia z mixinów ----------------

	public static void onPacketSent(String action, BlockPos p) {
		if (!logPackets) return;
		String rel = (pos != null && p.equals(pos)) ? String.format(" §7(+%dms od START)", now() - t0) : "";
		msg("§b→ " + action + " §7" + p.getX() + " " + p.getY() + " " + p.getZ() + rel);
	}

	public static void onStartAttempt(BlockPos p) {
		Minecraft mc = Minecraft.getInstance();
		pos = p.immutable();
		state = mc.level != null ? mc.level.getBlockState(pos) : null;
		t0 = now();
		waitingAck = true;
		ghosted = false;
		Arrays.fill(stageTime, -1L);
		MiningTest.onStart(pos, state, t0);
	}

	public static void onStop() {
		pos = null;
	}

	public static void onPong(long sample) {
		if (sample <= 0 || sample > 2000) return;
		lastPongMs = now();
		addRttSample(sample);
	}

	public static void onAck() {
		if (!waitingAck) return;
		waitingAck = false;
		if (lastPongMs >= 0 && now() - lastPongMs < 5000) return;
		long sample = now() - t0;
		if (sample <= 0 || sample > 2000) return;
		addRttSample(sample);
	}

	private static void addRttSample(double sample) {
		if (rttMs < 0) {
			rttMs = sample;
			return;
		}
		rttDev = rttDev * 0.85 + Math.abs(sample - rttMs) * 0.15;
		rttMs = rttMs * 0.8 + sample * 0.2;
	}

	public static void onServerStage(BlockPos p, int stage) {
		if (localCall || pos == null || stage < 0 || stage > 9 || !p.equals(pos)) return;
		if (stageTime[stage] < 0) {
			stageTime[stage] = now();
			if (logPackets) msg(String.format("§d← pęknięcie %d §7(+%dms od START)", stage, stageTime[stage] - t0));
		}
	}

	public static void onServerBlock(BlockPos p, BlockState newState) {
		long n = now();
		if (logPackets && pos != null && p.equals(pos)) {
			msg(String.format("§d← serwer zmienił blok §7(+%dms od START)", n - t0));
		}
		MiningTest.onServerBlock(p, newState, n);

		// Kalibracja: porównaj realne potwierdzenie z prognozą.
		long[] pred = recentPredictions.remove(p);
		if (pred != null && n - pred[0] < 30_000) {
			double err = n - pred[1];
			if (Math.abs(err) < 2000) {
				errMean = samples == 0 ? err : errMean * 0.85 + err * 0.15;
				errDev = errDev * 0.85 + Math.abs(err - errMean) * 0.15;
				samples++;
			}
		}

		for (Iterator<Ghost> it = pending.iterator(); it.hasNext(); ) {
			Ghost g = it.next();
			if (!g.pos.equals(p)) continue;
			it.remove();
			if (newState == g.original) {
				onMiss("serwer mówi, że blok nadal stoi - za wcześnie");
			} else {
				hits++;
				if (g.preview) {
					msg(String.format("§a✔ §7potwierdzone §f%dms§7 po sygnale (zapas był §f%.0fms§7)", n - g.ghostTime, g.margin));
				} else if (debug) {
					msg(String.format("§a✔ §7potwierdzone %dms po usunięciu (zapas %.0fms)", n - g.ghostTime, g.margin));
				}
			}
		}
	}

	private static void onMiss(String why) {
		misses++;
		// Pudło = prognoza była zbyt pewna siebie -> zwiększ rozrzut, następne zapasy będą większe.
		errDev = Math.min(500, errDev * 1.5 + 10);
		MiningTest.onModMiss();
		msg("§c✘ §7" + why + String.format(" §7(rozrzut zwiększony do %.0fms)", errDev));
	}

	// ---------------- matematyka ----------------

	/** Dystrybuanta rozkładu normalnego (przybliżenie Abramowitz-Stegun). */
	private static double phi(double x) {
		double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
		double d = 0.3989422804014327 * Math.exp(-x * x / 2.0);
		double p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
		return x >= 0 ? 1.0 - p : p;
	}

	/**
	 * Wybiera zapas m (ms, o ile START ma dotrzeć po wykopaniu na serwerze).
	 * Zwraca {m, szansa pudła}.
	 */
	private static double[] chooseMargin(double D, double rtt) {
		if (!autoMargin) {
			double m = rtt / 2.0;
			return new double[] {m, 1.0 - phi(m / sigma())};
		}
		double sigma = sigma();
		double bestM = rtt, bestCost = Double.MAX_VALUE, bestP = 0;
		double maxM = Math.max(rtt, 4 * sigma) + 50;
		for (double m = 0; m <= maxM; m += 5) {
			double pMiss = 1.0 - phi(m / sigma);
			if (pMiss > MAX_MISS_CHANCE) continue;
			double cost = m + pMiss * (D + m);
			if (cost < bestCost) {
				bestCost = cost;
				bestM = m;
				bestP = pMiss;
			}
		}
		return new double[] {bestM, bestP};
	}

	/** Łączna niepewność: rozrzut prognozy + wahania pingu (średnie odchylenie -> sigma ~ x1.25). */
	private static double sigma() {
		double a = errDev * 1.25, b = rttDev * 1.25;
		return Math.max(5, Math.sqrt(a * a + b * b));
	}

	/** Dopasowanie prostej t = L + k * (D/10). Zwraca {L, D, czas ostatniego etapu, nr ostatniego, ms/etap}. */
	private static double[] fit() {
		int n = 0, lastK = -1;
		double sk = 0, st = 0, skk = 0, skt = 0;
		for (int k = 0; k < 10; k++) {
			if (stageTime[k] < 0) continue;
			double t = stageTime[k] - t0;
			n++; sk += k; st += t; skk += (double) k * k; skt += k * t;
			lastK = k;
		}
		if (n < 3) return null;
		double denom = n * skk - sk * sk;
		if (denom <= 0) return null;
		double slope = (n * skt - sk * st) / denom;
		double intercept = (st - slope * sk) / n;
		if (slope <= 0) return null;
		for (int k = 0; k < 10; k++) {
			if (stageTime[k] < 0) continue;
			double resid = (stageTime[k] - t0) - (intercept + slope * k);
			if (Math.abs(resid) > 100) return null; // nierówne etapy = lag serwera
		}
		return new double[] {intercept, slope * 10, stageTime[lastK], lastK, slope};
	}

	// ---------------- tick ----------------

	private static void tick() {
		Minecraft mc = Minecraft.getInstance();
		localCall = false;
		MiningTracker.tickCounter++;
		if (mc.level == null) {
			pending.clear();
			recentPredictions.clear();
			pos = null;
			return;
		}

		long n = now();
		MiningTest.tick(n);

		// Brak potwierdzenia w oknie = pudło.
		for (Iterator<Ghost> it = pending.iterator(); it.hasNext(); ) {
			Ghost g = it.next();
			if (n < g.deadline) continue;
			it.remove();
			if (!g.preview && mc.level.getBlockState(g.pos).isAir()) {
				mc.level.setBlock(g.pos, g.original, 3);
				onMiss("brak potwierdzenia - blok przywrócony");
			} else {
				onMiss("brak potwierdzenia w oknie - sygnał za wcześnie (albo zmieniłeś blok)");
			}
		}

		if (pos == null || ghosted || state == null || state.isAir()) return;
		if (mc.level.getBlockState(pos) != state) return;

		double[] f = fit();
		if (f == null) return;
		double L = f[0], D = f[1], lastStage = f[2], lastK = f[3], perStage = f[4];

		double predictedC = t0 + L + D + errMean;
		recentPredictions.put(pos, new long[] {t0, Math.round(t0 + L + D)});

		if (!enabled) return;
		double rtt = rttMs >= 0 ? rttMs : Math.max(0, L - 50);

		if (lastK < 9 && n - lastStage > perStage * 2 + 100) {
			if (debug) msg("§e⚠ §7etapy się spóźniają (lag serwera) - czekam na potwierdzenie");
			ghosted = true;
			skipped++;
			return;
		}

		double[] choice = chooseMargin(D, rtt);
		double margin = choice[0];
		lastMargin = margin;
		lastMissChance = choice[1];

		// Wysyłamy w chwili: potwierdzenie - pełny ping + zapas (START dotrze "margin" ms po wykopaniu).
		double ghostAt = predictedC - rtt + margin - userOffsetMs;
		if (n < ghostAt) return;

		BlockState original = mc.level.getBlockState(pos);
		long deadline = Math.max(Math.round(predictedC + 3 * sigma() + 150), n + 150);
		ghosted = true;

		if (!removeMode) {
			pending.add(new Ghost(pos, original, n, deadline + 1000, true, margin));
			msg(String.format("§a§l■ WYKOPANY §7(po %dms, D=%.0f, ping=%.0f, zapas=%.0f, ryzyko %.1f%%)",
					n - t0, D, rtt, margin, choice[1] * 100));
			playDing(mc);
			MiningTest.onSignal(pos, n);
			return;
		}

		mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		if (mc.gameMode instanceof GameModeAccess access) {
			access.drillfix$forgetCurrentBlock();
		}
		pending.add(new Ghost(pos, original, n, deadline, false, margin));
		MiningTest.onSignal(pos, n);
		if (debug) {
			msg(String.format("§e⛏ §7usunięty po %dms | D=%.0f ping=%.0f zapas=%.0f ryzyko %.1f%%",
					n - t0, D, rtt, margin, choice[1] * 100));
		}
	}
}
