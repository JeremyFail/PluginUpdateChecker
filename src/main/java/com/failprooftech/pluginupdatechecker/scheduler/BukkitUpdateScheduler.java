package com.failprooftech.pluginupdatechecker.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link UpdateScheduler} backed by {@link org.bukkit.scheduler.BukkitScheduler} for a specific {@link JavaPlugin}.
 * Repeating tasks are tracked in a map so {@link #cancelTask(long)} can cancel the underlying {@link BukkitTask}.
 */
public final class BukkitUpdateScheduler implements UpdateScheduler
{

    /** Plugin passed to {@link org.bukkit.scheduler.BukkitScheduler} when registering tasks. */
    private final JavaPlugin plugin;
    /** Maps synthetic ids to active timer tasks for cancellation. */
    private final Map<Long, BukkitTask> tasks = new ConcurrentHashMap<>();
    /** Monotonic id generator for {@link #runSyncRepeating(Runnable, long, long)}. */
    private final AtomicLong syntheticIds = new AtomicLong(1L);

    /**
     * Binds async/sync/repeating work to the given plugin for {@link org.bukkit.scheduler.BukkitScheduler} registration.
     *
     * @param plugin owning plugin; must stay enabled while tasks may run
     */
    public BukkitUpdateScheduler(JavaPlugin plugin)
    {
        this.plugin = plugin;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runAsync(Runnable task)
    {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runSync(Runnable task)
    {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Wraps {@link org.bukkit.scheduler.BukkitScheduler#runTaskTimer(org.bukkit.plugin.Plugin, Runnable, long, long)}.
     *
     * @param task        {@inheritDoc}
     * @param delayTicks  {@inheritDoc}
     * @param periodTicks {@inheritDoc}
     * @return a synthetic positive long key stored until cancel
     */
    @Override
    public long runSyncRepeating(Runnable task, long delayTicks, long periodTicks)
    {
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        long taskId = syntheticIds.getAndIncrement();
        tasks.put(taskId, bukkitTask);
        return taskId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cancelTask(long taskId)
    {
        BukkitTask bukkitTask = tasks.remove(taskId);
        if (bukkitTask != null)
        {
            bukkitTask.cancel();
        }
    }
}
