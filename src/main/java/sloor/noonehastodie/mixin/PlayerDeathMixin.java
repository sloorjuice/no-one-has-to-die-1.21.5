package sloor.noonehastodie.mixin;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 1. Tell Fabric this Mixin modifies the PlayerEntity class
@Mixin(PlayerEntity.class)
public abstract class PlayerDeathMixin {

    // 2. Inject into the "damage" method
    // "HEAD" means we run this code right at the start of the method, before Minecraft does its math
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void interceptDeath(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {

        // Cast 'this' to the player object so we can check health
        PlayerEntity player = (PlayerEntity) (Object) this;

        // We only care about this happening on the server side
        if (player instanceof ServerPlayerEntity) {

            // 3. Check if this damage would kill the player
            float currentHealth = player.getHealth();

            // If the incoming damage is greater than or equal to current health...
            if (amount > currentHealth) {

                // Prevent Death:

                // A. cancel the damage event so Minecraft doesn't kill the player
                cir.setReturnValue(false);

                //B. Set health to a safe number (e.g., half a heart
                player.setHealth(1.0f);

                // C. Visual feedback (for testing)
                player.sendMessage(Text.of("§cYou are KNOCKED! Wait for help!"), true);

                // TODO: Add logic here later to start your 7-second timer
                // TODO: Add logic here to apply Slowness/Crawling
            }
        }
    }

}
