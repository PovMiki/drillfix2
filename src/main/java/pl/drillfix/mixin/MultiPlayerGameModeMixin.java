package pl.drillfix.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.drillfix.DrillFix;
import pl.drillfix.MiningTracker;
import pl.drillfix.PingMiner;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin implements PingMiner.GameModeAccess {
	@Shadow @Final private Minecraft minecraft;
	@Shadow private BlockPos destroyBlockPos;
	@Shadow private ItemStack destroyingItem;
	@Shadow private boolean isDestroying;
	@Shadow private float destroyProgress;

	/**
	 * To samo co HSM: vanilla przerywa kopanie, gdy trzymany przedmiot zmieni jakiekolwiek dane
	 * (paliwo drilla w opisie, naprawa przez Mending). Tu uznajemy, że to nadal ten sam cel,
	 * jeśli blok i typ przedmiotu się nie zmieniły.
	 */
	@Inject(method = "sameDestroyTarget", at = @At("RETURN"), cancellable = true)
	private void drillfix$keepBreaking(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() || this.minecraft.player == null) {
			return;
		}

		ItemStack held = this.minecraft.player.getMainHandItem();
		if (pos.equals(this.destroyBlockPos)
				&& !held.isEmpty()
				&& held.is(this.destroyingItem.getItem())) {
			// Tu vanilla zresetowałaby kopanie tylko przez zmianę danych przedmiotu.
			DrillFix.itemResets[DrillFix.mode()]++;
			if (DrillFix.enabled) {
				// Zapamiętujemy nową wersję przedmiotu, żeby ta sama zmiana nie liczyła się co tick.
				this.destroyingItem = held.copy();
				cir.setReturnValue(true);
			}
		}
	}

	// ---- start / koniec kopania (dla ping minera) ----

	@Inject(method = "startDestroyBlock", at = @At("HEAD"))
	private void drillfix$onStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		// Ten sam warunek co w grze: START leci, gdy nie kopiemy albo kopiemy inny blok.
		if (!this.isDestroying || !pos.equals(this.destroyBlockPos)) {
			PingMiner.onStartAttempt(pos);
		}
		PingMiner.localCall = true;
	}

	@Inject(method = "startDestroyBlock", at = @At("RETURN"))
	private void drillfix$onStartEnd(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		PingMiner.localCall = false;
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"))
	private void drillfix$onContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		MiningTracker.onMiningTick(pos);
		PingMiner.localCall = true;
	}

	@Inject(method = "continueDestroyBlock", at = @At("RETURN"))
	private void drillfix$onContinueEnd(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		PingMiner.localCall = false;
	}

	@Inject(method = "stopDestroyBlock", at = @At("HEAD"))
	private void drillfix$onStop(CallbackInfo ci) {
		PingMiner.onStop();
		PingMiner.localCall = true;
	}

	@Inject(method = "stopDestroyBlock", at = @At("RETURN"))
	private void drillfix$onStopEnd(CallbackInfo ci) {
		PingMiner.localCall = false;
	}

	/** Po lokalnym usunięciu bloku: zapominamy go bez wysyłania ABORT, żeby serwer dokończył kopanie. */
	@Override
	public void drillfix$forgetCurrentBlock() {
		this.isDestroying = false;
		this.destroyProgress = 0.0F;
	}
}
