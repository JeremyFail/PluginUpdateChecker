package com.failprooftech.pluginupdatechecker.notify;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@link PlayerMessageSender} for Paper (and compatible forks): builds one Kyori Adventure {@link Component} per line
 * and sends it via {@link Audience#sendMessage(Component)}. {@link PlayerMessageSenders} only constructs this when
 * Adventure is available; pure Spigot servers never load this class.
 */
final class PaperMessageSender implements PlayerMessageSender
{

    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    /**
     * Appends each {@link ChatLinePart} onto an empty root component, then sends the combined line.
     */
    @Override
    public void sendLine(Player player, List<ChatLinePart> parts)
    {
        if (!(player instanceof Audience audience))
        {
            throw new IllegalStateException("Player does not implement Adventure Audience (expected Paper)");
        }
        Component combinedLine = Component.empty();
        for (ChatLinePart linePart : parts)
        {
            if (linePart instanceof ChatLinePart.Text textPart)
            {
                String legacyPrefixed = textPart.color() == null
                        ? textPart.content()
                        : textPart.color().toString() + textPart.content();
                combinedLine = combinedLine.append(LEGACY_SECTION.deserialize(legacyPrefixed));
            }
            else if (linePart instanceof ChatLinePart.Link linkPart)
            {
                Component linkComponent = Component.text(linkPart.label())
                        .color(NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(linkPart.url()))
                        .hoverEvent(HoverEvent.showText(Component.text("Link: " + linkPart.url())));
                combinedLine = combinedLine.append(linkComponent);
            }
            else
            {
                throw new IllegalStateException("Unknown ChatLinePart: " + linePart);
            }
        }
        audience.sendMessage(combinedLine);
    }
}
