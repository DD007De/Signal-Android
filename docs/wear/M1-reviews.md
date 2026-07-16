# WEAR-001 — Review evidence (uncorrelated reviewers)

Three independent reviewers per the Dark Factory "uncorrelated reviewers" pattern.

| Reviewer | Type | Verdict |
|---|---|---|
| **Codex** (`gpt-5.5`, dedicated runner) | LLM, vendor A | No spike blocker; 1 MEDIUM, 1 LOW |
| **Claude — pr-review-toolkit:code-reviewer** | LLM, vendor B | Sound for a spike; 2 Important, 2 Minor |
| **Claude — superpowers general-purpose reviewer** | LLM, vendor B (diff lens) | With fixes; 0 Critical, 2 Important, 5 Minor |
| **Security scan** (Semgrep/Gitleaks/Trivy) | rule-based | **Skipped — tools not installed on this host.** Logged per framework. Low risk: diff adds no secrets, and two standard Google libraries. Full scan required before any upstream PR. |

## Consolidated findings & resolution

| # | Finding | Raised by | Severity | Action |
|---|---|---|---|---|
| 1 | `WearDataClient.ping()` `.await()` can throw (ApiException/Cancellation) in a coroutine with no handler → app crash on a failing tap | pr-toolkit (Important) | High | **Fixed** — `ping()` now wraps in try/catch, rethrows `CancellationException`, logs + returns false otherwise. |
| 2 | Watch redundantly advertises the `signal_wear_bridge` capability it consumes; in M2 the phone would discover the watch as a bridge endpoint | pr-toolkit (Important), superpowers (Minor) | Medium | **Fixed** — deleted `wear/src/main/res/values/wear.xml`. |
| 3 | `"sent…"` can clobber a delivered `"pong"` (display race) | Codex (MEDIUM), pr-toolkit (Minor), superpowers (Minor) | Medium | **Fixed** — set `"sending…"` before send; on success leave the state to the pong listener; only report `"no phone"` on failure. |
| 4 | Phone-side `sendMessage` pong is fire-and-forget (silent failure) | Codex (LOW), pr-toolkit (Minor), superpowers (Minor) | Low | **Fixed** — added `addOnFailureListener` logging. |
| 5 | **Ping/pong transport not actually demonstrated** (emulators never paired; only the `"no phone"` branch ran) — de-risking goal not fully met | superpowers (Important) | High (scope) | **Accepted + re-scoped.** Watch-side path + no-node handling proven; live transport delivery to be confirmed on a **physical paired watch** using the `staging` APKs built for that purpose (see `M1-roundtrip-evidence.md`). M2 Task 1 also revalidates transport before building on it. |
| 6 | `play-services-wearable` added unconditionally → lands in the Google-free `website` flavor too | superpowers (Important) | Medium (upstream) | **Deferred to M2/PR prep** — gate the GMS wearable dep behind the `play` distribution flavor before upstream. Acceptable in the fork spike. |
| 7 | Transport listener depends on Compose (`mutableStateOf`); protocol duplicated without a drift guard | superpowers (Minor) | Low | **Deferred to M2** — M2 Task 1 extracts the protocol to `:core` (with an identity test) and routes replies through a repository/flow instead of a Compose state object. |

## Outcome
All High/Medium code findings fixed in this branch (items 1–4). Items 5–7 are scope/upstream items tracked into the M2 plan and `staging` physical test. No Critical findings from any reviewer.
