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

package net.pcal.fastback.common.mod;

import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.pcal.fastback.common.logging.UserMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;
import static net.minecraft.ChatFormatting.GRAY;
import static net.minecraft.ChatFormatting.GREEN;
import static net.minecraft.ChatFormatting.RED;
import static net.minecraft.ChatFormatting.YELLOW;
import static net.minecraft.network.chat.Style.EMPTY;

/**
 * Utility for converting {@link UserMessage} to Minecraft {@link Component}.
 *
 * @author pcal
 * @since 0.2.0
 */
public class UserMessageUtil {

    private static final Map<String, String> DEFAULT_TRANSLATIONS = loadDefaultTranslations();
    private static final Pattern HEX_COLOR = Pattern.compile("#[a-fA-F0-9]{6}");

    public static Component messageToText(final UserMessage m) {
        final MutableComponent out;
        if (m.localized() != null) {
            out = Component.translatableWithFallback(
                m.localized().key(),
                DEFAULT_TRANSLATIONS.getOrDefault(m.localized().key(), m.localized().key()),
                messageParamsToComponentArgs(m.localized().params())
            );
        } else {
            out = Component.literal(m.raw());
        }
        return out.setStyle(messageStyle(m.style()));
    }

    /** Parse configuration tokens once; substituted player text remains literal. */
    public static UserMessage configuredMessage(String template, Map<String, String> values,
                                                UserMessage.UserMessageStyle messageStyle) {
        final Style base = messageStyle(messageStyle);
        final MutableComponent text = Component.empty().setStyle(base);
        Style style = base;
        int offset = 0;
        int start = 0;
        int depth = 0;
        for (int end = 0; end < template.length(); end++) {
            final char character = template.charAt(end);
            if (character == '{') {
                if (depth++ == 0) start = end;
                continue;
            }
            if (character != '}' || depth == 0 || --depth != 0) continue;
            text.append(Component.literal(template.substring(offset, start)).setStyle(style));
            final String token = template.substring(start + 1, end);
            if (values.containsKey(token)) {
                text.append(Component.literal(values.get(token)).setStyle(style));
            } else if (HEX_COLOR.matcher(token).matches()) {
                style = style.withColor(Integer.parseInt(token.substring(1), 16));
            } else {
                ChatFormatting format;
                try {
                    format = ChatFormatting.valueOf("underlined".equalsIgnoreCase(token)
                            ? "UNDERLINE" : token.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknownToken) {
                    format = null;
                }
                if (format == null) {
                    text.append(Component.literal(template.substring(start, end + 1)).setStyle(style));
                } else {
                    style = switch (format) {
                        case RESET -> base;
                        case BOLD -> style.withBold(true);
                        case ITALIC -> style.withItalic(true);
                        case UNDERLINE -> style.withUnderlined(true);
                        case STRIKETHROUGH -> style.withStrikethrough(true);
                        case OBFUSCATED -> style.withObfuscated(true);
                        default -> style.withColor(TextColor.fromLegacyFormat(format));
                    };
                }
            }
            offset = end + 1;
        }
        text.append(Component.literal(template.substring(offset)).setStyle(style));
        return UserMessage.styledLocalized("fastback.message.custom", messageStyle, text);
    }

    private static Style messageStyle(UserMessage.UserMessageStyle style) {
        return switch (style) {
            case ERROR -> EMPTY.withColor(RED);
            case WARNING -> EMPTY.withColor(YELLOW);
            case JGIT -> EMPTY.withColor(GRAY);
            case NATIVE_GIT, BROADCAST -> EMPTY.withColor(GREEN);
            case NORMAL -> EMPTY;
        };
    }

    private static Map<String, String> loadDefaultTranslations() {
        final Map<String, String> translations = new HashMap<>();
        try (InputStream in = UserMessageUtil.class.getResourceAsStream("/assets/fastback/lang/en_us.json")) {
            Language.loadFromJson(requireNonNull(in, "Missing default FastBack translations"), translations::put);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load default FastBack translations", e);
        }
        return Map.copyOf(translations);
    }

    private static Object[] messageParamsToComponentArgs(final Object[] params) {
        if (params == null) return new Object[0];

        final Object[] out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            final Object param = params[i];
            if (param instanceof Component) {
                out[i] = param;
            } else {
                out[i] = String.valueOf(param);
            }
        }
        return out;
    }

    private UserMessageUtil() {}
}
