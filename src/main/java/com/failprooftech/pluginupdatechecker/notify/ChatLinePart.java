package com.failprooftech.pluginupdatechecker.notify;

import org.bukkit.ChatColor;

/**
 * Immutable description of one fragment of an update-notification chat line. Implementations of
 * {@link PlayerMessageSender} interpret {@link Text} and {@link Link} into platform-specific messages.
 */
public sealed interface ChatLinePart permits ChatLinePart.Text, ChatLinePart.Link
{

    /**
     * Plain UTF-8 text with optional legacy {@link ChatColor} applied before the content.
     *
     * @param content visible characters
     * @param color   Bukkit legacy color to prefix, or {@code null} for default chat color
     */
    record Text(String content, ChatColor color) implements ChatLinePart
    {
    }

    /**
     * Interactive-style link: gold, bold, underlined label opening {@code url} when clicks are supported; otherwise
     * rendered as label plus URL (see {@link PlainPlayerMessageSender}).
     *
     * @param label short button text (e.g. {@code "Download"})
     * @param url   absolute {@code http(s)} URL
     */
    record Link(String label, String url) implements ChatLinePart
    {
    }
}
