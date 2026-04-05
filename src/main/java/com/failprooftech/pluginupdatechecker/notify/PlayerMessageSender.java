package com.failprooftech.pluginupdatechecker.notify;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * Strategy for sending a single chat line built from {@link ChatLinePart} segments. Concrete implementations target
 * Paper Adventure, Spigot {@code BaseComponent}, or plain strings; see {@link PlayerMessageSenders}.
 */
public interface PlayerMessageSender
{

    /**
     * Sends all {@code parts} as one continuous line (colors and links preserved when the platform supports them).
     *
     * @param player recipient
     * @param parts  ordered segments; empty list sends an empty line
     */
    void sendLine(Player player, List<ChatLinePart> parts);

    /**
     * Convenience overload that wraps {@code parts} in an immutable list for {@link #sendLine(Player, List)}.
     *
     * @param player recipient
     * @param parts  ordered segments
     */
    default void sendLine(Player player, ChatLinePart... parts)
    {
        sendLine(player, List.of(parts));
    }
}
