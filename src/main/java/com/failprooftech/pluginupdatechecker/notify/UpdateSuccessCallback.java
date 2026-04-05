package com.failprooftech.pluginupdatechecker.notify;

import org.bukkit.command.CommandSender;

import java.util.Collection;

/**
 * Optional main-thread hook after a successful remote version fetch. Runs in the same synchronous hop as
 * {@link com.failprooftech.pluginupdatechecker.event.PluginUpdateCheckEvent} dispatch. Install with
 * {@link com.failprooftech.pluginupdatechecker.PluginUpdateChecker#setOnSuccess(UpdateSuccessCallback)}.
 */
@FunctionalInterface
public interface UpdateSuccessCallback
{

    /**
     * Receives the same requestor collection passed to {@link com.failprooftech.pluginupdatechecker.PluginUpdateChecker#checkNow(java.util.Collection)}
     * (or the console-only list for {@link com.failprooftech.pluginupdatechecker.PluginUpdateChecker#checkNow()}).
     *
     * @param requestors    who triggered or is attributed to this check; empty for silent/scheduled runs
     * @param latestVersion trimmed remote version string from the supplier for this run
     */
    void onSuccess(Collection<CommandSender> requestors, String latestVersion);
}
