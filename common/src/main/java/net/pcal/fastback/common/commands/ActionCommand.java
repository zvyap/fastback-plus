package net.pcal.fastback.common.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.pcal.fastback.common.config.ActionConfig;
import net.pcal.fastback.common.config.ActionRule;
import net.pcal.fastback.common.config.ActionRule.Event;
import net.pcal.fastback.common.logging.UserLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static net.minecraft.ChatFormatting.DARK_GRAY;
import static net.minecraft.ChatFormatting.GOLD;
import static net.minecraft.ChatFormatting.GRAY;
import static net.minecraft.ChatFormatting.GREEN;
import static net.minecraft.ChatFormatting.YELLOW;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.pcal.fastback.common.commands.Commands.FAILURE;
import static net.pcal.fastback.common.commands.Commands.SUCCESS;
import static net.pcal.fastback.common.commands.Commands.gitOp;
import static net.pcal.fastback.common.commands.Commands.subcommandPermission;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.ERROR;
import static net.pcal.fastback.common.logging.UserMessage.UserMessageStyle.WARNING;
import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.logging.UserMessage.styledLocalized;
import static net.pcal.fastback.common.mod.UserMessageUtil.messageToText;
import static net.pcal.fastback.common.mod.Mod.mod;
import static net.pcal.fastback.common.utils.Executor.ExecutionLock.NONE;
import static net.pcal.fastback.common.utils.Executor.ExecutionLock.WRITE;

/** Edits the world's automatic actions without exposing legacy single-action settings. */
enum ActionCommand implements Command {
    INSTANCE;

    private static final long CONFIRMATION_LIFETIME = TimeUnit.MINUTES.toNanos(5);
    private static final Map<ConfirmationKey, PendingRemoval> PENDING_REMOVALS = new ConcurrentHashMap<>();
    private record ConfirmationKey(Path world, String issuer) {}
    private record PendingRemoval(ActionRule rule, long createdAt) {}

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root, PermissionsFactory<CommandSourceStack> pf) {
        root.then(literal("action").requires(subcommandPermission("action", pf))
                .executes(context -> error(context, "fastback.chat.action-subcommand"))
                .then(literal("list").requires(subcommandPermission("action.list", pf))
                        .executes(context -> list(context, 1, pf))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> list(context, IntegerArgumentType.getInteger(context, "page"), pf))))
                .then(literal("add").requires(subcommandPermission("action.add", pf))
                        .executes(context -> error(context, "fastback.chat.action-usage"))
                        .then(argument("arguments", StringArgumentType.greedyString())
                                .suggests(ActionCommand::suggestAdd).executes(ActionCommand::add)))
                .then(literal("remove").requires(subcommandPermission("action.remove", pf))
                        .executes(context -> error(context, "fastback.chat.action-remove-usage"))
                        .then(argument("event", StringArgumentType.word()).suggests(ActionCommand::suggestRemoveEvent)
                                .executes(context -> error(context, "fastback.chat.action-remove-usage"))
                                .then(argument("action", StringArgumentType.word()).suggests(ActionCommand::suggestRemoveAction)
                                        .executes(context -> remove(context, false))
                                        .then(ConfirmationArgument.hidden(context -> remove(context, true)))))));
    }

    static ActionRule parseAdd(String input) {
        final String[] tokens = input.strip().split("\\s+");
        if (tokens.length < 2) throw new IllegalArgumentException("fastback.chat.action-usage");
        final SchedulableAction action = SchedulableAction.forConfigValue(tokens[1]);
        if (action == null) throw new IllegalArgumentException("fastback.chat.action-invalid-action");
        final ActionRule base;
        try {
            base = ActionRule.parse(tokens[0], action, 0);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("fastback.chat.action-invalid-event");
        }
        if (base.event() == Event.SHUTDOWN && tokens.length > 2) {
            throw new IllegalArgumentException("fastback.chat.action-shutdown-max");
        }
        if (tokens.length > 3) throw new IllegalArgumentException("fastback.chat.action-usage");
        int max = 0;
        if (tokens.length == 3) {
            try {
                max = Integer.parseInt(tokens[2]);
                if (max < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("fastback.chat.action-invalid-max");
            }
        }
        return ActionRule.parse(tokens[0], action, max);
    }

    private static int add(CommandContext<CommandSourceStack> context) {
        final ActionRule rule;
        try {
            rule = parseAdd(StringArgumentType.getString(context, "arguments"));
        } catch (IllegalArgumentException e) {
            return error(context, e.getMessage());
        }
        if (rule.event() == Event.EMPTY && !context.getSource().getServer().isDedicatedServer()) {
            context.getSource().sendSuccess(() -> messageToText(styledLocalized("fastback.chat.action-empty-singleplayer", WARNING))
                    .copy().withStyle(GOLD), false);
            return FAILURE;
        }
        final UserLogger log = UserLogger.ulog(context);
        gitOp(WRITE, log, false, repo -> {
            final List<ActionRule> rules = new ArrayList<>(ActionConfig.load(repo.getConfig()));
            if (rules.stream().anyMatch(existing -> existing.event() == rule.event() && existing.action() == rule.action())) {
                log.message(styledLocalized("fastback.chat.action-duplicate", ERROR, rule.event(), rule.action().getArgumentName()));
                return;
            }
            rules.add(rule);
            ActionConfig.save(repo.getConfig(), rules);
            mod().onActionsChanged(rules);
            log.message(localized("fastback.chat.action-added", description(rule)));
        });
        return SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> context, int page, PermissionsFactory<CommandSourceStack> pf) {
        final UserLogger log = UserLogger.ulog(context);
        final boolean canRemove = subcommandPermission("action.remove", pf).test(context.getSource());
        gitOp(NONE, log, false, repo -> {
            final List<ActionRule> rules = ActionConfig.load(repo.getConfig());
            final int maxPage = SnapshotList.pageCount(rules.size());
            if (page > maxPage) {
                log.message(styledLocalized("fastback.chat.list-invalid-page", ERROR, page, maxPage));
                return;
            }
            final MutableComponent text = SnapshotList.header("fastback.chat.action-list-title", "fastback.chat.action-list-columns", rules.size());
            final int from = (page - 1) * SnapshotList.PAGE_SIZE;
            for (final ActionRule rule : rules.subList(from, Math.min(from + SnapshotList.PAGE_SIZE, rules.size()))) {
                final MutableComponent row = Component.literal("  ").withStyle(DARK_GRAY)
                        .append(Component.literal(rule.eventArgument()).withStyle(YELLOW))
                        .append(SnapshotList.separator())
                        .append(Component.literal(rule.action().getArgumentName()).withStyle(GRAY))
                        .append(SnapshotList.separator())
                        .append(maxText(rule))
                        .withStyle(style -> style.withBold(false));
                if (canRemove) {
                    row.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(removeCommand(rule)))
                            .withHoverEvent(new HoverEvent.ShowText(messageToText(localized("fastback.chat.action-remove-hint")))));
                }
                text.append("\n").append(row);
            }
            if (rules.isEmpty()) text.append("\n  ").append(messageToText(localized("fastback.chat.action-list-empty")).copy()
                    .withStyle(style -> style.withColor(GRAY).withBold(false)));
            send(context, SnapshotList.footer(text, page, rules.size(), "/backup action list "));
        });
        return SUCCESS;
    }

    private static int remove(CommandContext<CommandSourceStack> context, boolean confirmed) {
        final Event event;
        final SchedulableAction action = SchedulableAction.forConfigValue(StringArgumentType.getString(context, "action"));
        try {
            event = Event.valueOf(StringArgumentType.getString(context, "event"));
        } catch (IllegalArgumentException e) {
            return error(context, "fastback.chat.action-remove-usage");
        }
        if (action == null) return error(context, "fastback.chat.action-invalid-action");
        final UserLogger log = UserLogger.ulog(context);
        final CommandSourceStack source = context.getSource();
        final ConfirmationKey key = new ConfirmationKey(mod().getWorldDirectory(), source.getEntity() == null
                ? "source:" + source.getTextName() : "entity:" + source.getEntity().getUUID());
        final long now = System.nanoTime();
        PENDING_REMOVALS.entrySet().removeIf(entry -> now - entry.getValue().createdAt() > CONFIRMATION_LIFETIME);
        gitOp(confirmed ? WRITE : NONE, log, false, repo -> {
            final List<ActionRule> rules = new ArrayList<>(ActionConfig.load(repo.getConfig()));
            final ActionRule rule = rules.stream().filter(candidate -> candidate.event() == event && candidate.action() == action).findFirst().orElse(null);
            if (rule == null) {
                PENDING_REMOVALS.remove(key);
                log.message(styledLocalized("fastback.chat.action-not-found", ERROR, event, action.getArgumentName()));
                return;
            }
            if (!confirmed) {
                PENDING_REMOVALS.put(key, new PendingRemoval(rule, System.nanoTime()));
                final Component button = messageToText(localized("fastback.chat.action-remove-confirm-button")).copy()
                        .withStyle(style -> style.withColor(GREEN).withBold(true).withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(removeCommand(rule) + " confirm")));
                send(context, messageToText(localized("fastback.chat.action-remove-confirm", description(rule))).copy().withStyle(GOLD)
                        .append("\n  ").append(button).append("  ")
                        .append(messageToText(localized("fastback.chat.delete-cancel-hint")).copy().withStyle(GRAY)));
                return;
            }
            final PendingRemoval pending = PENDING_REMOVALS.remove(key);
            if (pending == null || !pending.rule().equals(rule) || System.nanoTime() - pending.createdAt() > CONFIRMATION_LIFETIME) {
                log.message(styledLocalized("fastback.chat.action-remove-stale", ERROR));
                return;
            }
            rules.remove(rule);
            ActionConfig.save(repo.getConfig(), rules);
            mod().onActionsChanged(rules);
            log.message(localized("fastback.chat.action-removed", description(rule)));
        });
        return SUCCESS;
    }

    private static Component maxText(ActionRule rule) {
        return rule.event() == Event.SHUTDOWN ? Component.literal("-").withStyle(GRAY)
                : rule.max() == 0 ? messageToText(localized("fastback.values.unlimited")).copy().withStyle(GRAY)
                : Component.literal(Integer.toString(rule.max())).withStyle(GRAY);
    }

    static String description(ActionRule rule) {
        return rule.eventArgument() + " " + rule.action().getArgumentName()
                + (rule.event() == Event.SHUTDOWN ? "" : " " + rule.max());
    }

    private static String removeCommand(ActionRule rule) {
        return "/backup action remove " + rule.event() + " " + rule.action().getArgumentName();
    }

    private static void send(CommandContext<CommandSourceStack> context, Component text) {
        context.getSource().getServer().execute(() -> {
            if (context.getSource().getServer().isRunning()) context.getSource().sendSuccess(() -> text, false);
        });
    }

    private static int error(CommandContext<CommandSourceStack> context, String key) {
        try (final UserLogger log = UserLogger.ulog(context)) {
            log.message(styledLocalized(key, ERROR));
        }
        return FAILURE;
    }

    private static CompletableFuture<Suggestions> suggestAdd(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return suggestAdd(context.getSource().getServer().isDedicatedServer(), builder);
    }

    static CompletableFuture<Suggestions> suggestAdd(boolean dedicated, SuggestionsBuilder builder) {
        final String remaining = builder.getRemaining();
        final int offset = remaining.lastIndexOf(' ') + 1;
        final String prefix = remaining.substring(0, offset).strip();
        final String[] preceding = prefix.isEmpty() ? new String[0] : prefix.split("\\s+");
        final SuggestionsBuilder tokenBuilder = builder.createOffset(builder.getStart() + offset);
        final List<String> choices = new ArrayList<>();
        if (preceding.length == 0) {
            choices.addAll(List.of("SHUTDOWN", "INTERVAL", "INTERVAL[30m]", "SCHEDULED", "SCHEDULED[00:00]"));
            if (dedicated) choices.add("EMPTY");
        } else if (preceding.length == 1) {
            try {
                ActionRule.parse(preceding[0], SchedulableAction.LOCAL, 0);
                Arrays.stream(SchedulableAction.values()).map(SchedulableAction::getArgumentName).forEach(choices::add);
            } catch (IllegalArgumentException ignored) {}
        } else if (preceding.length == 2) {
            try {
                if (parseAdd(prefix).event() != Event.SHUTDOWN) choices.add("0");
            } catch (IllegalArgumentException ignored) {}
        }
        choices.stream().filter(choice -> choice.startsWith(tokenBuilder.getRemaining())).forEach(tokenBuilder::suggest);
        return tokenBuilder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestRemoveEvent(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Arrays.stream(Event.values()).filter(event -> event != Event.EMPTY || context.getSource().getServer().isDedicatedServer())
                .map(Enum::name).filter(name -> name.startsWith(builder.getRemaining())).forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestRemoveAction(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Arrays.stream(SchedulableAction.values()).map(SchedulableAction::getArgumentName)
                .filter(name -> name.startsWith(builder.getRemaining())).forEach(builder::suggest);
        return builder.buildFuture();
    }
}
