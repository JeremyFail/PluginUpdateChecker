package com.failprooftech.pluginupdatechecker;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable collection of extra User-Agent segments that {@link PluginUpdateChecker} appends when building
 * its default User-Agent string (unless {@link PluginUpdateChecker#setUserAgent(String)} supplies a full override).
 * <p>
 * Obtain the shared instance via {@link PluginUpdateChecker#getDefaultUserAgentBuilder()}.
 */
public final class UserAgentBuilder
{

    /** Ordered segments appended to the default User-Agent, separated by {@code "; "}. */
    private final List<String> segments = new ArrayList<>();

    /**
     * Appends a caller-defined segment after trimming whitespace.
     * <p>
     * Null or blank strings are ignored.
     *
     * @param segment raw text to append; may be {@code null}
     * @return this builder, for chaining
     */
    public UserAgentBuilder appendRaw(String segment)
    {
        if (segment != null && !segment.trim().isEmpty())
        {
            segments.add(segment.trim());
        }
        return this;
    }

    /**
     * Appends {@code <plugin name>/<version>} using the plugin descriptor, if {@code plugin} is non-null.
     *
     * @param plugin host plugin; may be {@code null} (no-op)
     * @return this builder, for chaining
     */
    public UserAgentBuilder appendPlugin(JavaPlugin plugin)
    {
        if (plugin != null)
        {
            String pluginVersion = plugin.getDescription().getVersion();
            segments.add(plugin.getName() + "/" + (pluginVersion == null ? "?" : pluginVersion.trim()));
        }
        return this;
    }

    /**
     * Appends {@code Minecraft/...} and {@code Bukkit/...} lines from the running server, when available.
     *
     * @param plugin host plugin used to read {@link org.bukkit.Server}; may be {@code null} (no-op)
     * @return this builder, for chaining
     */
    public UserAgentBuilder appendServerVersion(JavaPlugin plugin)
    {
        if (plugin != null && plugin.getServer() != null)
        {
            segments.add("Minecraft/" + plugin.getServer().getVersion());
            segments.add("Bukkit/" + plugin.getServer().getBukkitVersion());
        }
        return this;
    }

    /**
     * Appends the literal segment {@code paid} when the host reports using a paid build.
     *
     * @param usingPaid {@code true} to append {@code paid}
     * @return this builder, for chaining
     */
    public UserAgentBuilder appendPaidVersion(boolean usingPaid)
    {
        if (usingPaid)
        {
            segments.add("paid");
        }
        return this;
    }

    /**
     * Appends {@code uid/<userId>} for marketplace or analytics correlation (optional).
     *
     * @param userId resource or user identifier; {@code null} or empty is ignored
     * @return this builder, for chaining
     */
    public UserAgentBuilder appendUserId(String userId)
    {
        if (userId != null && !userId.isEmpty())
        {
            segments.add("uid/" + userId.trim());
        }
        return this;
    }

    /**
     * Returns the live segment list used when composing the default User-Agent.
     *
     * @return modifiable list owned by this builder; do not retain references that outlive checker usage unless
     *         you understand concurrent mutation from the async check thread
     */
    List<String> segments()
    {
        return segments;
    }

    /**
     * Clears every segment added through this builder; the checker’s hard-coded default User-Agent prefix is unchanged.
     */
    void clear()
    {
        segments.clear();
    }
}
