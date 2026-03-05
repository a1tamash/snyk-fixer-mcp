package com.snykfixer.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.McpTool;
import org.springframework.ai.tool.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@Component
public class PullRequestTool {

    private static final Logger log = LoggerFactory.getLogger(PullRequestTool.class);

    @McpTool(
            name = "create_pull_request",
            description = """
                    Create a Git branch, commit the dependency changes, and raise a Pull Request.
                    
                    This tool:
                    1. Creates a new branch named 'fix/snyk-vulnerability-fixes-<timestamp>'
                    2. Stages all modified files (pom.xml changes)
                    3. Commits with a descriptive message listing the fixes applied
                    4. Pushes the branch to origin
                    5. Creates a PR using the GitHub CLI (gh)
                    
                    PREREQUISITES:
                    - Git must be configured with push access to the remote
                    - GitHub CLI (gh) must be installed and authenticated
                    - Run 'verify_maven_build' FIRST to ensure the build passes
                    
                    Call this ONLY after the build verification succeeds.
                    """
    )
    public String createPullRequest(
            @McpToolParam(description = "Absolute path to the project root directory", required = true)
            String projectPath,
            @McpToolParam(description = "PR title, e.g. 'fix: Resolve Snyk security vulnerabilities'", required = false)
            String title,
            @McpToolParam(description = "PR body/description with details of changes made", required = false)
            String body,
            @McpToolParam(description = "Base branch to merge into, default: main", required = false)
            String baseBranch
    ) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return "ERROR: Project directory does not exist: " + projectPath;
        }

        String effectiveTitle = title != null && !title.isBlank()
                ? title : "fix: Resolve Snyk security vulnerabilities";
        String effectiveBody = body != null && !body.isBlank()
                ? body : "Automated fix for Snyk-reported security vulnerabilities.\n\nOnly minor/patch version upgrades applied. No major version changes.";
        String effectiveBase = baseBranch != null && !baseBranch.isBlank()
                ? baseBranch : "main";

        long timestamp = System.currentTimeMillis() / 1000;
        String branchName = "fix/snyk-vulnerability-fixes-" + timestamp;

        StringBuilder result = new StringBuilder();

        // Step 1: Create branch
        String branchOutput = runGitCommand(projectDir, "git checkout -b " + branchName);
        if (branchOutput.startsWith("ERROR")) {
            return branchOutput;
        }
        result.append("Created branch: %s\n".formatted(branchName));

        // Step 2: Stage changes
        String addOutput = runGitCommand(projectDir, "git add -A");
        if (addOutput.startsWith("ERROR")) {
            return addOutput;
        }
        result.append("Staged all changes\n");

        // Step 3: Commit
        String commitMsg = "%s\n\n%s".formatted(effectiveTitle, effectiveBody);
        String commitOutput = runGitCommand(projectDir, "git commit -m \"%s\"".formatted(commitMsg.replace("\"", "\\\"")));
        if (commitOutput.startsWith("ERROR")) {
            if (commitOutput.contains("nothing to commit")) {
                return "No changes to commit. Make sure you applied the fix instructions to pom.xml first.";
            }
            return commitOutput;
        }
        result.append("Committed changes\n");

        // Step 4: Push branch
        String pushOutput = runGitCommand(projectDir, "git push -u origin " + branchName);
        if (pushOutput.startsWith("ERROR")) {
            return pushOutput;
        }
        result.append("Pushed branch to origin\n");

        // Step 5: Create PR using GitHub CLI
        String prCommand = "gh pr create --title \"%s\" --body \"%s\" --base %s --head %s"
                .formatted(
                        effectiveTitle.replace("\"", "\\\""),
                        effectiveBody.replace("\"", "\\\""),
                        effectiveBase,
                        branchName
                );
        String prOutput = runGitCommand(projectDir, prCommand);
        if (prOutput.startsWith("ERROR")) {
            result.append("\nWARNING: Branch pushed but PR creation failed. You can create the PR manually.\n");
            result.append("Branch: %s -> %s\n".formatted(branchName, effectiveBase));
            result.append("Error: %s\n".formatted(prOutput));
            return result.toString();
        }

        result.append("\nPR CREATED SUCCESSFULLY\n");
        result.append("PR URL: %s\n".formatted(prOutput.trim()));
        result.append("Branch: %s -> %s\n".formatted(branchName, effectiveBase));

        return result.toString();
    }

    private String runGitCommand(File workDir, String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(workDir);
            pb.redirectErrorStream(true);

            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb.command("cmd", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "ERROR: Command timed out: " + command;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "ERROR (exit %d): %s".formatted(exitCode, output.toString().trim());
            }

            return output.toString().trim();

        } catch (Exception e) {
            log.error("Git command failed: {}", command, e);
            return "ERROR: " + e.getMessage();
        }
    }
}
