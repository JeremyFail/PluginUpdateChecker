package com.failprooftech.pluginupdatechecker.scheduler;

/**
 * Abstraction over Bukkit’s async/sync task execution so {@link com.failprooftech.pluginupdatechecker.PluginUpdateChecker}
 * can run network work off the main thread and publish results on the main thread. Host plugins may reuse the same
 * scheduler instance via {@link com.failprooftech.pluginupdatechecker.PluginUpdateChecker#getScheduler()} for consistency.
 */
public interface UpdateScheduler
{

    /**
     * Runs a task on the region async executor (or equivalent) associated with the owning plugin.
     *
     * @param task work that must not touch Bukkit API unless documented thread-safe
     */
    void runAsync(Runnable task);

    /**
     * Runs a task on the next server tick on the main thread.
     *
     * @param task work safe for the main thread (events, player messaging, etc.)
     */
    void runSync(Runnable task);

    /**
     * Schedules a repeating main-thread task with the same delay and period (in ticks).
     * <p>
     * The returned id is implementation-specific and must be passed to {@link #cancelTask(long)}.
     *
     * @param task        runnable executed each period on the main thread
     * @param delayTicks  ticks before the first execution
     * @param periodTicks ticks between successive executions
     * @return opaque handle for {@link #cancelTask(long)}; implementations should never return a negative id that
     *         collides with “unset” sentinel values used by callers
     */
    long runSyncRepeating(Runnable task, long delayTicks, long periodTicks);

    /**
     * Cancels a repeating task previously returned by {@link #runSyncRepeating(Runnable, long, long)}.
     * No-op if the id is unknown or already cancelled.
     *
     * @param taskId id returned from {@link #runSyncRepeating(Runnable, long, long)}
     */
    void cancelTask(long taskId);
}
