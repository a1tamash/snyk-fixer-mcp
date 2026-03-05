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
public class BuildVerificationTool {

    private static final Logger log = LoggerFactory.getLogger(BuildVerificationTool.class);
    private static final int BUILD_TIMEOUT_MINUTES = 10;

    @McpTool(
            name = "verify_maven_build",
            description = """
                    Run 'mvn clean verify -DskipTests=false' in the given project directory to verify
                    that the project still compiles and all tests pass after dependency changes.
                    
                    Call this AFTER applying fix instructions from 'get_fix_instructions' to ensure
                    the dependency updates haven't broken anything.
                    
                    Returns BUILD SUCCESS or BUILD FAILURE with the relevant output/errors.
                    If the build fails, review the errors and either:
                    - Revert the problematic dependency change
                    - Try an alternative version
                    - Skip that particular fix
                    """
    )
    public String verifyBuild(
            @McpToolParam(description = "Absolute path to the project root directory containing pom.xml", required = true)
            String projectPath
    ) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            return "ERROR: Project directory does not exist: " + projectPath;
        }

        File pomFile = new File(projectDir, "pom.xml");
        if (!pomFile.exists()) {
            return "ERROR: No pom.xml found in: " + projectPath;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(projectDir);
            pb.redirectErrorStream(true);

            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb.command("cmd", "/c", "mvn", "clean", "verify", "-DskipTests=false");
            } else {
                pb.command("sh", "-c", "mvn clean verify -DskipTests=false");
            }

            log.info("Starting Maven build in: {}", projectPath);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return "ERROR: Build timed out after %d minutes. The build may be stuck or the project is very large."
                        .formatted(BUILD_TIMEOUT_MINUTES);
            }

            int exitCode = process.exitValue();
            String fullOutput = output.toString();

            // Extract last 100 lines for readability
            String[] lines = fullOutput.split("\n");
            int start = Math.max(0, lines.length - 100);
            StringBuilder tail = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                tail.append(lines[i]).append("\n");
            }

            if (exitCode == 0) {
                return "BUILD SUCCESS\n\nProject at %s compiled and all tests passed.\n\nLast output:\n%s"
                        .formatted(projectPath, tail);
            } else {
                return "BUILD FAILURE (exit code: %d)\n\nProject at %s failed to build.\n\nErrors (last 100 lines):\n%s\n\nPlease review the errors above. You may need to revert some dependency changes or try alternative versions."
                        .formatted(exitCode, projectPath, tail);
            }

        } catch (Exception e) {
            log.error("Build verification failed", e);
            return "ERROR: Failed to run Maven build: " + e.getMessage()
                    + "\nMake sure Maven is installed and available on PATH.";
        }
    }
}
