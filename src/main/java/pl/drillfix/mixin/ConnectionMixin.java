package pl.drillfix.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.drillfix.PingMiner;

/** Tylko podgląd: loguje pakiety kopania wysyłane do serwera. Niczego nie zmienia. */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), require = 0)
	private void drillfix$onSend(Packet<?> packet, CallbackInfo ci) {
		if (PingMiner.logPackets && packet instanceof ServerboundPlayerActionPacket action) {
			PingMiner.onPacketSent(action.getAction().name(), action.getPos());
		}
	}
}
