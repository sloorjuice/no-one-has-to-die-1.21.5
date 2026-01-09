package sloor.noonehastodie.mixin;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.entity.EntityPose;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TODO: Add a config. Time for a player to die after being knocked, time it takes to revive a player, etc. Show Knocked Messages, Show Knocked Location
// TODO: Highlight nearby players when you are knocked.
// TODO: Make sure this works with the Grab Mod. I think a good solution would be to check if the player is mounted to something for the death check so players dont die while being held
// TODO: Change logic so that when players get knocked their hearts get set to full and deplete over a 7 second period and you die when the hearts run out, if you're being revived the hearts stop depleting and once you're revived you keep that amount of hearts. Keep an option to switch to the Legacy Mode or the new mode in the config.
// TODO: Make sure vanilla death messages still work properly.
// TODO: Medkit or bandage item that speeds up reviive time and or makes it so the player is always revied at full health.
// TODO: If player is in a party and has the OPAC mod then only notify party members

// 1. Tell Fabric this Mixin modifies the PlayerEntity class
@Mixin(PlayerEntity.class)
public abstract class PlayerDeathMixin {

    // ADD THIS LINE HERE:
    @Unique
    private int reviveTicks = 0;

    @Unique
    private int bleedOutTicks = 0;

    @Unique
    private int letGoTicks = 0;

    @Unique
    private boolean isLettingGo = false;

    @Unique
    private static final net.minecraft.entity.data.TrackedData<Boolean> KNOCKED_STATE =
            net.minecraft.entity.data.DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    @Unique
    private static final net.minecraft.entity.data.TrackedData<Boolean> REVIVING_STATE =
            net.minecraft.entity.data.DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // 2. Inject into the "damage" method
    // "HEAD" means we run this code right at the start of the method, before Minecraft does its math
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void interceptDeath(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {

        // Cast 'this' to the player object so we can check health
        PlayerEntity player = (PlayerEntity) (Object) this;

        if (player.getDataTracker().get(KNOCKED_STATE)) return;

        // We only care about this happening on the server side
        if (player instanceof ServerPlayerEntity) {

            // 3. Check if this damage would kill the player
            float currentHealth = player.getHealth();

            // If the incoming damage is greater than or equal to current health...
            if (amount >= currentHealth) {

                player.getDataTracker().set(KNOCKED_STATE, true);
                this.reviveTicks = 0;
                this.bleedOutTicks = 0;
                // Prevent Death:

                // A. cancel the damage event so Minecraft doesn't kill the player
                cir.setReturnValue(false);

                //B. Set health to a safe number
                player.setHealth(10.0f); // 5 hearts

                // C. Visual feedback (for testing)
                player.sendMessage(Text.of("§cYou are KNOCKED! Wait for help!"), true);
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 980, 5, false, false, true));

                BlockPos knockedPos = player.getBlockPos();
                Text message = Text.literal("")
                        .append(player.getDisplayName())
                        .append(Text.literal(" has been knocked at ["
                                + knockedPos.getX() + ", "
                                + knockedPos.getY() + ", "
                                + knockedPos.getZ() + "]"))
                        .formatted(Formatting.WHITE);

                if (player instanceof ServerPlayerEntity serverPlayer) {
                    MinecraftServer server = serverPlayer.getServer();
                    if (server != null) {
                        server.getPlayerManager().broadcast(message, false);
                    }
                }
            }
        }
    }

    @Inject(method="initDataTracker", at = @At("TAIL"))
    protected void initDataTracker(net.minecraft.entity.data.DataTracker.Builder builder, CallbackInfo ci) {
        builder.add(KNOCKED_STATE, false);
        builder.add(REVIVING_STATE, false);
    }

    @Inject(method = "updatePose", at = @At("HEAD"), cancellable = true)
    private void forceKnockedPose(CallbackInfo ci) {
        PlayerEntity player = (PlayerEntity) (Object) this;

        if (player.getDataTracker().get(KNOCKED_STATE) || player.getDataTracker().get(REVIVING_STATE)) {
            player.setPose(EntityPose.SWIMMING);
            ci.cancel();
        }
    }

    // We need a way to stop being knocked after 7 seconds
    @Inject(method = "tick", at = @At("HEAD"))
    private void handleKnockedTick(CallbackInfo ci) {
        PlayerEntity player = (PlayerEntity) (Object) this;

        boolean isActuallyKnocked = player.getDataTracker().get(KNOCKED_STATE);
        if (isActuallyKnocked) {

            if (!player.getWorld().isClient()) {
                // 3. Search for Rescuers
                java.util.List<PlayerEntity> nearbyPlayers = player.getWorld().getEntitiesByClass(
                        PlayerEntity.class,
                        player.getBoundingBox().expand(3.0),
                        p -> p != player && p.isSneaking()
                );

                if (!nearbyPlayers.isEmpty()) {
                    this.reviveTicks++;
                    player.getDataTracker().set(REVIVING_STATE, true);

                    // Calculate time
                    int secondsPassed = this.reviveTicks / 20;
                    Text progressMessage = Text.of("§eReviving... " + secondsPassed + "s / 7s");

                    // Send a message to the downed player
                    player.sendMessage(progressMessage, true);

                    // Send a message to EVERY helper nearby
                    for (PlayerEntity helper : nearbyPlayers) {
                        helper.sendMessage(progressMessage, true);
                    }

                    if (reviveTicks >= 140) { // 7 seconds
                        player.getDataTracker().set(KNOCKED_STATE, false);
                        player.getDataTracker().set(REVIVING_STATE, false);
                        this.reviveTicks = 0;
                        this.bleedOutTicks = 0;
                        player.setHealth(10.0f); // 5 hearts
                        player.clearStatusEffects();
                        player.sendMessage(Text.of("§aYou were revived!"), true);

                        for (PlayerEntity helper : nearbyPlayers) {
                            helper.sendMessage(Text.of("§aPlayer Revived!"), true);
                        }
                    }
                } else if (player.isSneaking()) {
                    isLettingGo = true;
                    this.letGoTicks++;

                    player.sendMessage(Text.of("§aLetting Go:"), true);

                    if (letGoTicks >= 20) {
                        player.getDataTracker().set(KNOCKED_STATE, false);
                        player.getDataTracker().set(REVIVING_STATE, false);
                        player.setHealth(0.0f);
                        player.onDeath(player.getDamageSources().generic());
                    }
                } else if (!isLettingGo) {
                    // No rescuers found: reset revive progress and turn off the reviving state
                    if (player.getDataTracker().get(REVIVING_STATE)) {
                        player.getDataTracker().set(REVIVING_STATE, false);
                        player.sendMessage(Text.of("§cRevive interrupted!"), true);
                    }

                    this.reviveTicks = 0;
                    this.bleedOutTicks++;

                    int maxBleedTicks = 140; // 7 seconds
                    int secondsLeft = (maxBleedTicks - this.bleedOutTicks) / 20;

                    if (this.bleedOutTicks >= maxBleedTicks) {
                        player.getDataTracker().set(KNOCKED_STATE, false);
                        player.getDataTracker().set(REVIVING_STATE, false);
                        player.setHealth(0.0f);
                        player.onDeath(player.getDamageSources().generic());
                    } else {
                        String color = secondsLeft > 3 ? "§a" : "§c";
                        player.sendMessage(
                                Text.of("§7Bleeding out: " + color + secondsLeft + "s §8| §eCrouch to let go"),
                                true
                        );

                    }
                }
            }
        }
    }
}