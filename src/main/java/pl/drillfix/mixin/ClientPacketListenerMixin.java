package pl.drillfix.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pl.drillfix.PingMiner;

/**
 * Gra wysyła pakiety ping do serwera tylko przy otwartym wykresie sieci w F3.
 * Gdy ping miner jest włączony, wysyłamy je zawsze, żeby mierzyć ping na bieżąco.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;showNetworkCharts()Z"), require = 0)
	private boolean drillfix$alwaysPing(boolean original) {
		return original || PingMiner.enabled || PingMiner.debug;
	}
}
