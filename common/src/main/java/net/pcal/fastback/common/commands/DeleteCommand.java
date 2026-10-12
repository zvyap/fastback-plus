/*
 * FastBack - Fast, incremental Minecraft backups powered by Git.
 * Copyright (C) 2022 pcal.net
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; If not, see <http://www.gnu.org/licenses/>.
 */

package net.pcal.fastback.common.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.logging.UserMessage;
import net.pcal.fastback.common.repo.SnapshotDetails;
import net.pcal.fastback.common.repo.SnapshotId;
import net.pcal.fastback.common.repo.SnapshotListings;

import java.util.List;

import static net.minecraft.ChatFormatting.GOLD;
import static net.minecraft.ChatFormatting.GRAY;
import static net.minecraft.ChatFormatting.RED;
import static net.minecraft.commands.Commands.literal;
import static net.pcal.fastback.common.commands.Commands.SUCCESS;
import static net.pcal.fastback.common.commands.Commands.getArgumentNicely;
import static net.pcal.fastback.common.commands.Commands.gitOp;
import static net.pcal.fastback.common.commands.Commands.snapshotOp;
import static net.pcal.fastback.common.commands.Commands.subcommandPermission;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.logging.UserLogger.ulog;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.mod.UserMessageUtil.messageToText;
import static net.pcal.fastback.common.utils.Executor.ExecutionLock.WRITE;

enum DeleteCommand implements Command {

    INSTANCE;

    private static final String COMMAND_NAME = "delete";
    private static final String ARGUMENT = "snapshot";

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> argb, PermissionsFactory<CommandSourceStack> pf) {
        argb.then(literal(COMMAND_NAME).
                requires(subcommandPermission(COMMAND_NAME, pf)).then(
                        SnapshotArgument.local(ARGUMENT).
                                executes(context -> confirm(context, false))
                                .then(ConfirmationArgument.hidden(DeleteCommand::delete))
                )
        );
    }

    static int confirm(CommandContext<CommandSourceStack> context, boolean remote) {
        final UserLogger log = ulog(context);
        final CommandSourceStack source = context.getSource();
        final String snapshot = StringArgumentType.getString(context, ARGUMENT);
        snapshotOp(SnapshotListings.details(mod().getWorldDirectory(), remote, snapshot), log, details -> {
            if (details == null) {
                source.getServer().execute(() -> log.message(styledLocalized("fastback.chat.restore-nosuch", ERROR, snapshot)));
                return;
            }
            final Component text = confirmation(details, remote);
            source.getServer().execute(() -> {
                if (source.getServer().isRunning()) source.sendSuccess(() -> text, false);
            });
        });
        return SUCCESS;
    }

    static Component confirmation(SnapshotDetails details, boolean remote) {
        final String confirmCommand = "/backup " + (remote ? "remote-delete" : "delete") + " "
                + StringArgumentType.escapeIfRequired(details.id().getShortName()) + " confirm";
        final Component button = messageToText(localized("fastback.chat.delete-confirm-button")).copy()
                .withStyle(style -> style.withColor(RED).withBold(true).withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(confirmCommand)));
        return Component.empty().append(messageToText(localized("fastback.chat.delete-confirm",
                        messageToText(localized(remote ? "fastback.values.remote" : "fastback.values.local")))).copy()
                        .withStyle(GOLD).withStyle(style -> style.withBold(true)))
                .append("\n").append(SnapshotPresentation.details(details, remote))
                .append("\n  ").append(messageToText(localized("fastback.chat.delete-warning")).copy().withStyle(RED))
                .append("\n  ").append(button).append("  ")
                .append(messageToText(localized("fastback.chat.delete-cancel-hint")).copy().withStyle(GRAY));
    }

    private static int delete(final CommandContext<CommandSourceStack> cc) {
        final UserLogger log = ulog(cc);
        gitOp(WRITE, log, repo -> {
            final String snapshotName = getArgumentNicely(ARGUMENT, String.class, cc.getLastChild(), log);
            final SnapshotId sid = repo.createSnapshotId(snapshotName);
            final String branchName = sid.getBranchName();
            repo.deleteLocalBranches(List.of(branchName));
            log.message(UserMessage.localized("fastback.chat.delete-done", snapshotName));
        });
        return SUCCESS;
    }
}
