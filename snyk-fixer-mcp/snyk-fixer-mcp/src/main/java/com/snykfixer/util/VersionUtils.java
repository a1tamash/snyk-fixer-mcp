package com.snykfixer.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionUtils {

    private static final Pattern SEMVER_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private VersionUtils() {}

    public static boolean isMinorOrPatchUpgrade(String currentVersion, String targetVersion) {
        int[] current = parseMajorMinorPatch(currentVersion);
        int[] target = parseMajorMinorPatch(targetVersion);
        if (current == null || target == null) return false;
        return current[0] == target[0];
    }

    public static boolean isSpringBootMajorUpgrade(String packageName, String currentVersion, String targetVersion) {
        if (packageName == null || !packageName.toLowerCase().contains("spring-boot")) return false;
        int[] current = parseMajorMinorPatch(currentVersion);
        int[] target = parseMajorMinorPatch(targetVersion);
        if (current == null || target == null) return false;
        return current[0] != target[0];
    }

    private static int[] parseMajorMinorPatch(String version) {
        if (version == null || version.isBlank()) return null;
        Matcher m = SEMVER_PATTERN.matcher(version);
        if (!m.find()) return null;
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        return new int[]{major, minor, patch};
    }

    public static String parseVersionFromFixedIn(String fixedIn) {
        if (fixedIn == null || fixedIn.isBlank()) return null;
        Matcher m = Pattern.compile("(\\d+\\.\\d+[\\d.]*)").matcher(fixedIn);
        return m.find() ? m.group(1) : null;
    }
}
