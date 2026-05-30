# MyMTS — Vision (The What)

> **Status**: Locked. This document is the fixed point. Everything else in the project — security posture, delivery model, and every engineering decision — bends to serve this. When a "how" conflicts with this "what," the how is wrong.
> **Authority**: Operator (Will), via vision interrogation.
> **Rule**: The vision does not acquiesce to the how. The how acquiesces to the vision.

---

## 1. The one-sentence vision

**MyMTS is an ambient news video wall that lives on the TV — a calm, glanceable wall of live feeds you occasionally take control of. It exists to be *yours* in the ways monitor-the-situation.com can't be: your channels, no nags, your rules. It must be trustworthy above all else — it can never become a way into your home, and never expose anyone you share it with. Your own content stays effortless to change; everything beneath that stays locked down. A dead tile is forgivable; a breach is not.**

---

## 2. What it IS

- **Ambient first.** Its default state is running in the background on a TV, showing a wall of live news video, asking nothing of anyone. It is wallpaper that happens to be the news. Most of its life is spent being glanced at, not operated.
- **Occasionally active.** When something is happening in the world, the operator steps up and takes the controls — rearranges the wall, swaps in channels, focuses one feed. This is the exception, not the rule, but it must feel good when it happens.
- **A TV thing.** The television is the point. The wall belongs in a room, viewed from across that room. Everything about how it looks and behaves is judged first by "how is this from the couch."
- **Personal and owned.** It is the operator's own instrument. The channel lineup, the absence of nags and upsells, the rules of what shows and how — these are the operator's to set, and that ownership is the whole reason it exists rather than just using someone else's site.
- **Trustworthy.** It is something the operator can leave running unattended, indefinitely, without worrying that it has become a liability. Trust is not a feature of MyMTS; it is the precondition for MyMTS existing at all.

## 3. What it is NOT

- It is **not** a dashboard that demands attention or interaction to be useful.
- It is **not** a desktop-first application that happens to also run on a TV.
- It is **not** a general-purpose OSINT/intelligence terminal (no maps, aircraft, ships, markets-as-centerpiece). Those may exist someday but they are not the soul of the thing.
- It is **not** a product with users, accounts, billing, or growth. It is a personal instrument that *might* be shared, not a service seeking an audience.
- It is **not** a clone of monitor-the-situation.com. It takes the ambient-wall idea and makes it the operator's own; fidelity to MTS's exact layout or feature set is not a goal.
- It is **not** a thing whose value survives becoming untrustworthy. A beautiful, feature-rich MyMTS that could leak the operator's network is worthless. A plain one that is safe is the floor.

## 4. The experience, from the couch

When it is working perfectly:

- The operator walks into the room and the wall is already there — live, current, calm. No login wall, no "are you still watching," no setup. It is simply on.
- Several feeds play at once. The room has the texture of a newsroom without the operator having done anything.
- Nothing nags. No ads, no upsells, no "subscribe," no provider chrome screaming for attention beyond what the embedded video unavoidably carries.
- When the operator wants to change what's on the wall, they pick up the remote and it responds like a TV should — predictable, forgiving, never making them feel like they're operating a computer.
- Changing the operator's own content — adding a channel, swapping a source — is effortless. It never requires leaving the couch to go edit a file or redeploy something.
- The operator never thinks about whether it's safe, because it is, invariably.
- If a single feed dies, the wall absorbs it gracefully — a quiet gap, not a crash, not an error the whole room can see.

## 5. Whose instrument it is

- **Primary: the operator.** Designed around one person's taste and control. Every default serves the operator's experience first.
- **Ambient audience: household.** Family and others in the room are viewers, not operators. They benefit from the wall being on; they are not expected to configure it. The experience must be safe and inoffensive for anyone who happens to be in the room.
- **Someday-maybe: friends.** The operator might one day let a friend run their own instance or view theirs. This is *not* a near-term feature and the operator's own experience is never to be compromised for it. **But** — and this is load-bearing — the operator ranked "a friend I shared it with gets compromised" as the *second-worst* thing that could happen, above losing their own data. So while sharing is not a feature to build now, the *principle* that this thing must never harm someone it's shared with is a value from day one. It constrains the security posture even before sharing exists.

## 6. The fixed priorities (ranked, immutable)

These are the operator's stated rankings. They are the tiebreakers for every downstream decision.

### Why this exists (ranked)
1. **To have what MTS doesn't** — the operator's own channels, no nags, the operator's control. *(This is the primary driver. When in doubt, optimize for the operator's sense of ownership and control.)*
2. **To not depend on MTS** — the operator dislikes relying on a stranger's site.
3. **To own the data/infra** — rather than trust a stranger's site with it.

### What must never happen (ranked worst-first)
1. **A breach** — someone gets into the home network through this app. *This is the worst outcome. The entire security posture is built to make this impossible first.*
2. **Harm to a shared friend** — someone the operator shared it with breaks or gets compromised. *Ranked above the operator's own data loss. Sharing-safety is a day-one value even though sharing is not a day-one feature.*
3. **Data loss** — presets/config wiped, operator has to rebuild.
4. **Silent staleness** — it quietly stops updating and the operator is looking at old news without knowing.
5. **A visible dead screen** — the TV shows broken/dead tiles in front of family. *This is the least bad. A dead tile is forgivable.*

### The core tradeoff (resolved)
**Bulletproof core, one-click-easy content.** When "locked-down and safe" conflicts with "easy to tinker with," safety wins for everything structural — but changing the operator's *own content* (channels, sources, the lineup) must stay effortless. The lockdown is beneath the operator's content, not around it.

## 7. What this vision implies for everyone downstream

The security/privacy/stability doc and the delivery/maintenance doc both inherit directly from §6. Specifically:

- Because **breach is the worst outcome**, the trust boundary is the project's primary engineering investment, ahead of features and ahead of polish.
- Because **harming a shared friend ranks second**, the security model is designed as if untrusted people will eventually touch it — even before they do.
- Because **a dead tile is the least-bad outcome**, engineering effort should *not* be poured into perfect stream resilience at the cost of the things above it. Graceful degradation, not heroic recovery.
- Because **ambient-on-TV is the soul**, anything that compromises "it's just calmly on, viewed from the couch" is suspect — including features that only make sense leaning into a screen.
- Because **the operator's content must stay one-click easy**, any safety mechanism that makes changing channels/sources require a redeploy or a file edit has violated the vision and must be redesigned.
- Because **friends/accounts/growth are explicitly not the point**, no complexity may be justified by "users might want." Only the operator's vision justifies complexity.

## 8. The anti-drift clause

This project previously drifted by letting engineering concerns (tech stack, edge-case mitigations, feature creep) accumulate until the *what* was buried under the *how*. To prevent recurrence:

- No engineering decision may expand scope beyond what this vision requires.
- "Could theoretically happen" is not justification for complexity. Only "this serves the vision" or "this protects a §6 priority" is.
- When a review or interrogation surfaces a new concern, the first question is always: *does fixing this serve the vision, or is it the how trying to dictate the what?* If the latter, the concern is logged and deferred, not folded in.
- The operator is the authority on the vision. The engineer (Claude / Claude Code) is the authority on the how. The engineer does not ask the operator to adjudicate engineering tradeoffs; it resolves them in service of this vision.
