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
import net.minecraft.network.chat.Component;
import net.pcal.fastback.common.logging.UserLogger;
import net.pcal.fastback.common.repo.SnapshotListings;

import static net.minecraft.commands.Commands.literal;
import static net.pcal.fastback.common.commands.Commands.SUCCESS;
import static net.pcal.fastback.common.commands.Commands.snapshotOp;
import static net.pcal.fastback.common.commands.Commands.missingArgument;
import static net.pcal.fastback.common.commands.Commands.subcommandPermission;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.mod.Mod.mod;

enum ViewCommand implements Command {

    INSTANCE;

    private static final String COMMAND_NAME = "view";
    private static final String ARGUMENT = "snapshot";

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root, PermissionsFactory<CommandSourceStack> pf) {
        root.then(literal(COMMAND_NAME)
                .requires(subcommandPermission(COMMAND_NAME, pf))
                .executes(cc -> missingArgument(ARGUMENT, cc))
                .then(SnapshotArgument.local(ARGUMENT).executes(ViewCommand::view)));
    }

    private static int view(CommandContext<CommandSourceStack> cc) {
        try (final UserLogger ulog = UserLogger.ulog(cc)) {
            final String snapshot = StringArgumentType.getString(cc, ARGUMENT);
            snapshotOp(SnapshotListings.details(mod().getWorldDirectory(), false, snapshot), ulog, details -> {
                if (details == null) {
                    ulog.message(styledLocalized("fastback.chat.restore-nosuch", ERROR, snapshot));
                    return;
                }
                final Component text = SnapshotPresentation.details(details, false);
                cc.getSource().getServer().execute(() -> {
                    if (cc.getSource().getServer().isRunning()) cc.getSource().sendSuccess(() -> text, false);
                });
            });
        }
        return SUCCESS;
    }
}
