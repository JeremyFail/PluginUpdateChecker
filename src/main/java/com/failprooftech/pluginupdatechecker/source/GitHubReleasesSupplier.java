package com.failprooftech.pluginupdatechecker.source;

import com.failprooftech.pluginupdatechecker.RemoteVersionSupplier;
import com.failprooftech.pluginupdatechecker.http.HttpFetch;
import mjson.Json;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link RemoteVersionSupplier} that calls the GitHub REST API {@code GET /repos/{owner}/{repo}/releases}
 * and reads {@code tag_name} from the <strong>first</strong> element of the JSON array.
 * <p>
 * An empty releases array produces {@link IOException} with message {@code no GitHub release found}.
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
     * Parses {@code owner/repo}, stores suppliers for live HTTP settings, and validates the slug format.
     *
     * @param ownerRepo                  string {@code owner/repo} (e.g. {@code PaperMC/Paper})
     * @param userAgentSupplier          supplies User-Agent for {@link HttpFetch#get(String, String, int)}
     * @param connectTimeoutMsSupplier   supplies connect timeout in ms ({@code 0} = JVM default)
     * @throws IllegalArgumentException if {@code ownerRepo} is not exactly two non-empty segments
     * @throws NullPointerException     if {@code ownerRepo} or either supplier is null
     */
    public GitHubReleasesSupplier(String ownerRepo, Supplier<String> userAgentSupplier,
                                  Supplier<Integer> connectTimeoutMsSupplier)
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
    }

    /**
     * {@inheritDoc}
     * <p>
     * Parses the body with {@link Json#read(String)} (mJson) and reads {@code tag_name} from the first array element.
     * Does not filter draft or prerelease entries (first list element only).
     *
     * @return trimmed {@code tag_name}
     * @throws IOException if HTTP fails, JSON is invalid, the array is empty, or {@code tag_name} is missing/blank
     */
    @Override
    public String fetchLatestVersion() throws IOException
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
        if (!firstRelease.has("tag_name") || !firstRelease.at("tag_name").isString())
        {
            throw new IOException("missing or non-string tag_name from GitHub");
        }
        String tagName = firstRelease.at("tag_name").asString();
        if (tagName.trim().isEmpty())
        {
            throw new IOException("empty tag_name from GitHub");
        }
        return tagName.trim();
    }
}
