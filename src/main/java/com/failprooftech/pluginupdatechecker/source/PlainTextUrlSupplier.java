package com.failprooftech.pluginupdatechecker.source;

import com.failprooftech.pluginupdatechecker.RemoteVersionSupplier;
import com.failprooftech.pluginupdatechecker.http.HttpFetch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link RemoteVersionSupplier} that GETs a plain-text document and returns the first non-empty line after
 * {@link String#trim()}. Suitable for a static file hosted on any HTTP(S) URL that contains only a version string.
 */
public final class PlainTextUrlSupplier implements RemoteVersionSupplier
{

    /** Validated HTTP(S) URL string passed to {@link com.failprooftech.pluginupdatechecker.http.HttpFetch}. */
    private final String url;
    /** Supplies User-Agent on each fetch. */
    private final Supplier<String> userAgentSupplier;
    /** Supplies connect timeout in milliseconds on each fetch. */
    private final Supplier<Integer> connectTimeoutMsSupplier;

    /**
     * Validates {@code url} by instantiating {@link java.net.URL} from the string, then stores suppliers.
     *
     * @param url                        HTTP(S) location of the text file
     * @param userAgentSupplier          supplies User-Agent for {@link HttpFetch}
     * @param connectTimeoutMsSupplier   supplies connect timeout in ms
     * @throws IllegalArgumentException if the URL string cannot be parsed
     * @throws NullPointerException     if {@code url} or either supplier is null
     */
    public PlainTextUrlSupplier(String url, Supplier<String> userAgentSupplier,
                                Supplier<Integer> connectTimeoutMsSupplier)
    {
        try
        {
            new URL(url);
        }
        catch (Exception parseFailure)
        {
            throw new IllegalArgumentException("invalid URL: " + url, parseFailure);
        }
        this.url = Objects.requireNonNull(url, "url");
        this.userAgentSupplier = Objects.requireNonNull(userAgentSupplier);
        this.connectTimeoutMsSupplier = Objects.requireNonNull(connectTimeoutMsSupplier);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Scans line-by-line; blank lines are skipped. If no non-empty line exists, throws {@link IOException}.
     *
     * @return first non-empty trimmed line
     * @throws IOException if GET fails or no suitable line exists
     */
    @Override
    public String fetchLatestVersion() throws IOException
    {
        String body = HttpFetch.get(url, userAgentSupplier.get(), connectTimeoutMsSupplier.get());
        try (BufferedReader lineReader = new BufferedReader(new StringReader(body)))
        {
            String rawLine;
            while ((rawLine = lineReader.readLine()) != null)
            {
                String trimmedLine = rawLine.trim();
                if (!trimmedLine.isEmpty())
                {
                    return trimmedLine;
                }
            }
        }
        throw new IOException("no non-empty line in plain text response");
    }
}
