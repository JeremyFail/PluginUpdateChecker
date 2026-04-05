package com.failprooftech.pluginupdatechecker.event;

import com.failprooftech.pluginupdatechecker.PluginUpdateChecker;
import com.failprooftech.pluginupdatechecker.UpdateCheckResult;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.List;

/**
 * Bukkit event fired <strong>synchronously on the main thread</strong> after each update check attempt completes
 * (success or failure). Register with {@link org.bukkit.plugin.PluginManager#registerEvents(org.bukkit.event.Listener, org.bukkit.plugin.Plugin)}.
 */
public class PluginUpdateCheckEvent extends Event
{

    /** Bukkit’s static handler list for this event type. */
    private static final HandlerList HANDLER_LIST = new HandlerList();

    /** Checker instance that completed the attempt. */
    private final PluginUpdateChecker checker;
    /**
     * {@code true} if {@link com.failprooftech.pluginupdatechecker.RemoteVersionSupplier#fetchLatestVersion()} completed
     * without throwing.
     */
    private final boolean success;
    /** Outcome after version comparison, or {@link UpdateCheckResult#UNKNOWN} when {@link #success} is {@code false}. */
    private final UpdateCheckResult result;
    /** Local version string used for this run. */
    private final String installedVersion;
    /** Remote version when {@link #success} is {@code true}; otherwise {@code null}. */
    private final String latestVersion;
    /** Immutable snapshot of requestors for this check. */
    private final List<CommandSender> requestors;

    /**
     * Builds an immutable snapshot of the check outcome for listeners.
     *
     * @param checker          checker instance that performed the check
     * @param success          {@code true} if {@link com.failprooftech.pluginupdatechecker.RemoteVersionSupplier#fetchLatestVersion()} completed without throwing
     * @param result           coarse outcome after comparison (or {@link UpdateCheckResult#UNKNOWN} on failure)
     * @param installedVersion version string used as “installed” for this run
     * @param latestVersion    remote version on success; {@code null} on failure
     * @param requestors       command senders associated with this check; {@code null} treated as empty list
     */
    public PluginUpdateCheckEvent(
            PluginUpdateChecker checker,
            boolean success,
            UpdateCheckResult result,
            String installedVersion,
            String latestVersion,
            List<CommandSender> requestors)
    {
        super(false);
        this.checker = checker;
        this.success = success;
        this.result = result;
        this.installedVersion = installedVersion;
        this.latestVersion = latestVersion;
        this.requestors = requestors == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(requestors);
    }

    /**
     * @return the {@link PluginUpdateChecker} that originated this event
     */
    public PluginUpdateChecker getChecker()
    {
        return checker;
    }

    /**
     * @return {@code true} if the supplier/HTTP step completed without throwing; {@code false} if an exception was caught
     */
    public boolean isSuccess()
    {
        return success;
    }

    /**
     * @return comparison outcome for this attempt, or {@link UpdateCheckResult#UNKNOWN} when unsuccessful
     */
    public UpdateCheckResult getResult()
    {
        return result;
    }

    /**
     * @return installed version string used for this check (may differ from {@code plugin.yml} if overridden on the checker)
     */
    public String getInstalledVersion()
    {
        return installedVersion;
    }

    /**
     * Remote version returned by the supplier for this successful attempt.
     *
     * @return trimmed latest string, or {@code null} if {@link #isSuccess()} is {@code false}
     */
    public String getLatestVersion()
    {
        return latestVersion;
    }

    /**
     * Command senders that should receive built-in “requestor” messaging for this check.
     *
     * @return immutable list; empty for silent checks
     */
    public List<CommandSender> getRequestors()
    {
        return requestors;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HandlerList getHandlers()
    {
        return HANDLER_LIST;
    }

    /**
     * Bukkit handler list accessor for static registration patterns.
     *
     * @return the shared {@link HandlerList} for this event type
     */
    public static HandlerList getHandlerList()
    {
        return HANDLER_LIST;
    }
}
