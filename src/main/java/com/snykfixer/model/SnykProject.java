package com.snykfixer.model;

public record SnykProject(
        String id,
        String name,
        String origin,
        String type,
        String remoteRepoUrl
) {}
