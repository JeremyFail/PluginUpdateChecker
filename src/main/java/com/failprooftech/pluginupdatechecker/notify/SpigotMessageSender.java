package com.failprooftech.pluginupdatechecker.notify;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link PlayerMessageSender} for Spigot-style servers: {@link net.md_5.bungee.api.chat.BaseComponent} segments via
 * {@link Player.Spigot#sendMessage(net.md_5.bungee.api.chat.BaseComponent...)}. Used when Paper’s Adventure
 * {@code Player.sendMessage(Component)} path is not selected. Instantiated only after {@link PlayerMessageSenders}
 * confirms {@link Player#spigot()} exists.
 */
final class SpigotMessageSender implements PlayerMessageSender
{

    /**
     * Turns each {@link ChatLinePart} into a {@link TextComponent} and sends them as one logical line.
     */
    @Override
    public void sendLine(Player player, List<ChatLinePart> parts)
    {
        List<TextComponent> lineComponents = new ArrayList<>(parts.size());
        for (ChatLinePart linePart : parts)
        {
            if (linePart instanceof ChatLinePart.Text textPart)
            {
                TextComponent textComponent = new TextComponent(textPart.content());
                if (textPart.color() != null)
                {
                    textComponent.setColor(toBungeeChatColor(textPart.color()));
                }
                lineComponents.add(textComponent);
            }
            else if (linePart instanceof ChatLinePart.Link linkPart)
            {
                lineComponents.add(buildLinkTextComponent(linkPart.label(), linkPart.url()));
            }
        }
        player.spigot().sendMessage(lineComponents.toArray(new TextComponent[0]));
    }

    /**
     * Gold, bold, underlined label with open-URL click and a short hover (non-deprecated {@link Text} content).
     *
     * @param label visible text
     * @param url   URL for {@link ClickEvent.Action#OPEN_URL}
     * @return configured component
     */
    private static TextComponent buildLinkTextComponent(String label, String url)
    {
        TextComponent linkComponent = new TextComponent(label);
        linkComponent.setBold(true);
        linkComponent.setUnderlined(true);
        linkComponent.setColor(net.md_5.bungee.api.ChatColor.GOLD);
        linkComponent.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        String hoverLegacy = "Link: " + url;
        linkComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(new ComponentBuilder(hoverLegacy).create())));
        return linkComponent;
    }

    /**
     * Maps a Bukkit legacy color to the Bungee chat API type expected by {@link TextComponent#setColor}.
     *
     * @param bukkitColor non-null Bukkit color
     * @return matching Bungee {@link net.md_5.bungee.api.ChatColor}
     */
    private static net.md_5.bungee.api.ChatColor toBungeeChatColor(ChatColor bukkitColor)
    {
        return net.md_5.bungee.api.ChatColor.getByChar(bukkitColor.getChar());
    }
}
