package net.pcal.fastback.common.mixins;

import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Read cached identities without the resolver's network lookup or offline UUID generation. */
@Mixin(CachedUserNameToIdResolver.class)
public interface ProfileCacheAccessors {
    @Accessor(value = "profilesByName", remap = false)
    Map<String, ?> fastback$profilesByName();

    @Mixin(targets = "net.minecraft.server.players.CachedUserNameToIdResolver$GameProfileInfo")
    interface Entry {
        @Accessor(value = "nameAndId", remap = false)
        NameAndId fastback$nameAndId();
    }
}
