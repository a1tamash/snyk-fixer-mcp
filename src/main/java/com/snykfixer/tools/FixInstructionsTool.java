package com.snykfixer.tools;

import com.snykfixer.model.FixInstruction;
import com.snykfixer.model.SnykIssue;
import com.snykfixer.model.SnykProject;
import com.snykfixer.service.SnykApiService;
import com.snykfixer.util.VersionUtils;
import org.springframework.ai.tool.annotation.McpTool;
import org.springframework.ai.tool.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class FixInstructionsTool {

    private final SnykApiService snykApi;

    public FixInstructionsTool(SnykApiService snykApi) {
        this.snykApi = snykApi;
    }

    @McpTool(
            name = "get_fix_instructions",
            description = """
                    Generate structured fix instructions for Snyk vulnerabilities in a Java Maven project.
                    This tool analyzes each vulnerability and produces safe, actionable instructions:
                    - Only minor/patch version upgrades are recommended (no major version bumps)
                    - Spring Boot major version upgrades are explicitly blocked to avoid breaking changes
                    - Each instruction tells you exactly which dependency in pom.xml to update and to what version

                    IMPORTANT GUARDRAILS:
                    - Only minor version increases are applied
                    - Major Spring Boot version changes are SKIPPED (e.g. 2.x -> 3.x)
                    - This covers 80%+ of real vulnerability fixes safely

                    After getting instructions, you should:
                    1. Open the project's pom.xml
                    2. Update each dependency version as instructed
                    3. Run 'verify_maven_build' to ensure nothing is broken
                    4. If build passes, run 'create_pull_request' to submit changes
                    """
    )
    public String getFixInstructions(
            @McpToolParam(description = "Git repository URL", required = true)
            String repoUrl,
            @McpToolParam(description = "Path to the project's pom.xml file (relative to repo root, default: pom.xml)", required = false)
            String pomPath,
            @McpToolParam(description = "Comma-separated severity filter: critical,high,medium,low", required = false)
            String severityFilter,
            @McpToolParam(description = "If true, only return fixable issues", required = false)
            Boolean fixableOnly
    ) {
        Optional<SnykProject> project = snykApi.findProjectByRepoUrl(repoUrl);
        if (project.isEmpty()) {
            return "ERROR: No Snyk project found for: " + repoUrl;
        }

        List<String> severities = severityFilter != null && !severityFilter.isBlank()
                ? List.of(severityFilter.split(",")) : List.of();

        var filters = new com.snykfixer.model.FilterCriteria(
                severities, fixableOnly != null && fixableOnly, List.of());

        List<SnykIssue> issues = snykApi.getIssuesForProject(project.get().id(), filters);
        String effectivePomPath = pomPath != null && !pomPath.isBlank() ? pomPath : "pom.xml";

        List<FixInstruction> instructions = new ArrayList<>();
        List<SnykIssue> skipped = new ArrayList<>();

        for (SnykIssue issue : issues) {
            if (!issue.fixable() || issue.fixedIn() == null) {
                skipped.add(issue);
                continue;
            }

            String targetVersion = VersionUtils.parseVersionFromFixedIn(issue.fixedIn());
            if (targetVersion == null) {
                skipped.add(issue);
                continue;
            }

            boolean isMinor = VersionUtils.isMinorOrPatchUpgrade(issue.currentVersion(), targetVersion);
            boolean isSpringBootMajor = VersionUtils.isSpringBootMajorUpgrade(
                    issue.packageName(), issue.currentVersion(), targetVersion);

            if (isSpringBootMajor) {
                skipped.add(issue);
                continue;
            }

            instructions.add(new FixInstruction(
                    issue.packageName(),
                    issue.currentVersion(),
                    targetVersion,
                    effectivePomPath,
                    issue.severity(),
                    issue.title(),
                    isMinor,
                    isMinor && !isSpringBootMajor
            ));
        }

        return formatInstructions(instructions, skipped, repoUrl, effectivePomPath);
    }

    private String formatInstructions(List<FixInstruction> instructions, List<SnykIssue> skipped,
                                      String repoUrl, String pomPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== FIX INSTRUCTIONS FOR: %s ===\n\n".formatted(repoUrl));
        sb.append("Target file: %s\n".formatted(pomPath));
        sb.append("Total fixable: %d | Skipped: %d\n\n".formatted(instructions.size(), skipped.size()));

        if (instructions.isEmpty()) {
            sb.append("No safe fixes available. All issues either have no fix, require major version upgrades, ");
            sb.append("or involve Spring Boot major version changes.\n");
            return sb.toString();
        }

        sb.append("INSTRUCTIONS — Apply these dependency version changes to %s:\n\n".formatted(pomPath));

        for (int i = 0; i < instructions.size(); i++) {
            FixInstruction fix = instructions.get(i);
            sb.append("FIX %d:\n".formatted(i + 1));
            sb.append("  Issue: %s [%s]\n".formatted(fix.issueTitle(), fix.severity().toUpperCase()));
            sb.append("  Package: %s\n".formatted(fix.packageName()));
            sb.append("  Change version: %s -> %s\n".formatted(fix.currentVersion(), fix.targetVersion()));
            sb.append("  Minor upgrade: %s | Safe: %s\n".formatted(fix.minorUpgrade() ? "Yes" : "No", fix.safe() ? "Yes" : "REVIEW NEEDED"));
            sb.append("\n");
        }

        if (!skipped.isEmpty()) {
            sb.append("\n--- SKIPPED (requires manual review) ---\n\n");
            for (SnykIssue issue : skipped) {
                String reason = !issue.fixable() ? "No fix available"
                        : VersionUtils.isSpringBootMajorUpgrade(issue.packageName(), issue.currentVersion(),
                        VersionUtils.parseVersionFromFixedIn(issue.fixedIn()) != null
                                ? VersionUtils.parseVersionFromFixedIn(issue.fixedIn()) : "")
                        ? "Spring Boot major version upgrade (blocked)"
                        : "Could not determine safe target version";
                sb.append("  - %s (%s): %s\n".formatted(issue.title(), issue.severity(), reason));
            }
        }

        sb.append("\n--- NEXT STEPS ---\n");
        sb.append("1. Open %s and apply the version changes listed above\n".formatted(pomPath));
        sb.append("2. Run the 'verify_maven_build' tool to check the build passes\n");
        sb.append("3. If build succeeds, run 'create_pull_request' to submit the changes\n");

        return sb.toString();
    }
}
