package net.pcal.fastback.common.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.mixins.ProfileCacheAccessors;
import net.pcal.fastback.common.repo.SnapshotDetails;
import net.pcal.fastback.common.repo.SnapshotListings;
import net.pcal.fastback.common.utils.RollbackTarget;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import static net.minecraft.ChatFormatting.GOLD;
import static net.minecraft.ChatFormatting.GREEN;
import static net.minecraft.ChatFormatting.RED;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.pcal.fastback.common.commands.Commands.SUCCESS;
import static net.pcal.fastback.common.commands.Commands.gitOp;
import static net.pcal.fastback.common.commands.Commands.snapshotOp;
import static net.pcal.fastback.common.commands.Commands.subcommandPermission;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.mod.UserMessageUtil.messageToText;
import static net.pcal.fastback.common.utils.Executor.ExecutionLock.WRITE;
import static net.pcal.fastback.common.utils.RollbackTarget.Kind;

enum RollbackCommand implements Command {
    INSTANCE;

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root, PermissionsFactory<CommandSourceStack> pf) {
        var snapshot = SnapshotArgument.local("snapshot");
        for (Kind kind : new Kind[]{Kind.REGION, Kind.CHUNK, Kind.DIMENSION}) {
            RequiredArgumentBuilder<CommandSourceStack, ?> selection = argument("dimension", DimensionArgument.dimension());
            if (kind == Kind.DIMENSION) {
                selection.executes(cc -> confirm(cc, target(cc, kind)));
                selection.then(ConfirmationArgument.hidden(cc -> rollback(cc, target(cc, kind))));
            } else {
                // Try XYZ first: the legacy X/Z branch also parses a third word as its hidden confirmation.
                selection.then(argument("pos", BlockPosArgument.blockPos())
                        .executes(cc -> confirm(cc, blockTarget(cc, kind)))
                        .then(ConfirmationArgument.hidden(cc -> rollback(cc, blockTarget(cc, kind)))));
                var coordinates = kind == Kind.REGION
                        ? IntegerArgumentType.integer(Integer.MIN_VALUE / 32, Integer.MAX_VALUE / 32)
                        : IntegerArgumentType.integer();
                selection.then(argument("x", coordinates).then(argument("z", coordinates)
                        .executes(cc -> confirm(cc, target(cc, kind)))
                        .then(ConfirmationArgument.hidden(cc -> rollback(cc, target(cc, kind))))));
            }
            snapshot.then(literal(kind == Kind.REGION ? "region" : kind == Kind.CHUNK ? "chunk" : "dimension").then(selection));
        }
        snapshot.then(literal("player").then(argument("player", StringArgumentType.word())
                .suggests((cc, builder) -> SharedSuggestionProvider.suggest(knownPlayers(cc.getSource()).values()
                        .stream().map(NameAndId::name), builder))
                .executes(cc -> {
                    final String name = StringArgumentType.getString(cc, "player");
                    final var player = knownPlayers(cc.getSource()).get(name);
                    if (player == null) throw new SimpleCommandExceptionType(messageToText(
                            localized("fastback.chat.rollback-player-unknown", name))).create();
                    return confirm(cc, new RollbackTarget(Kind.PLAYER, null, 0, 0, player.id()));
                })
                // Pin the previewed UUID in the button, even if the player disconnects before confirmation.
                .then(argument("uuid", UuidArgument.uuid()).suggests((cc, builder) -> builder.buildFuture())
                        .then(ConfirmationArgument.hidden(cc -> rollback(cc,
                                new RollbackTarget(Kind.PLAYER, null, 0, 0, UuidArgument.getUuid(cc, "uuid"))))))));
        root.then(literal("rollback")
                .requires(subcommandPermission("rollback", pf).and(source -> source.getServer().isDedicatedServer()))
                .then(snapshot));
    }

    private static RollbackTarget target(CommandContext<CommandSourceStack> cc, Kind kind) throws CommandSyntaxException {
        return new RollbackTarget(kind, DimensionArgument.getDimension(cc, "dimension").dimension().identifier(),
                kind == Kind.DIMENSION ? 0 : IntegerArgumentType.getInteger(cc, "x"),
                kind == Kind.DIMENSION ? 0 : IntegerArgumentType.getInteger(cc, "z"), null);
    }

    private static RollbackTarget blockTarget(CommandContext<CommandSourceStack> cc, Kind kind) throws CommandSyntaxException {
        return RollbackTarget.atBlock(kind, DimensionArgument.getDimension(cc, "dimension").dimension().identifier(),
                BlockPosArgument.getBlockPos(cc, "pos"));
    }

    private static Map<String, NameAndId> knownPlayers(CommandSourceStack source) {
        final var resolver = source.getServer().services().nameToIdCache();
        final var cached = resolver instanceof ProfileCacheAccessors access ? access : null;
        final var online = source.getServer().getPlayerList().getPlayers().stream()
                .map(player -> new NameAndId(player.getGameProfile())).toList();
        return knownPlayers(cached, online);
    }

    static Map<String, NameAndId> knownPlayers(ProfileCacheAccessors cached, Collection<NameAndId> online) {
        final Map<String, NameAndId> players = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (cached != null) cached.fastback$profilesByName().values().forEach(entry -> {
            final var player = ((ProfileCacheAccessors.Entry) entry).fastback$nameAndId();
            players.put(player.name(), player);
        });
        online.forEach(player -> players.put(player.name(), player));
        return players;
    }

    private static int confirm(CommandContext<CommandSourceStack> cc, RollbackTarget target) {
        final UserLogger log = UserLogger.ulog(cc);
        final var source = cc.getSource();
        final String name = target.kind() == Kind.PLAYER ? StringArgumentType.getString(cc, "player") : null;
        snapshotOp(SnapshotListings.details(mod().getWorldDirectory(), false, StringArgumentType.getString(cc, "snapshot")), log, details -> {
            if (details == null) {
                log.message(styledLocalized("fastback.chat.restore-nosuch", ERROR, StringArgumentType.getString(cc, "snapshot")));
                return;
            }
            final Component text = confirmation(details, target, name);
            source.getServer().execute(() -> {
                if (source.getServer().isRunning()) source.sendSuccess(() -> text, false);
            });
        });
        return SUCCESS;
    }

    static Component confirmation(SnapshotDetails details, RollbackTarget target, String playerName) {
        final String selection = switch (target.kind()) {
            case PLAYER -> "player " + playerName + " " + target.playerId();
            case DIMENSION -> "dimension " + target.dimension();
            case REGION, CHUNK -> (target.kind() == Kind.REGION ? "region " : "chunk ")
                    + target.dimension() + " " + target.x() + " " + target.z();
        };
        final String command = "/backup rollback " + StringArgumentType.escapeIfRequired(details.id().getShortName())
                + " " + selection + " confirm";
        final var text = Component.empty()
                .append(messageToText(localized("fastback.chat.rollback-confirm")).copy().withStyle(GOLD))
                .append("\n").append(SnapshotPresentation.details(details, false));
        if (target.kind() == Kind.PLAYER) {
            text.append("\n  ").append(messageToText(localized("fastback.chat.rollback-player", playerName, target.playerId())));
        } else {
            text.append("\n  ").append(messageToText(localized("fastback.chat.rollback-dimension", target.dimension())));
            if (target.kind() == Kind.DIMENSION) {
                text.append("\n  ").append(messageToText(localized("fastback.chat.rollback-entire-dimension")));
            } else {
                text.append("\n  ").append(messageToText(localized("fastback.chat.rollback-region", target.regionX(), target.regionZ())));
                text.append("\n  ").append(messageToText(localized("fastback.chat.rollback-chunks",
                        target.minChunkX(), target.minChunkZ(), target.maxChunkX(), target.maxChunkZ())));
                text.append("\n  ").append(messageToText(localized("fastback.chat.rollback-blocks",
                        target.minChunkX() * 16, target.minChunkZ() * 16,
                        target.maxChunkX() * 16 + 15, target.maxChunkZ() * 16 + 15)));
            }
        }
        return text.append("\n  ").append(messageToText(localized("fastback.chat.rollback-warning")).copy().withStyle(RED))
                .append("\n  ").append(messageToText(localized("fastback.chat.rollback-confirm-button")).copy()
                        .withStyle(style -> style.withColor(GREEN).withBold(true).withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(command))));
    }

    private static int rollback(CommandContext<CommandSourceStack> cc, RollbackTarget target) {
        final UserLogger log = UserLogger.ulog(cc);
        gitOp(WRITE, log, repo -> repo.doLoadSnapshot(StringArgumentType.getString(cc, "snapshot"), false, target, log));
        return SUCCESS;
    }
}
