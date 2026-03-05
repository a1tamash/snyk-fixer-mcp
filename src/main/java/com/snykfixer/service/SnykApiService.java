package com.snykfixer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.snykfixer.config.SnykApiProperties;
import com.snykfixer.model.FilterCriteria;
import com.snykfixer.model.SnykIssue;
import com.snykfixer.model.SnykProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SnykApiService {

    private static final Logger log = LoggerFactory.getLogger(SnykApiService.class);
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("project/([a-f0-9-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORG_PATTERN = Pattern.compile("org/([^/]+)");
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+[\\d.]*)");

    private final WebClient snykWebClient;
    private final SnykApiProperties props;

    public SnykApiService(WebClient snykWebClient, SnykApiProperties props) {
        this.snykWebClient = snykWebClient;
        this.props = props;
    }

    public List<SnykProject> listProjects() {
        String path = "/rest/orgs/%s/projects?version=%s&limit=100"
                .formatted(props.orgId(), props.restVersion());

        JsonNode response = snykWebClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        List<SnykProject> projects = new ArrayList<>();
        if (response == null || !response.has("data")) return projects;

        for (JsonNode node : response.get("data")) {
            JsonNode attrs = node.path("attributes");
            String repoUrl = node.path("relationships").path("target")
                    .path("data").path("attributes").path("url").asText("");

            projects.add(new SnykProject(
                    node.path("id").asText(),
                    attrs.path("name").asText(""),
                    attrs.path("origin").asText(""),
                    attrs.path("type").asText(""),
                    repoUrl
            ));
        }
        return projects;
    }

    public Optional<SnykProject> findProjectByRepoUrl(String repoUrl) {
        List<SnykProject> projects = listProjects();
        String normalized = repoUrl.replaceAll("\\.git$", "").replaceAll("/$", "").toLowerCase();

        return projects.stream()
                .filter(p -> {
                    String projectUrl = (p.remoteRepoUrl().isEmpty() ? p.name() : p.remoteRepoUrl())
                            .replaceAll("\\.git$", "").replaceAll("/$", "").toLowerCase();
                    return projectUrl.contains(normalized) || normalized.contains(projectUrl);
                })
                .findFirst();
    }

    public List<SnykIssue> getIssuesForProject(String projectId, FilterCriteria filters) {
        StringBuilder pathBuilder = new StringBuilder();
        pathBuilder.append("/rest/orgs/%s/issues?version=%s&scan_item.id=%s&scan_item.type=project&limit=100"
                .formatted(props.orgId(), props.restVersion(), projectId));

        if (filters != null && !filters.severity().isEmpty()) {
            pathBuilder.append("&severity=").append(String.join(",", filters.severity()));
        }

        List<SnykIssue> allIssues = new ArrayList<>();
        String nextPath = pathBuilder.toString();

        while (nextPath != null) {
            JsonNode response = snykWebClient.get()
                    .uri(nextPath)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("data")) break;

            for (JsonNode issueNode : response.get("data")) {
                SnykIssue issue = mapIssue(issueNode);

                if (filters != null && filters.fixableOnly() && !issue.fixable()) continue;
                if (filters != null && !filters.exploitMaturity().isEmpty()
                        && !filters.exploitMaturity().contains(issue.exploitMaturity())) continue;

                allIssues.add(issue);
            }

            nextPath = response.has("links") && response.get("links").has("next")
                    ? response.get("links").get("next").asText(null)
                    : null;
        }

        return allIssues;
    }

    public List<SnykIssue> getIssuesFromReportUrl(String reportUrl) {
        Matcher projectMatcher = PROJECT_ID_PATTERN.matcher(reportUrl);
        if (projectMatcher.find()) {
            return getIssuesForProject(projectMatcher.group(1), null);
        }

        Matcher orgMatcher = ORG_PATTERN.matcher(reportUrl);
        if (orgMatcher.find()) {
            return getIssuesForOrg();
        }

        throw new IllegalArgumentException("Could not extract project or org info from the Snyk report URL: " + reportUrl);
    }

    private List<SnykIssue> getIssuesForOrg() {
        String path = "/rest/orgs/%s/issues?version=%s&limit=100"
                .formatted(props.orgId(), props.restVersion());

        JsonNode response = snykWebClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        List<SnykIssue> issues = new ArrayList<>();
        if (response == null || !response.has("data")) return issues;

        for (JsonNode node : response.get("data")) {
            issues.add(mapIssue(node));
        }
        return issues;
    }

    private SnykIssue mapIssue(JsonNode issueNode) {
        JsonNode attrs = issueNode.path("attributes");
        JsonNode coordinates = attrs.path("coordinates");
        JsonNode firstCoord = coordinates.isArray() && !coordinates.isEmpty() ? coordinates.get(0) : null;
        JsonNode remedies = firstCoord != null ? firstCoord.path("remedies") : null;
        JsonNode firstRemedy = remedies != null && remedies.isArray() && !remedies.isEmpty() ? remedies.get(0) : null;

        String packageName = "";
        if (attrs.has("problems") && attrs.get("problems").isArray() && !attrs.get("problems").isEmpty()) {
            packageName = attrs.get("problems").get(0).path("source").asText("");
        }
        if (packageName.isEmpty() && firstCoord != null) {
            JsonNode repr = firstCoord.path("representation");
            if (repr.isArray() && !repr.isEmpty()) {
                packageName = repr.get(0).asText("");
            }
        }

        String currentVersion = "";
        if (firstCoord != null) {
            JsonNode repr = firstCoord.path("representation");
            if (repr.isArray() && !repr.isEmpty()) {
                currentVersion = repr.get(0).asText("");
            }
        }

        String fixedIn = firstRemedy != null ? firstRemedy.path("description").asText(null) : null;

        return new SnykIssue(
                issueNode.path("id").asText(),
                attrs.path("title").asText(""),
                attrs.path("effective_severity_level").asText(attrs.path("severity").asText("medium")),
                packageName,
                currentVersion,
                fixedIn,
                attrs.path("exploit_maturity").asText("no-known-exploit"),
                firstRemedy != null,
                attrs.path("description").asText(""),
                attrs.has("cvss_score") ? attrs.get("cvss_score").asDouble() : null,
                attrs.path("language").asText("java"),
                attrs.path("package_manager").asText("maven")
        );
    }

    public static String parseVersionFromFixedIn(String fixedIn) {
        if (fixedIn == null || fixedIn.isBlank()) return null;
        Matcher m = VERSION_PATTERN.matcher(fixedIn);
        return m.find() ? m.group(1) : null;
    }
}
