package net.pcal.fastback.common.commands;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.pcal.fastback.common.repo.SnapshotDetails;

import java.util.List;

import static net.minecraft.ChatFormatting.GOLD;
import static net.minecraft.ChatFormatting.GRAY;
import static net.minecraft.ChatFormatting.GREEN;
import static net.minecraft.ChatFormatting.YELLOW;
import static net.pcal.fastback.common.logging.UserMessage.localized;
import static net.pcal.fastback.common.mod.UserMessageUtil.messageToText;

/** Chat presentation shared by local and remote backup lists. */
final class SnapshotList {

    static final int PAGE_SIZE = 8;

    static int pageCount(int total) {
        return total == 0 ? 1 : 1 + (total - 1) / PAGE_SIZE;
    }

    /** Snapshots must already be ordered newest first by the shared cache. */
    static Component render(List<SnapshotDetails> snapshots, int page, boolean remote) {
        final int maxPage = pageCount(snapshots.size());
        if (page < 1 || page > maxPage) throw new IllegalArgumentException("Invalid backup list page: " + page);
        final MutableComponent result = messageToText(localized(remote ? "fastback.chat.remote-list-title" : "fastback.chat.list-title", snapshots.size())).copy()
                .withStyle(GOLD);
        final int start = (page - 1) * PAGE_SIZE;
        final int end = start + Math.min(PAGE_SIZE, snapshots.size() - start);
        for (final SnapshotDetails details : snapshots.subList(start, end)) {
            final String id = details.id().getShortName();
            final Component hover = messageToText(localized("fastback.chat.view-id", id)).copy()
                    .append("\n").append(SnapshotArgument.tooltip(details, remote))
                    .append("\n").append(messageToText(localized("fastback.chat.list-copy-hint")));
            final Component entry = messageToText(localized("fastback.chat.list-entry", id,
                    SnapshotArgument.creatorText(details), SnapshotArgument.remarkText(details, remote))).copy()
                    .withStyle(style -> style.withColor(YELLOW)
                            .withClickEvent(new ClickEvent.CopyToClipboard(id))
                            .withHoverEvent(new HoverEvent.ShowText(hover)));
            result.append("\n").append(entry);
        }
        if (snapshots.isEmpty()) result.append("\n").append(messageToText(localized("fastback.chat.list-empty")).copy().withStyle(GRAY));
        final String command = remote ? "/backup remote-list " : "/backup list ";
        return result.append("\n")
                .append(navigation("fastback.chat.list-previous", command, page - 1, page > 1))
                .append(" ")
                .append(messageToText(localized("fastback.chat.list-page", page, maxPage)).copy().withStyle(GOLD))
                .append(" ")
                .append(navigation("fastback.chat.list-next", command, page + 1, page < maxPage));
    }

    private static Component navigation(String key, String command, int page, boolean enabled) {
        final MutableComponent text = messageToText(localized(key)).copy().withStyle(enabled ? GREEN : GRAY);
        return enabled ? text.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command + page))) : text;
    }

    private SnapshotList() {}
}
