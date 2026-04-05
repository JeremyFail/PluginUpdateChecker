package com.failprooftech.pluginupdatechecker.notify;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@link PlayerMessageSender} of last resort: one {@link Player#sendMessage(String)} per line using legacy § codes only.
 * Links are shown as styled label text plus the URL in parentheses; there is no click or hover.
 */
final class PlainPlayerMessageSender implements PlayerMessageSender
{

    /**
     * Concatenates all parts into one legacy string (colors via {@link ChatColor} constants).
     */
    @Override
    public void sendLine(Player player, List<ChatLinePart> parts)
    {
        StringBuilder lineBuilder = new StringBuilder();
        for (ChatLinePart linePart : parts)
        {
            if (linePart instanceof ChatLinePart.Text textPart)
            {
                if (textPart.color() != null)
                {
                    lineBuilder.append(textPart.color());
                }
                lineBuilder.append(textPart.content());
            }
            else if (linePart instanceof ChatLinePart.Link linkPart)
            {
                // Mimic link styling without interactive events so players can still copy the URL from chat.
                lineBuilder.append(ChatColor.GOLD).append(ChatColor.BOLD).append(ChatColor.UNDERLINE)
                        .append(linkPart.label()).append(ChatColor.RESET).append(ChatColor.GRAY)
                        .append(" (").append(linkPart.url()).append(")");
            }
        }
        player.sendMessage(lineBuilder.toString());
    }
}
