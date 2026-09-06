#!/usr/bin/env python3
"""One audited branch-consolidation batch; never rewrites or deletes main."""
import os
import re
import subprocess
import sys

BASE = "81039466c747891ef22ac06cf0651f4c682e54cf"
INTEGRATION_BRANCH = "team2/issue-129-startup-gate"
INTEGRATION_ROOT = "3543626f774b302d3607f03dd2f9612922bfb00e"
EXPECTED = dict.fromkeys([
    "docs/canonical-studio-ai-links-20260905",
    "feature/pair-extraordinaire-fix",
    "fix/daily-build-no-gh",
    "fix/reasoning-off-openai-compatible-20260906",
    "ops/release-refresh-on-main",
    "ops/release-refresh-on-main-v2",
    "pair-v7", "pair-v8", "pair-v9", "pair-v10-branch", "pair-v15-branch",
    "perf/ci-queue-gradle-971", "perf/codeql-kotlin-ic-ab",
    "perf/codeql-targeted-rebuild-final", "side-notes-target-by-org-orchords",
    "team1/ci-readme-policy-20260906", "team1/issue-197-tool-alias-policy",
], BASE)
EXPECTED.update({
    "team1/issue-62-provider-off-fixtures": "98260891fb4dad1e000b9cdb37a822ada384a307",
    "team1/issue-87-connector-executor-preflight": "cb0e8a8a4a52ae88c90fbc36fe040dc6e1253de9",
    "team1/issue-240-ingress-unknown-length": "7b4207022cf9986457969e71b4bd8556de774418",
    "team1/issue-240-unknown-length-ingress": "183fad9f5bf65d5ffc00ff6e919a2e043ae86cd4",
    "team2/issue-82-skill-install-service": "f9b6b5b63f602e095f45f69378e26097187db08f",
    "team2/issue-259-safe-regex": "971cdcca32313331cae9a878ad6c1511d6522404",
    "team2/issue-260-injection-budget": "175cc659669411ef1fefe87780171e5f11b7485f",
    "team2/issue-267-lorebook-depth": "19297946e4cc75aabb9008cdc5d1dc6018efe40d",
})


class StaleVerifiedMain(RuntimeError):
    """A successful older verification was superseded by a newer main head."""


def git(*args):
    return subprocess.run(["git", *args], check=True, text=True, capture_output=True).stdout.strip()


def inventory():
    return {ref.removeprefix("refs/heads/"): sha for sha, ref in
            (line.split() for line in git("ls-remote", "--heads", "origin").splitlines())}


def ancestor(older, newer):
    result = subprocess.run(["git", "merge-base", "--is-ancestor", older, newer], capture_output=True)
    if result.returncode not in (0, 1):
        raise RuntimeError("Cannot establish branch ancestry")
    return result.returncode == 0


def select_candidates(heads, verified, expected, integration_branch, integration_root):
    if heads.get("main") != verified:
        raise StaleVerifiedMain("main moved; wait for verification of the new head")
    if "main" in expected or integration_branch == "main":
        raise RuntimeError("main is never a deletion candidate")
    candidates = {}
    for name, recorded in expected.items():
        actual = heads.get(name)
        if actual is None:
            continue
        if actual != recorded or not ancestor(actual, verified):
            raise RuntimeError(f"Refusing changed or unmerged branch: {name}")
        candidates[name] = actual
    actual = heads.get(integration_branch)
    if actual is not None:
        # A true merge records the integration head in main history. Later main
        # commits may legitimately change the tree, so ancestry is the durable
        # preservation proof rather than permanent tree equality.
        if not ancestor(integration_root, actual) or not ancestor(actual, verified):
            raise RuntimeError("Integration branch is not fully preserved in verified main history")
        candidates[integration_branch] = actual
    return candidates


def delete_candidates(candidates):
    if not candidates:
        return
    if "main" in candidates:
        raise RuntimeError("main is never a deletion candidate")
    leases = [f"--force-with-lease=refs/heads/{name}:{sha}" for name, sha in candidates.items()]
    deletes = [f":refs/heads/{name}" for name in candidates]
    print(git("push", "--atomic", "--porcelain", *leases, "origin", *deletes))


def main():
    if os.environ.get("GITHUB_REPOSITORY") != "ORCHORDS/OrchordsStudioAi":
        raise RuntimeError("This audited batch is restricted to ORCHORDS/OrchordsStudioAi")
    remote = git("remote", "get-url", "origin")
    if remote not in ("https://github.com/ORCHORDS/OrchordsStudioAi", "https://github.com/ORCHORDS/OrchordsStudioAi.git"):
        raise RuntimeError("Unexpected remote repository")
    verified = os.environ["VERIFIED_MAIN_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", verified):
        raise RuntimeError("Invalid verified commit")
    if git("rev-parse", "HEAD") != verified:
        raise RuntimeError("Checkout does not match the verified commit")
    before = inventory()
    candidates = select_candidates(before, verified, EXPECTED, INTEGRATION_BRANCH, INTEGRATION_ROOT)
    if inventory().get("main") != verified:
        raise StaleVerifiedMain("main moved during the audit")
    delete_candidates(candidates)
    after = inventory()
    remaining = sorted(name for name in after if name != "main")
    report = f"Branches before: {len(before)}\nDeleted: {len(candidates)}\nBranches after: {len(after)}\nRemaining non-main: {remaining}\n"
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write("## Audited branch consolidation\n\n```text\n" + report + "```\n")
    if "main" not in after or remaining:
        raise RuntimeError("Repository is not main-only; unrelated/new branches were left untouched")


if __name__ == "__main__":
    try:
        main()
    except StaleVerifiedMain as error:
        # A newer main commit will receive its own Main Verification and cleanup
        # trigger. This older verified run performed no deletion and is not a
        # repository failure, so report a neutral successful skip instead of a
        # misleading red workflow.
        print(f"Branch cleanup skipped safely: {error}")
        sys.exit(0)
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Branch cleanup stopped safely: {error}", file=sys.stderr)
        sys.exit(1)
