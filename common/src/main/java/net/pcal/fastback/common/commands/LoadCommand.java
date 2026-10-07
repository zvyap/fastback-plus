package net.pcal.fastback.common.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.pcal.fastback.common.logging.UserLogger;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.pcal.fastback.common.commands.Commands.FAILURE;
import static net.pcal.fastback.common.commands.Commands.SUCCESS;
import static net.pcal.fastback.common.commands.Commands.gitOp;
import static net.pcal.fastback.common.commands.Commands.subcommandPermission;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.WARNING;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.utils.Executor.ExecutionLock.WRITE;

/** Loads a snapshot into a dedicated server's world after a clean shutdown. */
enum LoadCommand implements Command {

    LOCAL("load", false),
    REMOTE("remote-load", true);

    private final String commandName;
    private final boolean remote;

    LoadCommand(String commandName, boolean remote) {
        this.commandName = commandName;
        this.remote = remote;
    }

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root, PermissionsFactory<CommandSourceStack> pf) {
        root.then(literal(commandName)
                .requires(subcommandPermission(commandName, pf).and(source -> source.getServer().isDedicatedServer()))
                .then(argument("snapshot", StringArgumentType.string())
                        .suggests(remote ? SnapshotNameSuggestions.remote() : SnapshotNameSuggestions.local())
                        .executes(this::confirm)
                        .then(literal("confirm").executes(this::load))));
    }

    private int confirm(CommandContext<CommandSourceStack> context) {
        try (final UserLogger ulog = UserLogger.ulog(context)) {
            ulog.message(styledLocalized("fastback.chat.load-confirm", WARNING, commandName,
                    StringArgumentType.getString(context, "snapshot")));
        }
        return FAILURE;
    }

    private int load(CommandContext<CommandSourceStack> context) {
        final UserLogger ulog = UserLogger.ulog(context);
        final String snapshot = StringArgumentType.getString(context, "snapshot");
        gitOp(WRITE, ulog, repo -> repo.doLoadSnapshot(snapshot, remote, ulog));
        return SUCCESS;
    }
}
