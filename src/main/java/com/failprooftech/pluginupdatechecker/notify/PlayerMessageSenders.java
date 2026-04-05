package com.failprooftech.pluginupdatechecker.notify;

import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Registry for the {@link PlayerMessageSender} implementation used when {@link com.failprooftech.pluginupdatechecker.PluginUpdateChecker}
 * prints update lines to players.
 * <p>
 * On first {@link #get()}, selects an implementation in order:
 * <ol>
 *     <li>{@link PaperMessageSender} when Kyori Adventure and {@code Player.sendMessage(Component)} are present (Paper)</li>
 *     <li>{@link SpigotMessageSender} when {@link Player#spigot()} exists and the Paper path above did not apply</li>
 *     <li>{@link PlainPlayerMessageSender} otherwise</li>
 * </ol>
 * Paper vs Spigot capability is probed with small reflection checks here so {@link PaperMessageSender} is not loaded on
 * pure Spigot (no Adventure), avoiding linkage errors. Call {@link #register(PlayerMessageSender)} from {@code onEnable}
 * to override detection.
 */
public final class PlayerMessageSenders
{

    /** Protects lazy init and {@link #register(PlayerMessageSender)}. */
    private static final Object LOCK = new Object();
    /** Cached sender; written under {@link #LOCK}. */
    private static volatile PlayerMessageSender instance;

    /**
     * Prevents instantiation.
     */
    private PlayerMessageSenders()
    {
    }

    /**
     * Returns the active sender, running {@link #detect()} once if nothing was {@linkplain #register registered}.
     *
     * @return non-null {@link PlayerMessageSender}
     */
    public static PlayerMessageSender get()
    {
        PlayerMessageSender cached = instance;
        if (cached != null)
        {
            return cached;
        }
        synchronized (LOCK)
        {
            if (instance == null)
            {
                instance = detect();
            }
            return instance;
        }
    }

    /**
     * Replaces the detected sender with a custom implementation (tests, proxies, or a fixed backend).
     * <p>
     * Must be called before the first {@link #get()} if you need to avoid auto-detection entirely.
     *
     * @param sender new implementation; must not be {@code null}
     */
    public static void register(PlayerMessageSender sender)
    {
        Objects.requireNonNull(sender, "sender");
        synchronized (LOCK)
        {
            instance = sender;
        }
    }

    /**
     * Eagerly runs the same logic as {@link #get()} so the implementation is chosen during plugin startup.
     */
    public static void ensureInitialized()
    {
        get();
    }

    /**
     * @return {@code true} if Adventure {@code Component} and {@code Player.sendMessage(Component)} plus the legacy
     *         serializer are loadable (typical Paper)
     */
    private static boolean isPaperAdventureMessagingAvailable()
    {
        try
        {
            Class<?> adventureComponent = Class.forName("net.kyori.adventure.text.Component");
            Player.class.getMethod("sendMessage", adventureComponent);
            Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            return true;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /**
     * @return {@code true} when {@link Player#spigot()} exists (Spigot API servers)
     */
    private static boolean isSpigotComponentMessagingAvailable()
    {
        try
        {
            Player.class.getMethod("spigot");
            return true;
        }
        catch (NoSuchMethodException ignored)
        {
            return false;
        }
    }

    /**
     * Picks the first supported {@link PlayerMessageSender} in priority order.
     *
     * @return non-null implementation
     */
    private static PlayerMessageSender detect()
    {
        if (isPaperAdventureMessagingAvailable())
        {
            return new PaperMessageSender();
        }
        if (isSpigotComponentMessagingAvailable())
        {
            return new SpigotMessageSender();
        }
        return new PlainPlayerMessageSender();
    }
}
