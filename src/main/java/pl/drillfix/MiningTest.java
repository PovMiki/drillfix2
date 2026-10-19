package pl.drillfix;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Test porównawczy: /pom test [sekundy] uruchamia pomiar w aktualnym trybie
 * (bez moda = /pom off, z modem = /pom on). Licznik rusza przy pierwszym uderzeniu w blok.
 *
 * Mierzy:
 *  - wykopane bloki (potwierdzone przez serwer) i bloki/min,
 *  - "przejście": ile ms PRZED (+) lub PO (-) potwierdzeniu serwera zaczynasz kolejny blok,
 *  - przerwę po stronie serwera: od wykopania do dotarcia Twojego kolejnego START (= ping - przejście),
 *  - powroty: zacząłeś blok, porzuciłeś go niewykopany i wróciłeś (pudło "na wyczucie"),
 *  - pudła moda.
 * /pom test wynik porównuje ostatni test bez moda z ostatnim testem z modem.
 */
public final class MiningTest {
	private record Result(String label, int blocks, double seconds, double avgLead, double rtt, int returns, int modMisses) {
		double perMinute() {
			return seconds <= 0 ? 0 : blocks / (seconds / 60.0);
		}

		double serverGap() {
			return rtt - avgLead;
		}
	}

	private static boolean armed = false, running = false;
	private static int durationSec = 120;
	private static long startTime, endTime;
	private static String label = "";

	private static int blocks = 0, returns = 0, modMisses = 0;
	private static double leadSum = 0;
	private static int leadCount = 0;
	private static double rttSum = 0;
	private static int rttCount = 0;

	/** pos -> {czas START, potwierdzony? 0/1}, plus oryginalny stan bloku. */
	private static final Map<BlockPos, long[]> starts = new HashMap<>();
	private static final Map<BlockPos, BlockState> originals = new HashMap<>();
	private static BlockPos latestStartPos = null;
	private static long latestStartTime = 0;
	private static long confirmWaitingForSwitch = -1;

	private static Result lastOff = null, lastOn = null;

	private MiningTest() {}

	public static int start(int seconds) {
		if (running || armed) {
			PingMiner.msg("§eTest już trwa. §7/pom test stop przerywa.");
			return 1;
		}
		durationSec = seconds;
		label = PingMiner.enabled
				? (PingMiner.removeMode ? "MOD (usuwanie)" : "MOD (podgląd)")
				: "BEZ MODA (na wyczucie)";
		blocks = 0; returns = 0; modMisses = 0;
		leadSum = 0; leadCount = 0; rttSum = 0; rttCount = 0;
		starts.clear(); originals.clear();
		latestStartPos = null; confirmWaitingForSwitch = -1;
		armed = true;
		PingMiner.msg("§aTest §f" + label + "§a: " + seconds + "s. Zacznij kopać - licznik ruszy przy pierwszym bloku.");
		return 1;
	}

	public static int stop() {
		if (running) {
			finish(PingMiner.now());
		} else {
			armed = false;
			PingMiner.msg("§7Test anulowany.");
		}
		return 1;
	}

	public static void onStart(BlockPos pos, BlockState state, long t) {
		if (armed && !running) {
			armed = false;
			running = true;
			startTime = t;
			endTime = t + durationSec * 1000L;
		}
		if (!running) return;

		long[] prev = starts.get(pos);
		if (prev != null && prev[1] == 0) {
			returns++; // wróciłeś do bloku, którego serwer nie wykopał
		}
		starts.put(pos, new long[] {t, 0});
		originals.put(pos, state);

		if (confirmWaitingForSwitch >= 0) {
			// Potwierdzenie przyszło, zanim przełączyłeś blok -> przejście po potwierdzeniu (ujemne).
			addLead(confirmWaitingForSwitch - t);
			confirmWaitingForSwitch = -1;
		}
		latestStartPos = pos;
		latestStartTime = t;
	}

	public static void onServerBlock(BlockPos pos, BlockState newState, long n) {
		if (!running) return;
		long[] s = starts.get(pos);
		BlockState orig = originals.get(pos);
		if (s == null || s[1] == 1 || orig == null || newState == orig) return;
		s[1] = 1;
		blocks++;

		if (latestStartPos != null && !latestStartPos.equals(pos) && latestStartTime > s[0]) {
			// Zacząłeś już kolejny blok przed potwierdzeniem -> przejście przed potwierdzeniem (dodatnie).
			addLead(n - latestStartTime);
		} else {
			confirmWaitingForSwitch = n;
		}
	}

	private static void addLead(double lead) {
		if (Math.abs(lead) > 3000) return; // przerwa w kopaniu, nie przejście
		leadSum += lead;
		leadCount++;
		double rtt = PingMiner.rtt();
		if (rtt > 0) {
			rttSum += rtt;
			rttCount++;
		}
	}

	public static void onSignal(BlockPos pos, long n) {
		// miejsce na przyszłe statystyki sygnału
	}

	public static void onModMiss() {
		if (running) modMisses++;
	}

	public static void tick(long n) {
		if (!running) return;
		if (n >= endTime) {
			finish(n);
			return;
		}
		for (Iterator<Map.Entry<BlockPos, long[]>> it = starts.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<BlockPos, long[]> e = it.next();
			if (n - e.getValue()[0] > 60_000) {
				originals.remove(e.getKey());
				it.remove();
			}
		}
	}

	private static void finish(long n) {
		running = false;
		double seconds = (Math.min(n, endTime) - startTime) / 1000.0;
		double avgLead = leadCount == 0 ? 0 : leadSum / leadCount;
		double rtt = rttCount == 0 ? Math.max(0, PingMiner.rtt()) : rttSum / rttCount;
		Result r = new Result(label, blocks, seconds, avgLead, rtt, returns, modMisses);
		if (label.startsWith("BEZ")) lastOff = r; else lastOn = r;

		PingMiner.msg("§6§l=== Wynik testu: " + label + " ===");
		print(r);
		if (lastOff != null && lastOn != null) compare();
		else PingMiner.msg("§7Zrób teraz test w drugim trybie (/pom " + (lastOn == null ? "on" : "off") + ", potem /pom test), a dostaniesz porównanie.");
	}

	private static void print(Result r) {
		PingMiner.msg(String.format("§7  Bloki: §f%d §7w §f%.0fs §7→ §b%.1f bl/min", r.blocks, r.seconds, r.perMinute()));
		PingMiner.msg(String.format("§7  Przejście na kolejny blok: §f%+.0fms §7%s potwierdzenia serwera",
				r.avgLead, r.avgLead >= 0 ? "przed" : "po"));
		PingMiner.msg(String.format("§7  Przerwa na serwerze (wykopany → Twój START dotarł): §f~%.0fms §7(ping %.0fms)",
				r.serverGap(), r.rtt));
		PingMiner.msg(String.format("§7  Powroty do niewykopanych: §f%d§7, pudła moda: §f%d", r.returns, r.modMisses));
	}

	public static int compare() {
		if (lastOff == null || lastOn == null) {
			PingMiner.msg("§eBrak obu testów. §7Zrób /pom test z /pom off i z /pom on.");
			return 1;
		}
		PingMiner.msg("§6§l=== Porównanie ===");
		PingMiner.msg(String.format("§7  Bez moda: §b%.1f bl/min§7, przerwa ~%.0fms, powroty %d",
				lastOff.perMinute(), lastOff.serverGap(), lastOff.returns));
		PingMiner.msg(String.format("§7  Z modem:  §b%.1f bl/min§7, przerwa ~%.0fms, pudła moda %d, powroty %d",
				lastOn.perMinute(), lastOn.serverGap(), lastOn.modMisses, lastOn.returns));
		if (lastOff.perMinute() > 0) {
			double diff = (lastOn.perMinute() / lastOff.perMinute() - 1) * 100;
			PingMiner.msg(String.format("§e  Mod: %+.1f%% bloków/min, przerwa krótsza o ~%.0fms na blok",
					diff, lastOff.serverGap() - lastOn.serverGap()));
		}
		return 1;
	}
}
