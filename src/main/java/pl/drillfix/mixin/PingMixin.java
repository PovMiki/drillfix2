package pl.drillfix.mixin;

import net.minecraft.client.multiplayer.PingDebugMonitor;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.drillfix.PingMiner;

/** Pomiar pingu z odpowiedzi serwera na pakiet ping (wykres sieci z F3). */
@Mixin(PingDebugMonitor.class)
public abstract class PingMixin {
	@Inject(method = "onPongReceived", at = @At("TAIL"), require = 0)
	private void drillfix$onPong(ClientboundPongResponsePacket packet, CallbackInfo ci) {
		PingMiner.onPong(Util.getMillis() - packet.time());
	}
}
