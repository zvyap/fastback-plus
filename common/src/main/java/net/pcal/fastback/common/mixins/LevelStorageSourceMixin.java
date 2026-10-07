package net.pcal.fastback.common.mixins;

import net.minecraft.world.level.storage.LevelStorageSource;
import net.pcal.fastback.common.utils.ServerWorldRestore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;

/** Check interrupted swaps before Minecraft can rotate or rewrite level.dat during startup. */
@Mixin(LevelStorageSource.class)
public class LevelStorageSourceMixin {

    @Inject(at = @At("HEAD"), method = {"createAccess", "validateAndCreateAccess"}, remap = false)
    private void fastback_checkPendingRestore(String levelName,
            CallbackInfoReturnable<LevelStorageSource.LevelStorageAccess> ci) throws IOException {
        ServerWorldRestore.checkForIncompleteRestore(((LevelStorageSource) (Object) this).getLevelPath(levelName));
    }
}
