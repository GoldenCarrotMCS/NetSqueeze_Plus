package me.albert.netsqueeze.protocol;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Version eligibility is not proof of binary compatibility; runtime probes and CI are separate. */
public final class MinecraftVersions {
    private static final Pattern RELEASE = Pattern.compile("([0-9]+)[.]([0-9]+)(?:[.]([0-9]+))?");
    public static final Set<String> CI_VERSIONS = Set.of("26.1", "26.1.1", "26.1.2", "26.2", "26.3");

    private MinecraftVersions() { }

    public static boolean eligible(String version) {
        if (version == null) return false;
        Matcher match = RELEASE.matcher(version);
        if (!match.matches()) return false;
        try { return Integer.parseInt(match.group(1)) >= 26; }
        catch (NumberFormatException ignored) { return false; }
    }

    public static boolean protocolMatches(int server, int client) {
        // Snapshot protocol numbers must not silently opt into the release wire format.
        return server > 0 && server < 0x40000000 && server == client;
    }
}
