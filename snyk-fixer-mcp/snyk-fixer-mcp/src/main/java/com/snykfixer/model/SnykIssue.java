package com.snykfixer.model;

public record SnykIssue(
        String id,
        String title,
        String severity,
        String packageName,
        String currentVersion,
        String fixedIn,
        String exploitMaturity,
        boolean fixable,
        String description,
        Double cvssScore,
        String language,
        String packageManager
) {}
