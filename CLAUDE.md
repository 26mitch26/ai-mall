# AI-Mall Project

## gstack

Use the `/browse` skill from gstack for all web browsing. Never use `mcp__claude-in-chrome__*` tools.

### Available Skills

**Core Development:**
- `/office-hours` - YC Office Hours: startup diagnostic + builder brainstorm
- `/plan-ceo-review` - CEO-level plan review
- `/plan-eng-review` - Engineering plan review
- `/plan-design-review` - Design plan review
- `/design-consultation` - Design system from scratch
- `/design-shotgun` - Visual design exploration
- `/design-html` - HTML design generation
- `/review` - PR review
- `/ship` - Ship workflow
- `/land-and-deploy` - Merge → deploy → canary verify
- `/canary` - Post-deploy monitoring loop
- `/benchmark` - Performance regression detection
- `/browse` - Headless browser CLI (Playwright)
- `/connect-chrome` - Launch GStack Browser

**Quality & Testing:**
- `/qa` - Quality assurance with fixes
- `/qa-only` - Report-only QA, no fixes
- `/design-review` - Design audit + fix loop
- `/setup-browser-cookies` - Browser cookie configuration
- `/setup-deploy` - Deploy configuration
- `/setup-gbrain` - GBrain setup

**Planning & Documentation:**
- `/retro` - Retrospective (includes global cross-project mode)
- `/investigate` - Systematic root-cause debugging
- `/document-release` - Post-ship doc updates
- `/document-generate` - Diataxis doc generator

**AI & Advanced:**
- `/codex` - Multi-AI second opinion via OpenAI Codex CLI
- `/cso` - OWASP Top 10 + STRIDE security audit
- `/autoplan` - Auto-review pipeline: CEO → design → eng
- `/plan-devex-review` - Developer experience review
- `/devex-review` - Developer experience audit

**Safety & Control:**
- `/careful` - Enable careful mode
- `/freeze` - Freeze codebase
- `/guard` - Guard against changes
- `/unfreeze` - Unfreeze codebase

**Meta:**
- `/gstack-upgrade` - Upgrade gstack
- `/learn` - Learn new patterns

## Project Structure

```
ai-mall/
├── pom.xml                          ← Parent POM
├── mall-core/                       ← E-commerce core (7 submodules)
├── ai-gateway/                      ← Unified API gateway
├── agent-customer/                  ← Smart customer service Agent
├── agent-ops/                       ← Smart operations Agent
├── agent-test/                      ← Automated testing Agent
├── infra/                           ← Docker Compose infrastructure
└── docs/                            ← Interview materials
```

## Development Guidelines

- Java 21 + Spring Boot 3.5
- Maven multi-module architecture
- Docker Compose for infrastructure
- JUnit 5 + Testcontainers for testing
