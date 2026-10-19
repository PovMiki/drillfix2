package pl.drillfix;

import net.minecraft.core.BlockPos;

/** Tylko statystyki do porównania ON/OFF. Nic nie wysyła na serwer. */
public final class MiningTracker {
	/** Licznik ticków klienta (zwiększany w PingMiner.tick). */
	public static long tickCounter = 0;

	private static BlockPos currentPos = null;
	private static long lastTick = -1;

	private MiningTracker() {}

	public static void onMiningTick(BlockPos pos) {
		// continueDestroyBlock może wywołać się więcej niż raz na tick - liczymy tick tylko raz.
		if (tickCounter != lastTick) {
			lastTick = tickCounter;
			DrillFix.miningTicks[DrillFix.mode()]++;
		}
		currentPos = pos.immutable();
	}

	/** Serwer zmienił blok - jeśli to ten, który właśnie kopaliśmy, liczymy go jako wykopany. */
	public static void onServerBlockChanged(BlockPos pos) {
		if (currentPos != null && currentPos.equals(pos)) {
			if (tickCounter - lastTick <= 20) {
				DrillFix.blocksMined[DrillFix.mode()]++;
			}
			currentPos = null;
		}
	}
}
