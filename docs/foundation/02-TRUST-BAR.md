# MyMTS — The Trust Bar (Security, Privacy, Stability)

> **Status**: Locked. These are principles and required outcomes, not implementations. They are derived directly from the Vision doc's ranked priorities. No technology, library, or mechanism is named here — that is the engineer's job, downstream. This document says *what must be true*, never *how*.
> **Derivation**: Every principle traces to a ranked priority in `01-VISION.md §6`.
> **Precedence**: When any requirement here conflicts with a feature or a convenience, this document wins. The sole exception is the "one-click-easy content" guarantee from the Vision, which these principles must accommodate rather than override.

---

## Part A — Security

### A0. The prime directive
**MyMTS must never become a path into the operator's home network.** This is the single highest requirement in the entire project, above all features, all polish, all other safety. Every security principle below exists to serve this one sentence. If any decision anywhere in the project measurably increases the risk of network compromise, that decision is wrong regardless of what it gains.

*(Traces to: worst-outcome #1.)*

### A1. Assume hostile input, always
Everything that enters MyMTS from outside — news content, video sources, market/sports data, anything the operator or anyone else types in, anything fetched from the internet — is assumed hostile until proven safe. Nothing from outside is ever trusted on arrival. The burden is on the input to prove itself benign, never on the system to prove it harmful.

*(Traces to: worst-outcome #1.)*

### A2. The blast radius of any single failure is contained
If any one part of MyMTS is compromised, the damage must stop there. A compromise of the thing that shows video must not reach the thing that stores data. A compromise of MyMTS must not reach other things on the network. A compromise of the operator's session must not become a compromise of everything else the operator can access. The system is built as compartments, and a breach of one compartment does not flood the others.

*(Traces to: worst-outcomes #1 and #2.)*

### A3. Designed as if untrusted people will touch it
Even though sharing is not a near-term feature, the security model is built from day one as though people other than the operator will eventually use or reach it. No security guarantee may depend on "only the operator would ever do this" or "no one else can reach it." The walls are built before the guests arrive.

*(Traces to: worst-outcome #2 — harm to a shared friend ranks above the operator's own data loss.)*

### A4. No ambient authority
Nothing in MyMTS has more power or reach than it needs to do its job. The part that shows video cannot reach the network at large. The part that fetches news cannot reach the operator's other systems. Powerful capabilities are never granted "just in case." Every component operates with the least authority sufficient for its function, and no more.

*(Traces to: worst-outcome #1.)*

### A5. The operator's session is not a skeleton key
The operator's authenticated access to MyMTS must never silently extend to anything else the operator can reach. Being logged into MyMTS grants access to MyMTS and nothing beyond it. A compromise of MyMTS in the browser must not yield the keys to the operator's other private systems.

*(Traces to: worst-outcome #1 — this is the specific mechanism by which a web compromise becomes a network compromise.)*

### A6. Actions are authenticated and intentional
Anything that changes state — changing the lineup, editing sources, triggering any operation — must be something the operator actually, intentionally did from within MyMTS. The system must be able to distinguish a real operator action from a forged one initiated elsewhere. No state-changing action can be triggered by merely visiting a malicious page elsewhere.

*(Traces to: worst-outcome #1.)*

### A7. Secrets stay secret
Any credential, token, or key the system holds is never exposed — not in logs, not on screen, not in error messages, not to any component that doesn't strictly need it, not in anything that leaves the system. Secrets are write-only into the places that consume them and invisible everywhere else. Long-lived secrets have a known owner and a known way to be rotated without rebuilding the system.

*(Traces to: worst-outcomes #1 and #2.)*

### A8. The boundary is observable and fails safe
The integrity of the trust boundary must be observable — the operator can tell whether the protections are actually in force. When a protective control cannot do its job, the system fails toward safety (deny, isolate, alert), never toward silent permissiveness. A protection that has quietly stopped working is itself a detectable, alertable condition.

*(Traces to: worst-outcomes #1 and #4.)*

### A9. Compromise is recoverable
If the worst happens and something is compromised, there must be a way to revoke, reset, and recover to a known-good state without physical heroics. The operator can pull the plug on a bad state remotely. Recovery does not require standing in front of a device.

*(Traces to: worst-outcomes #1 and #2.)*

---

## Part B — Privacy

### B1. The operator's behavior is the operator's alone
What the operator watches, when, how they arrange the wall, what sources they follow — this is private to the operator and is never sent anywhere outside the operator's own infrastructure. No third-party telemetry, no analytics-phone-home, no usage data leaving the operator's control. The system does not report on its operator.

*(Traces to: Vision — owning data/infra; worst-outcome #1.)*

### B2. Shared guests are owed the same privacy
If the thing is ever shared, the same rule extends: a guest's use is not surveilled by the operator beyond what is strictly necessary to keep the system safe and working, and never leaves the operator's infrastructure. Sharing does not create a surveillance relationship.

*(Traces to: worst-outcome #2.)*

### B3. Minimal retention by default
The system keeps what it needs to function and no more, for no longer than useful. Historical data accumulates only where it serves a stated purpose (e.g., recent news for the feed, a short audit trail for investigating problems). Nothing is hoarded indefinitely without reason.

*(Traces to: Vision — ownership; worst-outcome #3.)*

### B4. No leak through convenience features
Conveniences that bridge devices or hand off content (anything that moves something from the wall to another screen, or shares a link) must not leak anything private or anything that grants access. A convenience feature is never allowed to become a privacy hole or an access-granting side channel.

*(Traces to: worst-outcomes #1 and #2.)*

### B5. The operator owns the record
Any logs or records the system keeps belong to the operator, live on the operator's infrastructure, and are governed by the operator. They exist to serve the operator's ability to understand and trust the system, not anyone else's.

*(Traces to: Vision — owning data/infra.)*

---

## Part C — Stability

### C0. Stability is ranked, not absolute
The Vision establishes a clear hierarchy: a breach is catastrophic, data loss is serious, a dead tile is forgivable. Stability effort is allocated according to that hierarchy. The system is not required to be perfectly resilient at everything — it is required to be *unbreakable where it matters and gracefully imperfect where it doesn't*.

*(Traces to: the full worst-outcome ranking.)*

### C1. The operator's data is durable
The operator's content and configuration — the lineup, the sources, the presets, anything the operator built — must survive. It survives restarts, updates, crashes, and operator mistakes. There is always a way back to recent good state. Losing the operator's accumulated work is a serious failure and the system is built to make it nearly impossible.

*(Traces to: worst-outcome #3.)*

### C2. A dead feed is a non-event
When a single video feed fails — and they will, constantly, because live streams are inherently unreliable — the wall absorbs it without drama. No crash, no cascade, no error that fills the room. A failed feed degrades to a quiet, honest gap and recovers when it can. The system never lets one feed's failure become the wall's failure. This is explicitly *not* an area for heroic engineering; graceful degradation is the goal, not perfect uptime per feed.

*(Traces to: worst-outcome #5 — the least-bad outcome, deliberately under-invested relative to the above.)*

### C3. Staleness is never silent
If the system stops getting fresh information — feeds stop updating, sources go dark, the connection drops — it must make that visible rather than presenting stale content as current. The operator (and anyone in the room) should be able to tell the difference between "current and calm" and "frozen and pretending." Silent staleness is worse than visible breakage because it erodes trust invisibly.

*(Traces to: worst-outcome #4 — ranked above visible dead screens precisely because it's deceptive.)*

### C4. It runs unattended for a long time
The defining use is "on, in the background, for a long time, untouched." The system must therefore survive long uptimes without degrading — no slow leaks that eventually choke it, no states that only appear after days of running. It is built to be left alone for months, because that is its actual job.

*(Traces to: Vision — ambient, set-and-forget on the TV.)*

### C5. Recovery is automatic where it can be, visible where it can't
When something recoverable goes wrong, the system heals itself without the operator noticing. When something needs the operator, it says so clearly rather than failing silently or pretending. The operator is never left guessing whether the thing is healthy.

*(Traces to: worst-outcomes #4 and #5; Vision — trust.)*

### C6. Failure is graceful, never catastrophic
No single failure should ever take down the whole system or, worse, leave it in a corrupt or unsafe state. The system fails in pieces, softly, and never in a way that violates Part A. A stability failure must never become a security failure — when something breaks, it breaks *safe*.

*(Traces to: the full ranking; binds Part C back to Part A.)*

---

## Part D — How these resolve against each other

When principles tension, this is the precedence order:

1. **Security (Part A)** — always wins. Nothing justifies increasing breach risk. *(Worst-outcome #1.)*
2. **The shared-guest safety principle** — wins over the operator's own convenience and the operator's own data. *(Worst-outcome #2 ranked above #3.)*
3. **Data durability (C1)** — wins over everything below it. *(Worst-outcome #3.)*
4. **Honest staleness (C3)** — wins over visual polish. *(Worst-outcome #4 above #5.)*
5. **Feed-level resilience (C2)** — the lowest priority; deliberately under-invested. *(Worst-outcome #5.)*

And cutting across all of them, the **one-click-easy content guarantee** from the Vision: none of these protections may make changing the operator's own channels and sources require friction like editing files or redeploying. Security is built *beneath* the operator's content, never *around* it. If a security mechanism would gate the operator's routine content changes behind engineering friction, that mechanism must be redesigned — the requirement is to achieve the safety some other way, not to relax the safety.

---

## Part E — What this doc deliberately does NOT say

It does not name a single technology, protocol, library, or pattern. It does not say how isolation is achieved, how sessions are scoped, how data is made durable, how staleness is detected, or how recovery happens. Those are engineering decisions that must *satisfy* these principles, and the engineer is free to choose any means that does. This separation is intentional: the principles are permanent; the mechanisms may change. If a future mechanism better serves these principles, it may replace an older one without this document changing.
