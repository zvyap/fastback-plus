package net.pcal.fastback.common.commands;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.pcal.fastback.common.repo.SnapshotDetails;
import net.pcal.fastback.common.repo.SnapshotListings;

import java.util.List;

import static net.minecraft.ChatFormatting.BOLD;
import static net.minecraft.ChatFormatting.DARK_GRAY;
import static net.minecraft.ChatFormatting.GOLD;
import static net.minecraft.ChatFormatting.GRAY;
import static net.minecraft.ChatFormatting.GREEN;
import static net.minecraft.ChatFormatting.YELLOW;
import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.mod.UserMessageUtil.messageToText;

/** Chat presentation shared by local and remote backup lists. */
final class SnapshotList {

    static final int PAGE_SIZE = SnapshotListings.PAGE_SIZE;

    static int pageCount(int total) {
        return total == 0 ? 1 : 1 + (total - 1) / PAGE_SIZE;
    }

    /** Page entries must already be ordered newest first by the shared cache. */
    static Component render(List<SnapshotDetails> entries, int page, int total, boolean remote) {
        final int maxPage = pageCount(total);
        if (page < 1 || page > maxPage) throw new IllegalArgumentException("Invalid backup list page: " + page);
        final MutableComponent result = messageToText(localized(remote ? "fastback.chat.remote-list-title" : "fastback.chat.list-title", total)).copy()
                .withStyle(GOLD, BOLD);
        result.append("\n  ").append(messageToText(localized("fastback.chat.list-columns")).copy()
                .withStyle(style -> style.withColor(GRAY).withBold(false)));
        for (final SnapshotDetails details : entries) {
            final String id = details.id().getShortName();
            final Component hover = SnapshotPresentation.details(details, remote).copy()
                    .append("\n\n  ").append(messageToText(localized("fastback.chat.list-copy-hint")).copy().withStyle(GREEN));
            final Component entry = Component.literal("  ").withStyle(DARK_GRAY)
                    .append(Component.literal(id).withStyle(YELLOW))
                    .append(Component.literal(" | ").withStyle(DARK_GRAY))
                    .append(SnapshotPresentation.creatorText(details))
                    .append(Component.literal(" | ").withStyle(DARK_GRAY))
                    .append(SnapshotPresentation.remarkText(details, remote))
                    .withStyle(style -> style.withBold(false)
                            .withClickEvent(new ClickEvent.CopyToClipboard(id))
                            .withHoverEvent(new HoverEvent.ShowText(hover)));
            result.append("\n").append(entry);
        }
        if (total == 0) result.append("\n  ").append(messageToText(localized("fastback.chat.list-empty")).copy().withStyle(style -> style.withColor(GRAY).withBold(false)));
        final String command = remote ? "/backup remote-list " : "/backup list ";
        return result.append("\n  ")
                .append(navigation("fastback.chat.list-previous", command, page - 1, page > 1))
                .append(" ")
                .append(messageToText(localized("fastback.chat.list-page", page, maxPage)).copy().withStyle(style -> style.withColor(GOLD).withBold(false)))
                .append(" ")
                .append(navigation("fastback.chat.list-next", command, page + 1, page < maxPage));
    }

    private static Component navigation(String key, String command, int page, boolean enabled) {
        final MutableComponent text = messageToText(localized(key)).copy().withStyle(style -> style.withColor(enabled ? GREEN : GRAY).withBold(false));
        return enabled ? text.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command + page))) : text;
    }

    private SnapshotList() {}
}
