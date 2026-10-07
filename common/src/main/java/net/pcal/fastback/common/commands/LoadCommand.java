package net.pcal.fastback.common.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.repo.SnapshotId;

import java.text.ParseException;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import static net.minecraft.ChatFormatting.GREEN;
import static net.minecraft.commands.Commands.literal;
import static net.pcal.fastback.common.commands.Commands.SUCCESS;
import static net.pcal.fastback.common.commands.Commands.gitOp;
import static net.pcal.fastback.common.commands.Commands.subcommandPermission;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.WARNING;
import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.mod.UserMessageUtil.messageToText;
import static net.pcal.fastback.common.utils.Executor.ExecutionLock.NONE;
import static net.pcal.fastback.common.utils.Executor.ExecutionLock.WRITE;

/** Loads a snapshot into a dedicated server's world after a clean shutdown and restarts it. */
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
                .then((remote ? SnapshotArgument.remote("snapshot") : SnapshotArgument.local("snapshot"))
                        .executes(this::confirm)
                        .then(literal("confirm").executes(this::load))));
    }

    private int confirm(CommandContext<CommandSourceStack> context) {
        final UserLogger ulog = UserLogger.ulog(context);
        final CommandSourceStack source = context.getSource();
        final String snapshot = StringArgumentType.getString(context, "snapshot");
        gitOp(NONE, ulog, repo -> {
            final SnapshotId requested;
            try {
                requested = repo.createSnapshotId(snapshot);
            } catch (ParseException e) {
                source.getServer().execute(() -> ulog.message(styledLocalized("fastback.chat.restore-nosuch", ERROR, snapshot)));
                return;
            }
            final SnapshotId existing = findSnapshot(requested, remote ? repo.getRemoteSnapshots() : repo.getLocalSnapshots());
            if (existing == null) {
                source.getServer().execute(() -> ulog.message(styledLocalized("fastback.chat.restore-nosuch", ERROR, snapshot)));
                return;
            }
            final Component details = confirmation(existing);
            source.getServer().execute(() -> source.sendSuccess(() -> details, false));
        });
        return SUCCESS;
    }

    static SnapshotId findSnapshot(SnapshotId requested, Set<SnapshotId> snapshots) {
        return snapshots.stream().filter(sid -> sid.getBranchName().equals(requested.getBranchName())).findFirst().orElse(null);
    }

    Component confirmation(SnapshotId snapshot) {
        final String confirmCommand = "/backup " + commandName + " " + StringArgumentType.escapeIfRequired(snapshot.getShortName()) + " confirm";
        final Component button = messageToText(localized("fastback.chat.load-confirm-button")).copy()
                .withStyle(style -> style.withColor(GREEN).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(confirmCommand)));
        return messageToText(styledLocalized("fastback.chat.load-confirm", WARNING,
                snapshot.getShortName(), DateTimeFormatter.ISO_INSTANT.format(snapshot.getDate().toInstant()),
                snapshot.getWorldId().toString(), messageToText(localized(remote ? "fastback.values.remote" : "fastback.values.local")),
                confirmCommand)).copy().append("\n").append(button);
    }

    private int load(CommandContext<CommandSourceStack> context) {
        final UserLogger ulog = UserLogger.ulog(context);
        final String snapshot = StringArgumentType.getString(context, "snapshot");
        gitOp(WRITE, ulog, repo -> repo.doLoadSnapshot(snapshot, remote, ulog));
        return SUCCESS;
    }
}
