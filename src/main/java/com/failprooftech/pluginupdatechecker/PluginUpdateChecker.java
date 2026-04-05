package com.failprooftech.pluginupdatechecker;

import com.failprooftech.pluginupdatechecker.event.PluginUpdateCheckEvent;
import com.failprooftech.pluginupdatechecker.notify.ChatLinePart;
import com.failprooftech.pluginupdatechecker.notify.PlayerMessageSenders;
import com.failprooftech.pluginupdatechecker.notify.UpdateSuccessCallback;
import com.failprooftech.pluginupdatechecker.scheduler.BukkitUpdateScheduler;
import com.failprooftech.pluginupdatechecker.scheduler.UpdateScheduler;
import com.failprooftech.pluginupdatechecker.source.GitHubReleasesSupplier;
import com.failprooftech.pluginupdatechecker.source.PlainTextUrlSupplier;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Coordinates asynchronous update checks for a single {@link JavaPlugin}: resolves a remote version string,
 * compares it to the installed version using Maven-style ordering, fires {@link PluginUpdateCheckEvent}, and
 * optionally prints player/console notifications.
 * <p>
 * Network and {@link RemoteVersionSupplier} work run via {@link #getScheduler()}{@code .runAsync}; completion
 * handlers and events always run on the main thread.
 * <p>
 * Embed this library in your plugin JAR and <strong>relocate</strong> the {@code com.failprooftech.pluginupdatechecker}
 * package to avoid conflicts with other shaded copies.
 */
public class PluginUpdateChecker
{

    /**
     * Library artifact version, populated from filtered {@code /version.properties} at build time (mirrors the Maven version).
     */
    public static final String VERSION;

    /** Ensures the relocation warning is logged at most once per JVM. */
    private static volatile boolean relocationWarned;

    static
    {
        VERSION = readVersionProperties();
    }

    /**
     * Reads {@code version} from {@code /version.properties} on the classpath.
     *
     * @return trimmed version string, or {@code "unknown"} if the resource is missing or unreadable
     */
    private static String readVersionProperties()
    {
        Properties versionProperties = new Properties();
        try (InputStream resourceStream = PluginUpdateChecker.class.getResourceAsStream("/version.properties"))
        {
            if (resourceStream != null)
            {
                versionProperties.load(resourceStream);
                return versionProperties.getProperty("version", "unknown").trim();
            }
        }
        catch (IOException ignored)
        {
        }
        return "unknown";
    }

    /**
     * All constructed checkers (weak keys) iterated on player join for optional notifications.
     */
    private static final Collection<PluginUpdateChecker> ACTIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * Guards registration of the single JVM-wide {@link GlobalListener}.
     */
    private static final AtomicBoolean GLOBAL_LISTENER_REGISTERED = new AtomicBoolean(false);

    /** Host plugin owning scheduler tasks and logger output. */
    private final JavaPlugin plugin;
    /** Invoked on async thread to obtain the latest version string. */
    private final RemoteVersionSupplier supplier;
    /** Routes async/sync work for this checker. */
    private final UpdateScheduler scheduler;
    /** Extra User-Agent segments when not using {@link #setUserAgent(String)}. */
    private final UserAgentBuilder userAgentExtras = new UserAgentBuilder();

    /** When non-null/non-empty, replaces {@code plugin.getDescription().getVersion()} for comparisons. */
    private volatile String installedVersionOverride;
    /** Full User-Agent override; when null, {@link #buildUserAgent()} supplies the header. */
    private volatile String userAgentOverride;
    /** Connect timeout in ms for {@link java.net.HttpURLConnection}; {@code 0} means JVM default. */
    private volatile int connectTimeoutMs;

    /** Whether {@link #getAppropriateDownloadLinks()} should prefer paid URLs and tag the default UA. */
    private volatile boolean usingPaidVersion;
    /** Optional marketplace user/resource id appended to the default User-Agent. */
    private volatile String userAgentUserId;

    /** Single download URL when separate free/paid URLs are not used. */
    private volatile String downloadUrl;
    /** Optional free-tier download URL when {@link #downloadPaidUrl} is also set. */
    private volatile String downloadFreeUrl;
    /** Optional paid-tier download URL when {@link #downloadFreeUrl} is also set. */
    private volatile String downloadPaidUrl;
    /** Chat/console label for the free download link when both tiers exist. */
    private volatile String downloadFreeLabel = "Free";
    /** Chat/console label for the paid download link when both tiers exist. */
    private volatile String downloadPaidLabel = "Paid";
    /** Optional changelog URL (player chat only). */
    private volatile String changelogUrl;
    /** Optional donation URL (player chat and console box). */
    private volatile String donationUrl;
    /** Optional support URL (player chat and console box). */
    private volatile String supportUrl;

    /** Built-in messages to explicit check requestors (players/console). */
    private volatile boolean notifyRequestorsOnCheck = true;
    /** Include server operators in join-time update notices. */
    private volatile boolean notifyOperatorsOnJoin = true;
    /** Optional permission that also receives join-time notices. */
    private volatile String notifyPermission;
    /** Suppress “you are on the latest version” console line after checks. */
    private volatile boolean suppressUpToDateConsoleMessage;
    /** Allow “latest version” chat to players who requested a check. */
    private volatile boolean notifyRequestorsWhenUpToDate = true;
    /** Use legacy color codes in console update box lines. */
    private volatile boolean coloredConsoleOutput;

    /** Last remote version string from a successful fetch. */
    private volatile String latestVersionResolved;
    /** Outcome of the most recently finished attempt. */
    private volatile UpdateCheckResult lastCheckResult = UpdateCheckResult.UNKNOWN;
    /** Set when a check is queued (including scheduled silent checks). */
    private volatile boolean checkEverStarted;
    /** Set when any attempt has finished (success or failure). */
    private volatile boolean atLeastOneCheckCompleted;

    /** Optional success hook; runs on the main thread. */
    private volatile UpdateSuccessCallback onSuccess;
    /** Failure hook; default prints stack traces. */
    private volatile Consumer<Throwable> onFailure = Throwable::printStackTrace;

    /** Id returned by {@link UpdateScheduler#runSyncRepeating}; {@code -1} if no active repeat task. */
    private volatile long repeatingTaskId = -1;

    /**
     * Configures a GitHub Releases source: {@code GET /repos/{owner}/{repo}/releases}, first element’s {@code tag_name}.
     *
     * @param plugin          host plugin (must not be {@code null})
     * @param githubOwnerRepo repository slug in the form {@code owner/repo}
     * @throws NullPointerException if any argument is null
     */
    public PluginUpdateChecker(JavaPlugin plugin, String githubOwnerRepo)
    {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(githubOwnerRepo, "githubOwnerRepo");
        String githubOwnerSlug = githubOwnerRepo;
        this.plugin = plugin;
        this.supplier = () -> new GitHubReleasesSupplier(githubOwnerSlug, this::buildUserAgent, () -> this.connectTimeoutMs)
                .fetchLatestVersion();
        this.scheduler = new BukkitUpdateScheduler(plugin);
        afterConstruct();
    }

    /**
     * Configures an HTTP(S) plain-text source: first non-empty line after trim is the version string.
     *
     * @param plugin               host plugin
     * @param plainTextVersionUrl  URL to fetch (typically a small text file)
     * @throws NullPointerException if any argument is null
     */
    public PluginUpdateChecker(JavaPlugin plugin, java.net.URL plainTextVersionUrl)
    {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(plainTextVersionUrl, "plainTextVersionUrl");
        String plainTextVersionUrlString = plainTextVersionUrl.toString();
        this.plugin = plugin;
        this.supplier = () -> new PlainTextUrlSupplier(plainTextVersionUrlString, this::buildUserAgent, () -> this.connectTimeoutMs)
                .fetchLatestVersion();
        this.scheduler = new BukkitUpdateScheduler(plugin);
        afterConstruct();
    }

    /**
     * Configures a custom {@link RemoteVersionSupplier}. The supplier runs on an async thread; it must not use the Bukkit API.
     *
     * @param plugin   host plugin
     * @param supplier version source; typically a lambda or method reference
     * @throws NullPointerException if either argument is null
     */
    public PluginUpdateChecker(JavaPlugin plugin, RemoteVersionSupplier supplier)
    {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.supplier = Objects.requireNonNull(supplier, "supplier");
        this.scheduler = new BukkitUpdateScheduler(plugin);
        afterConstruct();
    }

    /**
     * Shared post-constructor steps: relocation warning, weak registration, and global listener registration.
     */
    private void afterConstruct()
    {
        warnRelocationIfNeeded();
        ACTIVE.add(this);
        ensureGlobalListenerRegistered();
        PlayerMessageSenders.ensureInitialized();
    }

    /**
     * Published Maven package name before embedders relocate (must not be a single string literal: Maven Shade rewrites
     * matching constants when relocating, which would equal {@link Package#getName()} after a correct shade and cause a false positive).
     */
    private static String unrelocatedLibraryPackageName()
    {
        return new String(new char[] {
                'c', 'o', 'm', '.', 'f', 'a', 'i', 'l', 'p', 'r', 'o', 'o', 'f', 't', 'e', 'c', 'h', '.',
                'p', 'l', 'u', 'g', 'i', 'n', 'u', 'p', 'd', 'a', 't', 'e', 'c', 'h', 'e', 'c', 'k', 'e', 'r'
        });
    }

    /**
     * Logs a one-time warning if this class still loads from the published package (shading without relocation).
     * <p>
     * Logs via {@link java.util.logging.Logger#warning(String)} on the host plugin: that API does not interpret
     * {@link ChatColor} or section signs, so the message is plain ASCII (no mojibake in the log file).
     */
    private void warnRelocationIfNeeded()
    {
        if (relocationWarned)
        {
            return;
        }
        Package pkg = PluginUpdateChecker.class.getPackage();
        String packageName = pkg == null ? "" : pkg.getName();
        String published = unrelocatedLibraryPackageName();
        if (!packageName.equals(published))
        {
            return;
        }
        relocationWarned = true;
        plugin.getLogger().warning(
                "[PluginUpdateChecker] Classes are still in the published package '" + published
                        + "' (not relocated). Relocate this library when shading to avoid classpath conflicts with other plugins. "
                        + "Please contact the developer of " + plugin.getName() + " to have them fix this.");
    }

    /**
     * Registers {@link GlobalListener} once per JVM against the first checker’s {@link #plugin}.
     */
    private void ensureGlobalListenerRegistered()
    {
        if (GLOBAL_LISTENER_REGISTERED.compareAndSet(false, true))
        {
            Bukkit.getPluginManager().registerEvents(new GlobalListener(), plugin);
        }
    }

    /**
     * Static helper delegating to {@link VersionCompare#isRemoteNewerThanInstalled(String, String)}.
     *
     * @param installed local version string
     * @param remote    remote version string
     * @return {@code true} if {@code remote} compares strictly newer
     */
    public static boolean isRemoteVersionNewer(String installed, String remote)
    {
        return VersionCompare.isRemoteNewerThanInstalled(installed, remote);
    }

    /**
     * @return the host plugin passed to the constructor
     */
    public JavaPlugin getPlugin()
    {
        return plugin;
    }

    /**
     * Exposes the scheduler used for async checks so embedders can align other work with the same threading model.
     *
     * @return the scheduler bound to {@link #plugin}
     */
    public UpdateScheduler getScheduler()
    {
        return scheduler;
    }

    /**
     * Overrides the installed version used for comparison (trimmed). {@code null} restores {@code plugin.yml} version.
     *
     * @param version override string, or {@code null}
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setInstalledVersion(String version)
    {
        this.installedVersionOverride = version == null ? null : version.trim();
        return this;
    }

    /**
     * Sets a fixed User-Agent string for HTTP requests. {@code null} or empty clears the override so {@link #buildUserAgent()} runs.
     *
     * @param userAgent full header value, or {@code null}/blank to use defaults
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setUserAgent(String userAgent)
    {
        this.userAgentOverride = userAgent == null || userAgent.isEmpty() ? null : userAgent;
        return this;
    }

    /**
     * Mutable builder for extra default User-Agent segments (ignored while {@link #setUserAgent(String)} is set).
     *
     * @return the same builder instance for the lifetime of this checker
     */
    public UserAgentBuilder getDefaultUserAgentBuilder()
    {
        return userAgentExtras;
    }

    /**
     * Sets the connect timeout for {@link java.net.HttpURLConnection} used by built-in suppliers.
     * {@code 0} means do not call {@link java.net.HttpURLConnection#setConnectTimeout(int)}. Read timeout is never set.
     *
     * @param connectTimeoutMs non-negative timeout in milliseconds
     * @return this instance, for chaining
     * @throws IllegalArgumentException if {@code connectTimeoutMs &lt; 0}
     */
    public PluginUpdateChecker setConnectTimeoutMs(int connectTimeoutMs)
    {
        if (connectTimeoutMs < 0)
        {
            throw new IllegalArgumentException("connectTimeoutMs must be >= 0");
        }
        this.connectTimeoutMs = connectTimeoutMs;
        return this;
    }

    /**
     * Marks whether this server runs a “paid” build, affecting {@link #getAppropriateDownloadLinks()} order/content and default UA.
     *
     * @param usingPaid {@code true} if the paid distribution is in use
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setUsingPaidVersion(boolean usingPaid)
    {
        this.usingPaidVersion = usingPaid;
        return this;
    }

    /**
     * Adds {@code uid/...} to the default User-Agent when set (reserved for future marketplace integration).
     *
     * @param userId opaque id, or {@code null} to clear
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setUserAgentResourceUserId(String userId)
    {
        this.userAgentUserId = userId == null ? null : userId.trim();
        return this;
    }

    /**
     * Sets a single download URL used when separate free/paid URLs are not configured.
     *
     * @param url HTTP(S) link, or {@code null}/blank to clear
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setDownloadUrl(String url)
    {
        this.downloadUrl = blankToNull(url);
        return this;
    }

    /**
     * Sets separate free and paid download URLs (see {@link #getAppropriateDownloadLinks()} for ordering rules).
     *
     * @param freeUrl URL for the free build, or {@code null}/blank
     * @param paidUrl URL for the paid build, or {@code null}/blank
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setDownloadUrls(String freeUrl, String paidUrl)
    {
        this.downloadFreeUrl = blankToNull(freeUrl);
        this.downloadPaidUrl = blankToNull(paidUrl);
        return this;
    }

    /**
     * Customizes chat/console labels for the two download buttons when both free and paid URLs exist.
     *
     * @param freeLabel label for the free link; ignored if {@code null} or empty
     * @param paidLabel label for the paid link; ignored if {@code null} or empty
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setDownloadLinkLabels(String freeLabel, String paidLabel)
    {
        if (freeLabel != null && !freeLabel.isEmpty())
        {
            this.downloadFreeLabel = freeLabel;
        }
        if (paidLabel != null && !paidLabel.isEmpty())
        {
            this.downloadPaidLabel = paidLabel;
        }
        return this;
    }

    /**
     * URL opened from the in-game “Changelog” link (not printed in the console update box).
     *
     * @param url HTTP(S) link, or {@code null}/blank to clear
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setChangelogUrl(String url)
    {
        this.changelogUrl = blankToNull(url);
        return this;
    }

    /**
     * Donation link shown in player chat and in the console update box.
     *
     * @param url HTTP(S) link, or {@code null}/blank to clear
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setDonationUrl(String url)
    {
        this.donationUrl = blankToNull(url);
        return this;
    }

    /**
     * Support link shown in player chat and in the console update box.
     *
     * @param url HTTP(S) link, or {@code null}/blank to clear
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setSupportUrl(String url)
    {
        this.supportUrl = blankToNull(url);
        return this;
    }

    /**
     * When {@code true}, players and console listed as requestors receive built-in messages after each check.
     *
     * @param notify {@code false} to rely only on events/callbacks
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setNotifyRequestorsOnCheck(boolean notify)
    {
        this.notifyRequestorsOnCheck = notify;
        return this;
    }

    /**
     * When {@code true}, operators may see join-time update notices (if other conditions hold).
     *
     * @param notify whether to consider operators for join notifications
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setNotifyOperatorsOnJoin(boolean notify)
    {
        this.notifyOperatorsOnJoin = notify;
        return this;
    }

    /**
     * Permission node that grants join-time update notices in addition to operators. {@code null} or blank disables.
     *
     * @param permission Bukkit permission string, or {@code null}
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setNotifyPermissionOnJoin(String permission)
    {
        this.notifyPermission = blankToNull(permission);
        return this;
    }

    /**
     * When {@code true}, suppresses the console “latest version” info line after successful up-to-date checks (requestor flow).
     *
     * @param suppress {@code true} to hide that message
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setSuppressUpToDateConsoleMessage(boolean suppress)
    {
        this.suppressUpToDateConsoleMessage = suppress;
        return this;
    }

    /**
     * When {@code false}, players who requested a check do not receive the “running the latest version” chat line.
     * Join notifications never show that line regardless of this setting.
     *
     * @param notify {@code true} to allow the up-to-date player message when requestors triggered the check
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setNotifyRequestorsWhenUpToDate(boolean notify)
    {
        this.notifyRequestorsWhenUpToDate = notify;
        return this;
    }

    /**
     * When {@code true}, prefixes some console update-box lines with legacy {@link ChatColor} codes for terminals that support them.
     *
     * @param colored whether to colorize console output
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setColoredConsoleOutput(boolean colored)
    {
        this.coloredConsoleOutput = colored;
        return this;
    }

    /**
     * Main-thread callback after a successful fetch (before the event is fired from the same sync hop).
     *
     * @param callback handler, or {@code null} to clear
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setOnSuccess(UpdateSuccessCallback callback)
    {
        this.onSuccess = callback;
        return this;
    }

    /**
     * Main-thread callback when the supplier throws. Default is {@code Throwable#printStackTrace()}; {@code null} installs a no-op.
     *
     * @param callback consumer receiving the failure, or {@code null} for silent failure
     * @return this instance, for chaining
     */
    public PluginUpdateChecker setOnFailure(Consumer<Throwable> callback)
    {
        if (callback == null)
        {
            this.onFailure = ignoredThrowable ->
            {
            };
        }
        else
        {
            this.onFailure = callback;
        }
        return this;
    }

    /**
     * Starts an asynchronous check and treats the server console as the sole requestor for built-in messaging.
     */
    public void checkNow()
    {
        checkNow(Collections.singletonList(Bukkit.getConsoleSender()));
    }

    /**
     * Starts an asynchronous check: fetches the remote version off-thread, then completes on the main thread.
     * {@code null} or an empty collection means a silent check (no built-in requestor messages), still firing the event.
     *
     * @param requestors players, console, or other {@link CommandSender}s to attribute to this check; may be {@code null}
     */
    public void checkNow(Collection<? extends CommandSender> requestors)
    {
        List<CommandSender> mutableRequestors = requestors == null || requestors.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(requestors);
        checkEverStarted = true;
        final List<CommandSender> immutableRequestors = Collections.unmodifiableList(mutableRequestors);
        scheduler.runAsync(() ->
        {
            String installedVersionAtStart = getCurrentVersion();
            try
            {
                String remoteVersionRaw = supplier.fetchLatestVersion();
                String remoteVersionTrimmed = remoteVersionRaw == null ? "" : remoteVersionRaw.trim();
                scheduler.runSync(() -> finishSuccess(installedVersionAtStart, remoteVersionTrimmed, immutableRequestors));
            }
            catch (IOException | RuntimeException ex)
            {
                scheduler.runSync(() -> finishFailure(installedVersionAtStart, ex, immutableRequestors));
            }
        });
    }

    /**
     * Schedules a repeating <strong>silent</strong> check (empty requestors) on the main-thread timer.
     * The first execution occurs after one full interval, not immediately-call {@link #checkNow()} for an immediate run.
     * <p>
     * Calling this again replaces any previous schedule.
     *
     * @param amount magnitude of the interval
     * @param unit   time unit converted to ticks ({@code ~50ms} per tick)
     * @return this instance, for chaining
     */
    public PluginUpdateChecker scheduleRepeating(long amount, TimeUnit unit)
    {
        cancelScheduledChecks();
        long ticks = Math.max(1L, unit.toMillis(amount) / 50L);
        repeatingTaskId = scheduler.runSyncRepeating(
                () -> checkNow(Collections.emptyList()),
                ticks,
                ticks);
        return this;
    }

    /**
     * Cancels the repeating task created by {@link #scheduleRepeating(long, TimeUnit)}, if any.
     */
    public void cancelScheduledChecks()
    {
        if (repeatingTaskId >= 0)
        {
            scheduler.cancelTask(repeatingTaskId);
            repeatingTaskId = -1;
        }
    }

    /**
     * @return last successfully resolved remote version, or {@code null} if no check has succeeded yet
     */
    public String getLatestVersion()
    {
        return latestVersionResolved;
    }

    /**
     * @return installed version string used for comparison (override or {@code plugin.yml})
     */
    public String getCurrentVersion()
    {
        if (installedVersionOverride != null && !installedVersionOverride.isEmpty())
        {
            return installedVersionOverride;
        }
        String versionFromPluginYml = plugin.getDescription().getVersion();
        return versionFromPluginYml == null ? "" : versionFromPluginYml.trim();
    }

    /**
     * Coarse result of the <em>last completed</em> check attempt.
     *
     * @return {@link UpdateCheckResult#UNKNOWN} after failures; otherwise the post-compare outcome
     */
    public UpdateCheckResult getLastCheckResult()
    {
        return lastCheckResult;
    }

    /**
     * String equality between {@link #getCurrentVersion()} and {@link #getLatestVersion()} (not semantic comparison).
     *
     * @return {@code false} if {@link #getLatestVersion()} is {@code null}
     */
    public boolean isUsingLatestVersion()
    {
        String latest = latestVersionResolved;
        if (latest == null)
        {
            return false;
        }
        return getCurrentVersion().equals(latest);
    }

    /**
     * @return value set by {@link #setUsingPaidVersion(boolean)}
     */
    public boolean isUsingPaidVersion()
    {
        return usingPaidVersion;
    }

    /**
     * @return {@code true} after any {@link #checkNow()} or scheduled check has been queued
     */
    public boolean hasBeenChecked()
    {
        return checkEverStarted;
    }

    /**
     * Computes which download URLs to advertise in messages, honoring paid/free configuration and {@link #isUsingPaidVersion()}.
     *
     * @return ordered list (empty if nothing configured)
     */
    public List<String> getAppropriateDownloadLinks()
    {
        String singleDownloadUrl = downloadUrl;
        String freeDownloadUrl = downloadFreeUrl;
        String paidDownloadUrl = downloadPaidUrl;
        List<String> resultLinks = new ArrayList<>(2);
        boolean hasFreeUrl = freeDownloadUrl != null && !freeDownloadUrl.isEmpty();
        boolean hasPaidUrl = paidDownloadUrl != null && !paidDownloadUrl.isEmpty();
        if (hasFreeUrl && hasPaidUrl)
        {
            if (usingPaidVersion)
            {
                if (hasPaidUrl)
                {
                    resultLinks.add(paidDownloadUrl);
                }
                else if (hasFreeUrl)
                {
                    resultLinks.add(freeDownloadUrl);
                }
            }
            else
            {
                resultLinks.add(paidDownloadUrl);
                resultLinks.add(freeDownloadUrl);
            }
            return resultLinks;
        }
        if (singleDownloadUrl != null && !singleDownloadUrl.isEmpty())
        {
            resultLinks.add(singleDownloadUrl);
            return resultLinks;
        }
        if (hasPaidUrl)
        {
            resultLinks.add(paidDownloadUrl);
        }
        if (hasFreeUrl)
        {
            resultLinks.add(freeDownloadUrl);
        }
        return resultLinks;
    }

    /**
     * @return configured changelog URL, or {@code null}
     */
    public String getChangelogLink()
    {
        return changelogUrl;
    }

    /**
     * @return configured donation URL, or {@code null}
     */
    public String getDonationLink()
    {
        return donationUrl;
    }

    /**
     * @return configured support URL, or {@code null}
     */
    public String getSupportLink()
    {
        return supportUrl;
    }

    /**
     * @return {@link #setNotifyRequestorsOnCheck(boolean)}
     */
    public boolean isNotifyRequestorsOnCheck()
    {
        return notifyRequestorsOnCheck;
    }

    /**
     * @return {@link #setNotifyOperatorsOnJoin(boolean)}
     */
    public boolean isNotifyOperatorsOnJoin()
    {
        return notifyOperatorsOnJoin;
    }

    /**
     * @return permission node for join notifications, or {@code null}
     */
    public String getNotifyPermission()
    {
        return notifyPermission;
    }

    /**
     * @return {@link #setSuppressUpToDateConsoleMessage(boolean)}
     */
    public boolean isSuppressUpToDateConsoleMessage()
    {
        return suppressUpToDateConsoleMessage;
    }

    /**
     * @return {@link #setNotifyRequestorsWhenUpToDate(boolean)}
     */
    public boolean isNotifyRequestorsWhenUpToDate()
    {
        return notifyRequestorsWhenUpToDate;
    }

    /**
     * @return {@link #setColoredConsoleOutput(boolean)}
     */
    public boolean isColoredConsoleOutput()
    {
        return coloredConsoleOutput;
    }

    /**
     * Main-thread completion path after a successful supplier call: updates state, invokes success callback, fires event.
     *
     * @param installed   installed version string for this run
     * @param remote      trimmed remote version
     * @param requestors  frozen requestor list for callbacks/messaging
     */
    private void finishSuccess(String installed, String remote, List<CommandSender> requestors)
    {
        atLeastOneCheckCompleted = true;
        latestVersionResolved = remote;
        boolean remoteIsNewer = VersionCompare.isRemoteNewerThanInstalled(installed, remote);
        UpdateCheckResult checkResult = remoteIsNewer
                ? UpdateCheckResult.NEW_VERSION_AVAILABLE
                : UpdateCheckResult.RUNNING_LATEST_VERSION;
        lastCheckResult = checkResult;
        if (onSuccess != null)
        {
            onSuccess.onSuccess(requestors, remote);
        }
        Bukkit.getPluginManager().callEvent(new PluginUpdateCheckEvent(
                this, true, checkResult, installed, remote, requestors));
    }

    /**
     * Main-thread completion path after supplier failure: records {@link UpdateCheckResult#UNKNOWN}, notifies failure callback, fires event.
     *
     * @param installed  installed version string for this run
     * @param error      throwable from the supplier
     * @param requestors frozen requestor list
     */
    private void finishFailure(String installed, Throwable error, List<CommandSender> requestors)
    {
        atLeastOneCheckCompleted = true;
        lastCheckResult = UpdateCheckResult.UNKNOWN;
        onFailure.accept(error);
        Bukkit.getPluginManager().callEvent(new PluginUpdateCheckEvent(
                this, false, UpdateCheckResult.UNKNOWN, installed, null, requestors));
    }

    /**
     * Builds the User-Agent for HTTP requests: either {@link #userAgentOverride} or a default string with optional extras.
     * <p>
     * Invoked from async code via supplier wrappers; reads current field values each time.
     *
     * @return non-null header value
     */
    private String buildUserAgent()
    {
        if (userAgentOverride != null)
        {
            return userAgentOverride;
        }
        StringBuilder headerBuilder = new StringBuilder();
        headerBuilder.append("PluginUpdateChecker/").append(VERSION);
        headerBuilder.append(" (").append(plugin.getName()).append("/").append(getCurrentVersion());
        headerBuilder.append("; Minecraft/").append(plugin.getServer().getVersion());
        headerBuilder.append("; Bukkit/").append(plugin.getServer().getBukkitVersion());
        if (usingPaidVersion)
        {
            headerBuilder.append("; paid");
        }
        if (userAgentUserId != null && !userAgentUserId.isEmpty())
        {
            headerBuilder.append("; uid/").append(userAgentUserId);
        }
        for (String extraSegment : userAgentExtras.segments())
        {
            headerBuilder.append("; ").append(extraSegment);
        }
        headerBuilder.append(')');
        return headerBuilder.toString();
    }

    /**
     * Normalizes optional URL/permission strings: {@code null} or blank after trim becomes {@code null}.
     *
     * @param raw raw input
     * @return trimmed non-empty string, or {@code null}
     */
    private static String blankToNull(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Dispatches built-in chat/console output to {@link PluginUpdateCheckEvent#getRequestors()} when enabled.
     * Typically invoked from {@link GlobalListener} on the main thread.
     *
     * @param event completed check event for this checker
     */
    public void applyBuiltInRequestorNotifications(PluginUpdateCheckEvent event)
    {
        if (!notifyRequestorsOnCheck || event.getRequestors().isEmpty())
        {
            return;
        }
        for (CommandSender requestor : event.getRequestors())
        {
            if (requestor instanceof Player)
            {
                sendPlayerCheckMessage((Player) requestor, event, true);
            }
            else if (requestor instanceof ConsoleCommandSender)
            {
                sendConsoleCheckMessage(event, true);
            }
        }
    }

    /**
     * Join hook: eligible players may see update-available or failure-style messages (never “up to date” spam).
     *
     * @param player joining player
     */
    void notifyPlayerJoin(Player player)
    {
        if (!atLeastOneCheckCompleted)
        {
            return;
        }
        boolean operatorEligible = notifyOperatorsOnJoin && player.isOp();
        boolean permissionEligible = notifyPermission != null && player.hasPermission(notifyPermission);
        if (!operatorEligible && !permissionEligible)
        {
            return;
        }
        if (lastCheckResult == UpdateCheckResult.RUNNING_LATEST_VERSION)
        {
            return;
        }
        if (lastCheckResult == UpdateCheckResult.NEW_VERSION_AVAILABLE)
        {
            PluginUpdateCheckEvent updateAvailableEvent = new PluginUpdateCheckEvent(
                    this,
                    true,
                    UpdateCheckResult.NEW_VERSION_AVAILABLE,
                    getCurrentVersion(),
                    latestVersionResolved,
                    Collections.emptyList());
            sendPlayerCheckMessage(player, updateAvailableEvent, false);
            return;
        }
        PluginUpdateCheckEvent checkFailedEvent = new PluginUpdateCheckEvent(
                this,
                false,
                UpdateCheckResult.UNKNOWN,
                getCurrentVersion(),
                null,
                Collections.emptyList());
        sendPlayerCheckMessage(player, checkFailedEvent, false);
    }

    /**
     * Renders update / up-to-date / failure lines for a player using {@link PlayerMessageSenders}.
     *
     * @param player         recipient
     * @param event          outcome snapshot (possibly synthetic on join)
     * @param requestorFlow  {@code true} when the player explicitly requested the check (affects up-to-date messaging)
     */
    private void sendPlayerCheckMessage(Player player, PluginUpdateCheckEvent event, boolean requestorFlow)
    {
        if (!event.isSuccess())
        {
            PlayerMessageSenders.get().sendLine(player,
                    new ChatLinePart.Text("Could not check for updates for " + plugin.getName() + ".",
                            ChatColor.GRAY));
            return;
        }
        if (event.getResult() == UpdateCheckResult.NEW_VERSION_AVAILABLE)
        {
            String latestRemoteVersion = event.getLatestVersion();
            PlayerMessageSenders.get().sendLine(player,
                    new ChatLinePart.Text("Update available for " + plugin.getName() + ": ",
                            ChatColor.GRAY),
                    new ChatLinePart.Text(getCurrentVersion(), ChatColor.RED),
                    new ChatLinePart.Text(" -> ", ChatColor.GRAY),
                    new ChatLinePart.Text(latestRemoteVersion, ChatColor.GREEN));
            int linkIndex = 0;
            List<String> downloadLinks = getAppropriateDownloadLinks();
            for (String eachDownloadLink : downloadLinks)
            {
                String linkLabel = linkLabelForIndex(linkIndex++, downloadLinks.size());
                PlayerMessageSenders.get().sendLine(player, new ChatLinePart.Link(linkLabel, eachDownloadLink));
            }
            if (changelogUrl != null)
            {
                PlayerMessageSenders.get().sendLine(player, new ChatLinePart.Link("Changelog", changelogUrl));
            }
            if (donationUrl != null)
            {
                PlayerMessageSenders.get().sendLine(player, new ChatLinePart.Link("Donate", donationUrl));
            }
            if (supportUrl != null)
            {
                PlayerMessageSenders.get().sendLine(player, new ChatLinePart.Link("Support", supportUrl));
            }
            return;
        }
        if (event.getResult() == UpdateCheckResult.RUNNING_LATEST_VERSION)
        {
            if (requestorFlow && notifyRequestorsWhenUpToDate)
            {
                PlayerMessageSenders.get().sendLine(player,
                        new ChatLinePart.Text("You are running the latest version of " + plugin.getName()
                                + " (" + getCurrentVersion() + ").", ChatColor.GRAY));
            }
            return;
        }
        PlayerMessageSenders.get().sendLine(player,
                new ChatLinePart.Text("Could not determine update status for " + plugin.getName() + ".",
                        ChatColor.GRAY));
    }

    /**
     * Chooses a human-readable label for the n-th download URL in a multi-link message.
     *
     * @param index zero-based index in the link list being labeled
     * @param total number of download URLs in this message
     * @return label text for chat/console
     */
    private String linkLabelForIndex(int index, int total)
    {
        List<String> configuredDownloadLinks = getAppropriateDownloadLinks();
        if (total == 1)
        {
            return "Download";
        }
        if (downloadFreeUrl != null && downloadPaidUrl != null && configuredDownloadLinks.size() >= 2)
        {
            return index == 0 ? downloadPaidLabel : downloadFreeLabel;
        }
        return index == 0 ? "Download" : "Download " + (index + 1);
    }

    /**
     * Writes short log lines or the bordered update box for the console requestor path.
     *
     * @param event          completed check
     * @param requestorFlow  {@code true} when invoked for an explicit requestor (affects up-to-date info line)
     */
    private void sendConsoleCheckMessage(PluginUpdateCheckEvent event, boolean requestorFlow)
    {
        if (!event.isSuccess())
        {
            plugin.getLogger().warning("Could not check for updates for " + plugin.getName() + ".");
            return;
        }
        if (event.getResult() == UpdateCheckResult.NEW_VERSION_AVAILABLE)
        {
            logConsoleUpdateBox(event.getLatestVersion());
            return;
        }
        if (event.getResult() == UpdateCheckResult.RUNNING_LATEST_VERSION)
        {
            if (!suppressUpToDateConsoleMessage && requestorFlow)
            {
                plugin.getLogger().info("You are using the latest version of " + plugin.getName()
                        + " (" + getCurrentVersion() + ").");
            }
            return;
        }
        plugin.getLogger().warning("Could not determine update status for " + plugin.getName() + ".");
    }

    /**
     * Prints the asterisk-bordered warning block for an available update (download/support/donate only-no changelog body).
     *
     * @param latest remote version string to display
     */
    private void logConsoleUpdateBox(String latest)
    {
        String installedVersion = getCurrentVersion();
        String border = repeat('*', 60);
        java.util.logging.Logger log = plugin.getLogger();
        log.warning(border);
        if (coloredConsoleOutput)
        {
            log.warning(ChatColor.RED + "Update available: " + ChatColor.RESET + plugin.getName());
            log.warning(ChatColor.GREEN + "Your version: " + ChatColor.RESET + installedVersion
                    + ChatColor.RED + "  Latest: " + ChatColor.RESET + latest);
        }
        else
        {
            log.warning("Update available: " + plugin.getName());
            log.warning("Your version: " + installedVersion + "  Latest: " + latest);
        }
        log.warning("Please update " + plugin.getName());
        List<String> downloadUrlsForConsole = getAppropriateDownloadLinks();
        int linkIndex = 0;
        for (String consoleDownloadUrl : downloadUrlsForConsole)
        {
            String consoleLinkLabel = linkLabelForIndex(linkIndex++, downloadUrlsForConsole.size());
            log.warning(consoleLinkLabel + ": " + consoleDownloadUrl);
        }
        if (supportUrl != null)
        {
            log.warning("Support: " + supportUrl);
        }
        if (donationUrl != null)
        {
            log.warning("Donate: " + donationUrl);
        }
        log.warning(border);
    }

    /**
     * @param character character to repeat
     * @param count     number of repetitions (non-negative; callers use small constants)
     * @return a string of length {@code count} filled with {@code character}
     */
    private static String repeat(char character, int count)
    {
        char[] repeatedChars = new char[count];
        java.util.Arrays.fill(repeatedChars, character);
        return new String(repeatedChars);
    }

    /**
     * Single Bukkit listener registered once: forwards check events to built-in messaging and join events to all weakly held checkers.
     */
    private static final class GlobalListener implements Listener
    {
        /**
         * Delegates to {@link PluginUpdateChecker#applyBuiltInRequestorNotifications(PluginUpdateCheckEvent)} on the owning checker.
         *
         * @param event fired after a check completes
         */
        @EventHandler
        public void onCheck(PluginUpdateCheckEvent event)
        {
            event.getChecker().applyBuiltInRequestorNotifications(event);
        }

        /**
         * Copies {@link #ACTIVE} under synchronization and notifies each checker for the joining player.
         *
         * @param event join event
         */
        @EventHandler
        public void onJoin(PlayerJoinEvent event)
        {
            List<PluginUpdateChecker> snapshot;
            synchronized (ACTIVE)
            {
                snapshot = new ArrayList<>(ACTIVE);
            }
            for (PluginUpdateChecker activeChecker : snapshot)
            {
                activeChecker.notifyPlayerJoin(event.getPlayer());
            }
        }
    }
}
