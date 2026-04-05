package com.failprooftech.pluginupdatechecker.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Small helper for HTTP GET requests used by built-in {@link com.failprooftech.pluginupdatechecker.RemoteVersionSupplier}
 * implementations. Uses {@link HttpURLConnection} only (no external HTTP client).
 */
public final class HttpFetch
{

    /**
     * Prevents instantiation.
     */
    private HttpFetch()
    {
    }

    /**
     * Performs a GET request, reads the entire response body as UTF-8 text, and disconnects the connection.
     * <p>
     * Non-2xx status codes cause an {@link IOException} without reading a body. A {@code finally} block always
     * calls {@link HttpURLConnection#disconnect()} after a successful response code so connections are released.
     *
     * @param urlString         absolute HTTP(S) URL
     * @param userAgent         value for the {@code User-Agent} header; if null or empty, the header is omitted
     * @param connectTimeoutMs  if {@code &gt; 0}, passed to {@link HttpURLConnection#setConnectTimeout(int)}; read timeout is not set
     * @return full response body as a string (may include multiple lines)
     * @throws IOException if the URL is invalid, connection fails, status is not 2xx, or reading the stream fails
     */
    public static String get(String urlString, String userAgent, int connectTimeoutMs) throws IOException
    {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (userAgent != null && !userAgent.isEmpty())
        {
            conn.setRequestProperty("User-Agent", userAgent);
        }
        if (connectTimeoutMs > 0)
        {
            conn.setConnectTimeout(connectTimeoutMs);
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300)
        {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + urlString);
        }
        try (BufferedReader responseReader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)))
        {
            StringBuilder bodyBuilder = new StringBuilder();
            String responseLine;
            while ((responseLine = responseReader.readLine()) != null)
            {
                bodyBuilder.append(responseLine).append('\n');
            }
            return bodyBuilder.toString();
        }
        finally
        {
            conn.disconnect();
        }
    }
}
