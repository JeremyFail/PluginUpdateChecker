package com.failprooftech.pluginupdatechecker;

/**
 * High-level classification of an update check after the remote version has been fetched successfully,
 * or {@link #UNKNOWN} when the fetch failed or no meaningful comparison applies.
 * <p>
 * Exposed on {@link com.failprooftech.pluginupdatechecker.event.PluginUpdateCheckEvent} and via
 * {@link PluginUpdateChecker#getLastCheckResult()}.
 */
public enum UpdateCheckResult
{
    /**
     * The remote version string compares strictly <em>newer</em> than the installed version
     * (Maven-style ordering via {@link VersionCompare}).
     */
    NEW_VERSION_AVAILABLE,
    /**
     * The remote version is not newer than installed (including the sanity rule: treat odd API data as “not an update”).
     */
    RUNNING_LATEST_VERSION,
    /**
     * The supplier or HTTP layer threw, or no successful check has completed yet.
     * Also used after a failed attempt when {@link PluginUpdateChecker#getLastCheckResult()} reflects the last run.
     */
    UNKNOWN
}
