package net.pcal.fastback.common.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/** A serializable confirmation flag that clients do not suggest. */
final class ConfirmationArgument {

    static <S> RequiredArgumentBuilder<S, String> hidden(Command<S> command) {
        return RequiredArgumentBuilder.<S, String>argument("confirm", StringArgumentType.word())
                .suggests((context, builder) -> builder.buildFuture())
                .executes(context -> {
                    if (!"confirm".equals(StringArgumentType.getString(context, "confirm"))) {
                        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.literalIncorrect().create("confirm");
                    }
                    return command.run(context);
                });
    }

    private ConfirmationArgument() {}
}
