package pl.drillfix.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.drillfix.MiningTracker;
import pl.drillfix.PingMiner;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	/** Stan bloku przysłany przez serwer (tylko od serwera, nie nasze lokalne zmiany). */
	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"), require = 0)
	private void drillfix$onServerBlock(CallbackInfo ci,
			@Local(argsOnly = true) BlockPos pos,
			@Local(argsOnly = true) BlockState state) {
		MiningTracker.onServerBlockChanged(pos);
		PingMiner.onServerBlock(pos.immutable(), state);
	}

	/** Serwer potwierdził nasz pakiet kopania - służy do pomiaru pingu. */
	@Inject(method = "handleBlockChangedAck", at = @At("HEAD"), require = 0)
	private void drillfix$onAck(int sequence, CallbackInfo ci) {
		PingMiner.onAck();
	}

	/** Pęknięcia bloków. Lokalne (rysowane przez samą grę) są odfiltrowane flagą localCall. */
	@Inject(method = "destroyBlockProgress", at = @At("HEAD"), require = 0)
	private void drillfix$onDestroyProgress(int id, BlockPos pos, int progress, CallbackInfo ci) {
		PingMiner.onServerStage(pos, progress);
	}
}
