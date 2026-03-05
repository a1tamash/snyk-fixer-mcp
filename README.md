# Snyk Issues Fixer — MCP Server

An MCP (Model Context Protocol) server built with **Java + Spring Boot + Spring AI** that automatically fetches Snyk vulnerability reports and guides AI agents to fix them.

## What It Does

1. **Fetches Snyk Reports** — connects to Snyk REST API via git repo URL or Snyk report link
2. **Generates Fix Instructions** — produces safe, structured dependency upgrade instructions (minor versions only)
3. **Verifies Builds** — runs `mvn clean verify` to ensure nothing breaks
4. **Creates PRs** — commits changes and raises a pull request automatically

### Guardrails
- Only **minor/patch** version upgrades (no major bumps)
- **Spring Boot major upgrades blocked** (e.g., 2.x → 3.x)
- Covers **80%+** of real vulnerability use cases safely

## MCP Tools Exposed

| Tool | Description |
|------|-------------|
| `fetch_snyk_issues_by_repo` | Fetch vulnerabilities for a git repo URL |
| `fetch_snyk_issues_by_report` | Fetch vulnerabilities from a Snyk report URL |
| `get_fix_instructions` | Generate safe dependency upgrade instructions |
| `verify_maven_build` | Run Maven build to verify changes don't break anything |
| `create_pull_request` | Create branch, commit, push, and open a PR |

## Prerequisites

- **Java 17+**
- **Maven 3.8+**
- **GitHub CLI** (`gh`) installed and authenticated
- **Snyk account** with API token and Org ID

## Setup

1. Clone and enter the project:
   ```bash
   cd snyk-fixer-mcp
   ```

2. Set environment variables:
   ```bash
   export SNYK_API_TOKEN=your_token
   export SNYK_ORG_ID=your_org_id
   export GITHUB_TOKEN=your_github_token
   ```

3. Build and run:
   ```bash
   mvn clean install
   mvn spring-boot:run
   ```

   The MCP server starts on `http://localhost:8080` with SSE transport.

## Connecting to an AI Agent

### Cursor / VS Code
Add to your MCP settings (`.cursor/mcp.json` or VS Code MCP config):
```json
{
  "mcpServers": {
    "snyk-fixer": {
      "url": "http://localhost:8080/sse"
    }
  }
}
```

### Usage Prompts
Once connected, tell your AI agent:

```
Fix all snyk vulnerabilities for https://github.com/my-org/my-repo
```

Or with filters:
```
Fix all snyk vulnerabilities for https://github.com/my-org/my-repo that match:
(1) Severity: Medium + Fixed In: Yes + Fixable
(2) Severity: High + Exploit Maturity: Mature
```

## Project Structure

```
src/main/java/com/snykfixer/
├── SnykFixerMcpApplication.java    # Spring Boot entry point
├── config/
│   ├── AppConfig.java              # WebClient and property config
│   ├── SnykApiProperties.java      # Snyk API config properties
│   └── GitHubApiProperties.java    # GitHub config properties
├── model/
│   ├── SnykIssue.java              # Vulnerability data model
│   ├── SnykProject.java            # Snyk project data model
│   ├── FixInstruction.java         # Fix instruction data model
│   └── FilterCriteria.java         # Issue filter criteria
├── service/
│   └── SnykApiService.java         # Snyk REST API client
├── tools/
│   ├── SnykFetchIssuesTool.java    # MCP Tool: fetch issues
│   ├── FixInstructionsTool.java    # MCP Tool: generate fix instructions
│   ├── BuildVerificationTool.java  # MCP Tool: run Maven build
│   └── PullRequestTool.java        # MCP Tool: create PR
└── util/
    └── VersionUtils.java           # Semver comparison utilities
```
