package com.failprooftech.pluginupdatechecker;

import java.io.IOException;

/**
 * Functional source of the remote “latest version” string for {@link PluginUpdateChecker}.
 * <p>
 * Implementations run on a worker thread supplied by {@link com.failprooftech.pluginupdatechecker.scheduler.UpdateScheduler#runAsync(Runnable)}.
 * They must not call the Bukkit API (thread-unsafe and may deadlock).
 */
@FunctionalInterface
public interface RemoteVersionSupplier
{

    /**
     * Performs network or custom logic and returns the latest version as reported upstream.
     *
     * @return non-null, non-empty version string after any trimming applied by the implementation
     * @throws IOException if the version cannot be read or parsed (propagates to failure handling on the main thread)
     */
    String fetchLatestVersion() throws IOException;
}
