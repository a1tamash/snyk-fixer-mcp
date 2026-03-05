package com.snykfixer.model;

public record FixInstruction(
        String packageName,
        String currentVersion,
        String targetVersion,
        String pomPath,
        String severity,
        String issueTitle,
        boolean minorUpgrade,
        boolean safe
) {}
