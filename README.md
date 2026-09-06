<p align="center">
  <img src="https://raw.githubusercontent.com/ORCHORDS/docs/main/assets/1080x360.jpg" width="1080" alt="ORCHORDS — BUILD DIFFERENT.">
</p>

# ORCHORDS AI

[![Daily build](https://github.com/ORCHORDS/OrchordsStudioAi/actions/workflows/daily-build.yml/badge.svg)](https://github.com/ORCHORDS/OrchordsStudioAi/actions/workflows/daily-build.yml)
[![Dependency audit](https://github.com/ORCHORDS/OrchordsStudioAi/actions/workflows/dependency-audit.yml/badge.svg)](https://github.com/ORCHORDS/OrchordsStudioAi/actions/workflows/dependency-audit.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

> ⭐ If you like ORCHORDS AI or find it useful, please consider starring this repository. It helps more people discover the project.

> **Interested in sponsoring ORCHORDS?** Sponsorships start at **US$1,000**. Depending on the sponsorship level, sponsors may receive public recognition, logo and website placement, sponsor updates and early previews, roadmap-feedback briefings, priority issue triage, and engineering or integration discussions. Sponsorship does not buy control of the roadmap or guarantee feature implementation. Contact **[crm@orchords.com](mailto:crm@orchords.com)**.

**Independent software studio founded in 2025.**

ORCHORDS AI is a private-by-design, local-first Android AI workspace for user-selected models and services. It combines multi-provider chat, Model Context Protocol (MCP) tools, local persistence, voice, search, rich rendering, image, video, and workspace workflows without tying the product to a single model vendor.

## Start here

| If you need… | Start with |
| --- | --- |
| Build and verification requirements | [Building ORCHORDS AI](docs/BUILDING.md) |
| Component boundaries | [ORCHORDS AI Architecture](docs/ARCHITECTURE.md) |
| MCP integration and approvals | [Model Context Protocol](docs/MCP.md) |
| GitHub MCP guidance | [GitHub MCP](docs/GITHUB_MCP.md) |
| Cloudflare MCP guidance | [Cloudflare MCP](docs/CLOUDFLARE_MCP.md) |
| Security design and reporting | [Security Design](docs/SECURITY.md) · [Security Policy](SECURITY.md) |
| Release process | [Releasing ORCHORDS AI](docs/RELEASING.md) |
| Repository branding rules | [Branding and Documentation Style](docs/BRANDING.md) |

## Capabilities

- Multi-provider chat with configurable endpoints and request controls
- MCP tools with explicit approval boundaries for sensitive actions
- On-device conversations, settings, and credentials
- Voice, search, rich rendering, image, and video workflows
- Optional local web interface and workspace tooling

## Verify nightly APKs

Nightly releases publish SHA-256 checksums and a GitHub/Sigstore SLSA provenance attestation. After downloading the APK and `SHA256SUMS`, verify both before installing:

```bash
sha256sum --check SHA256SUMS
gh attestation verify ./ORCHORDS-AI.apk \
  --repo ORCHORDS/OrchordsStudioAi \
  --signer-workflow ORCHORDS/OrchordsStudioAi/.github/workflows/daily-build.yml \
  --source-ref refs/heads/main
```

Replace `ORCHORDS-AI.apk` with the downloaded APK filename.

## Documentation boundary

Product-specific engineering guidance lives in this repository. Company-wide public engineering, security, governance, and operational documentation is maintained in [`ORCHORDS/docs`](https://github.com/ORCHORDS/docs).

## Brand

**ORCHORDS — BUILD DIFFERENT.**

## License

Licensed under the [GNU AGPL v3](LICENSE). See [Third-Party Notices](THIRD_PARTY_NOTICES.md).