package com.failprooftech.pluginupdatechecker;

import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * Utility for ordering version strings the same way Apache Maven does (segments, qualifiers, etc.).
 * Used by {@link PluginUpdateChecker} to decide whether a remote tag is genuinely newer than the installed plugin.
 */
public final class VersionCompare
{

    /**
     * Prevents instantiation.
     */
    private VersionCompare()
    {
    }

    /**
     * Compares two version strings after trimming. Returns {@code false} for null/blank inputs to avoid
     * false “update available” results from bad data.
     *
     * @param installed local version (e.g. from {@code plugin.yml})
     * @param remote      version reported by the update source
     * @return {@code true} only if {@code remote} is strictly greater than {@code installed} per {@link ComparableVersion}
     */
    public static boolean isRemoteNewerThanInstalled(String installed, String remote)
    {
        if (installed == null || remote == null)
        {
            return false;
        }
        String installedTrimmed = installed.trim();
        String remoteTrimmed = remote.trim();
        if (installedTrimmed.isEmpty() || remoteTrimmed.isEmpty())
        {
            return false;
        }
        ComparableVersion installedComparable = new ComparableVersion(installedTrimmed);
        ComparableVersion remoteComparable = new ComparableVersion(remoteTrimmed);
        return remoteComparable.compareTo(installedComparable) > 0;
    }
}
