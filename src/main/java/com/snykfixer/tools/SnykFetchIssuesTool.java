package com.snykfixer.tools;

import com.snykfixer.model.FilterCriteria;
import com.snykfixer.model.SnykIssue;
import com.snykfixer.model.SnykProject;
import com.snykfixer.service.SnykApiService;
import org.springframework.ai.tool.annotation.McpTool;
import org.springframework.ai.tool.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class SnykFetchIssuesTool {

    private final SnykApiService snykApi;

    public SnykFetchIssuesTool(SnykApiService snykApi) {
        this.snykApi = snykApi;
    }

    @McpTool(
            name = "fetch_snyk_issues_by_repo",
            description = """
                    Fetch all Snyk security vulnerability issues for a given Git repository URL.
                    Returns a list of vulnerabilities with severity, package name, current version,
                    fix version, exploit maturity, and fixability status.
                    Use this when the user provides a git repo URL like 'https://github.com/org/repo'.
                    """
    )
    public String fetchIssuesByRepo(
            @McpToolParam(description = "Git repository URL, e.g. https://github.com/org/repo", required = true)
            String repoUrl,
            @McpToolParam(description = "Comma-separated severity filter: critical,high,medium,low. Leave empty for all.", required = false)
            String severityFilter,
            @McpToolParam(description = "If true, only return issues that have a known fix", required = false)
            Boolean fixableOnly,
            @McpToolParam(description = "Comma-separated exploit maturity filter: mature,proof-of-concept,no-known-exploit", required = false)
            String exploitMaturityFilter
    ) {
        Optional<SnykProject> project = snykApi.findProjectByRepoUrl(repoUrl);
        if (project.isEmpty()) {
            return "ERROR: No Snyk project found for repository URL: " + repoUrl
                    + "\nMake sure the repository is imported into Snyk and the URL matches.";
        }

        FilterCriteria filters = buildFilters(severityFilter, fixableOnly, exploitMaturityFilter);
        List<SnykIssue> issues = snykApi.getIssuesForProject(project.get().id(), filters);

        return formatIssues(issues, repoUrl);
    }

    @McpTool(
            name = "fetch_snyk_issues_by_report",
            description = """
                    Fetch all Snyk security vulnerability issues using a Snyk report URL.
                    The URL should be a direct link to a Snyk project report, e.g.
                    https://app.snyk.io/org/my-org/project/abc-123-def
                    Returns a list of vulnerabilities with all details needed for remediation.
                    """
    )
    public String fetchIssuesByReport(
            @McpToolParam(description = "Snyk report URL", required = true)
            String reportUrl
    ) {
        List<SnykIssue> issues = snykApi.getIssuesFromReportUrl(reportUrl);
        return formatIssues(issues, reportUrl);
    }

    private FilterCriteria buildFilters(String severity, Boolean fixableOnly, String exploitMaturity) {
        List<String> severities = severity != null && !severity.isBlank()
                ? List.of(severity.split(",")) : List.of();
        List<String> exploits = exploitMaturity != null && !exploitMaturity.isBlank()
                ? List.of(exploitMaturity.split(",")) : List.of();
        return new FilterCriteria(severities, fixableOnly != null && fixableOnly, exploits);
    }

    private String formatIssues(List<SnykIssue> issues, String source) {
        if (issues.isEmpty()) {
            return "No vulnerabilities found for: " + source;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found %d vulnerabilities for: %s\n\n".formatted(issues.size(), source));

        long critical = issues.stream().filter(i -> "critical".equals(i.severity())).count();
        long high = issues.stream().filter(i -> "high".equals(i.severity())).count();
        long medium = issues.stream().filter(i -> "medium".equals(i.severity())).count();
        long low = issues.stream().filter(i -> "low".equals(i.severity())).count();

        sb.append("SUMMARY: %d critical | %d high | %d medium | %d low\n\n".formatted(critical, high, medium, low));

        for (int i = 0; i < issues.size(); i++) {
            SnykIssue issue = issues.get(i);
            sb.append("--- Issue %d ---\n".formatted(i + 1));
            sb.append("Title: %s\n".formatted(issue.title()));
            sb.append("Severity: %s\n".formatted(issue.severity().toUpperCase()));
            sb.append("Package: %s\n".formatted(issue.packageName()));
            sb.append("Current Version: %s\n".formatted(issue.currentVersion()));
            sb.append("Fixed In: %s\n".formatted(issue.fixedIn() != null ? issue.fixedIn() : "No fix available"));
            sb.append("Fixable: %s\n".formatted(issue.fixable() ? "Yes" : "No"));
            sb.append("Exploit Maturity: %s\n".formatted(issue.exploitMaturity()));
            if (issue.cvssScore() != null) {
                sb.append("CVSS Score: %.1f\n".formatted(issue.cvssScore()));
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
