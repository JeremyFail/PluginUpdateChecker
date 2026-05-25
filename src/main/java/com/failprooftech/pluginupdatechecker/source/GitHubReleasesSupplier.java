package com.failprooftech.pluginupdatechecker.source;

import com.failprooftech.pluginupdatechecker.RemoteVersionSupplier;
import com.failprooftech.pluginupdatechecker.http.HttpFetch;
import mjson.Json;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link RemoteVersionSupplier} that calls the GitHub REST API for release metadata and returns a {@code tag_name}.
 * <p>
 * When {@link #includePrereleases} is {@code false} (the default), uses
 * {@code GET /repos/{owner}/{repo}/releases/latest} — the same notion as GitHub's "Latest" release
 * (non-draft, non-prerelease). When {@code true}, uses {@code GET /repos/{owner}/{repo}/releases} and reads
 * {@code tag_name} from the first element (newest by {@code created_at}, including pre-releases).
 */
public final class GitHubReleasesSupplier implements RemoteVersionSupplier
{

    /** GitHub organization or user name (first segment of {@code owner/repo}). */
    private final String owner;
    /** Repository name (second segment of {@code owner/repo}). */
    private final String repo;
    /** Resolved when {@link #fetchLatestVersion()} runs so timeouts/User-Agent stay current. */
    private final Supplier<String> userAgentSupplier;
    /** Resolved when {@link #fetchLatestVersion()} runs; milliseconds for HTTP connect timeout. */
    private final Supplier<Integer> connectTimeoutMsSupplier;
    /**
     * When {@code true}, the newest created release is used (may be a pre-release).
     * When {@code false}, only the latest stable release is used.
     */
    private final boolean includePrereleases;

    /**
     * Equivalent to {@link #GitHubReleasesSupplier(String, Supplier, Supplier, boolean)} with
     * {@code includePrereleases = false}.
     */
    public GitHubReleasesSupplier(String ownerRepo, Supplier<String> userAgentSupplier,
                                  Supplier<Integer> connectTimeoutMsSupplier)
    {
        this(ownerRepo, userAgentSupplier, connectTimeoutMsSupplier, false);
    }

    /**
     * Parses {@code owner/repo}, stores suppliers for live HTTP settings, and validates the slug format.
     *
     * @param ownerRepo                  string {@code owner/repo} (e.g. {@code PaperMC/Paper})
     * @param userAgentSupplier          supplies User-Agent for {@link HttpFetch#get(String, String, int)}
     * @param connectTimeoutMsSupplier   supplies connect timeout in ms ({@code 0} = JVM default)
     * @param includePrereleases         when {@code true}, use the newest release including pre-releases;
     *                                   when {@code false}, use GitHub's latest stable release only
     * @throws IllegalArgumentException if {@code ownerRepo} is not exactly two non-empty segments
     * @throws NullPointerException     if {@code ownerRepo} or either supplier is null
     */
    public GitHubReleasesSupplier(String ownerRepo, Supplier<String> userAgentSupplier,
                                  Supplier<Integer> connectTimeoutMsSupplier, boolean includePrereleases)
    {
        Objects.requireNonNull(ownerRepo, "ownerRepo");
        String[] slugSegments = ownerRepo.split("/", 2);
        if (slugSegments.length != 2 || slugSegments[0].isEmpty() || slugSegments[1].isEmpty())
        {
            throw new IllegalArgumentException("ownerRepo must be 'owner/repo'");
        }
        this.owner = slugSegments[0];
        this.repo = slugSegments[1];
        this.userAgentSupplier = Objects.requireNonNull(userAgentSupplier);
        this.connectTimeoutMsSupplier = Objects.requireNonNull(connectTimeoutMsSupplier);
        this.includePrereleases = includePrereleases;
    }

    /**
     * @return whether pre-releases are included when resolving the remote version
     */
    public boolean isIncludePrereleases()
    {
        return includePrereleases;
    }

    /**
     * {@inheritDoc}
     *
     * @return trimmed {@code tag_name}
     * @throws IOException if HTTP fails, JSON is invalid, no suitable release exists, or {@code tag_name} is missing/blank
     */
    @Override
    public String fetchLatestVersion() throws IOException
    {
        if (includePrereleases)
        {
            return tagNameFromNewestCreatedRelease();
        }
        return tagNameFromLatestStableRelease();
    }

    /**
     * {@code GET /repos/{owner}/{repo}/releases/latest} — latest non-draft, non-prerelease release.
     */
    private String tagNameFromLatestStableRelease() throws IOException
    {
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";
        String responseBody = HttpFetch.get(apiUrl, userAgentSupplier.get(), connectTimeoutMsSupplier.get());
        final Json release;
        try
        {
            release = Json.read(responseBody);
        }
        catch (Json.MalformedJsonException e)
        {
            throw new IOException("invalid JSON from GitHub", e);
        }
        if (!release.isObject())
        {
            throw new IOException("JSON: expected release object from GitHub /releases/latest");
        }
        return tagNameFromReleaseObject(release, "GitHub /releases/latest");
    }

    /**
     * {@code GET /repos/{owner}/{repo}/releases} — first array element (newest {@code created_at}, any prerelease flag).
     */
    private String tagNameFromNewestCreatedRelease() throws IOException
    {
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/releases";
        String responseBody = HttpFetch.get(apiUrl, userAgentSupplier.get(), connectTimeoutMsSupplier.get());
        final Json jsonRoot;
        try
        {
            jsonRoot = Json.read(responseBody);
        }
        catch (Json.MalformedJsonException e)
        {
            throw new IOException("invalid JSON from GitHub", e);
        }
        if (!jsonRoot.isArray())
        {
            throw new IOException("JSON: expected array of releases");
        }
        List<Json> releases = jsonRoot.asJsonList();
        if (releases.isEmpty())
        {
            throw new IOException("no GitHub release found");
        }
        Json firstRelease = releases.get(0);
        if (!firstRelease.isObject())
        {
            throw new IOException("JSON: expected object at releases[0]");
        }
        return tagNameFromReleaseObject(firstRelease, "GitHub releases[0]");
    }

    private static String tagNameFromReleaseObject(Json release, String sourceLabel) throws IOException
    {
        if (!release.has("tag_name") || !release.at("tag_name").isString())
        {
            throw new IOException("missing or non-string tag_name from " + sourceLabel);
        }
        String tagName = release.at("tag_name").asString();
        if (tagName.trim().isEmpty())
        {
            throw new IOException("empty tag_name from " + sourceLabel);
        }
        return tagName.trim();
    }
}
