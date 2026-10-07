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

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.pcal.fastback.common.logging.UserLogger;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.pcal.fastback.common.commands.Commands.FAILURE;
import static net.pcal.fastback.common.commands.Commands.SUCCESS;
import static net.pcal.fastback.common.commands.Commands.snapshotOp;
import static net.pcal.fastback.common.commands.Commands.subcommandPermission;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.repo.RepoFactory.rf;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;

enum ListCommand implements Command {

    INSTANCE;

    private static final String COMMAND_NAME = "list";

    @Override
    public void register(final LiteralArgumentBuilder<CommandSourceStack> argb, PermissionsFactory<CommandSourceStack> pf) {
        argb.then(
                literal(COMMAND_NAME).
                        requires(subcommandPermission(COMMAND_NAME, pf)).
                        executes(cc -> list(cc, 1, false)).
                        then(argument("page", IntegerArgumentType.integer(1)).
                                executes(cc -> list(cc, IntegerArgumentType.getInteger(cc, "page"), false)))
        );
    }

    static int list(final CommandContext<CommandSourceStack> cc, int page, boolean remote) {
        try (final UserLogger ulog = UserLogger.ulog(cc)) {
            if (!rf().doInitCheck(mod().getWorldDirectory(), ulog)) return FAILURE;
            snapshotOp(remote, ulog, snapshots -> {
                final int maximumPage = SnapshotList.pageCount(snapshots.size());
                if (page > maximumPage) {
                    ulog.message(styledLocalized("fastback.chat.list-invalid-page", ERROR, page, maximumPage));
                    return;
                }
                final Component text = SnapshotList.render(snapshots, page, remote);
                cc.getSource().getServer().execute(() -> {
                    if (cc.getSource().getServer().isRunning()) cc.getSource().sendSuccess(() -> text, false);
                });
            });
        }
        return SUCCESS;
    }

}
