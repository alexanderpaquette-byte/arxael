# Your first land — watch one PR go green on `main` (2 minutes)

Onboarding usually stops at "the daemon is up." This is the missing next step: **see one change land on a
green `main`, end to end**, with your own eyes — no AI agent required, you play the agent by hand once.

## 0. Prereqs

The daemon is running and your project is connected:

```bash
scripts/arxael up        # starts the helper + connects the repo you're standing in
scripts/arxael status    # "✓ Connected <project> (N modules)."
```

`arxael up` printed the **hub** path (a bare clone agents merge into) — grab it and the port:

```bash
PORT=$(cat ~/.arxael/port 2>/dev/null || echo 8723)
HUB=~/.arxael/hubs/$(basename "$(git rev-parse --show-toplevel)").git
echo "port=$PORT  hub=$HUB"
```

## 1. Be an agent: make a branch IN THE HUB

Agents branch **off the hub** so the merge gate can see the branch (a branch the hub never received comes
back as state `missing`). Create a worktree off the hub and make a trivial change:

```bash
git -C "$HUB" worktree add /tmp/agent1 -b demo-change main
echo "// hello from agent1" >> /tmp/agent1/README.md
git -C /tmp/agent1 commit -aqm "demo: a one-line change"
```

## 2. Test it on the shared executor

Don't run gradle/pytest yourself — route it through the warm executor so the box stays bounded:

```bash
curl -sX POST 127.0.0.1:$PORT/invoke -H 'Content-Type: application/json' \
  -d '{"adapter":"gradle","worktree":"/tmp/agent1","tasks":["test"],"agentId":"agent1"}' | jq .status
# -> "SUCCESS" (FAILED = your tests failed; OVERLOADED = box busy, retry)
```

(Not a Gradle project? Swap `"adapter"` for `maven` / `pytest` / `cargo` / `go` / `vitest` / `npm` / `make`,
or `exec` with an explicit command in `tasks`.)

## 3. Submit it to land on `main`

```bash
curl -sX POST 127.0.0.1:$PORT/merge/submit -H 'Content-Type: application/json' \
  -d '{"branch":"demo-change","agentId":"agent1"}'
# -> {"ok":true,"queued":"demo-change"}   (module is optional — it's inferred from your diff)
```

## 4. Watch YOUR PR's outcome

Ask about *your* branch — don't race the shared counter:

```bash
curl -s "127.0.0.1:$PORT/merge/pr?branch=demo-change&wait=30" | jq
# -> {"state":"landed","terminal":true,"commit":"...","reason":null}
#    "reverted"/"bounced" = it didn't land (fix + resubmit); "missing" = step 1 was skipped
```

`landed`? It's on the shared `main`. Pull it back into your working checkout:

```bash
git pull "$HUB" main
scripts/arxael status     # "✓ 1 change(s) merged so far."
```

That's the whole loop a fleet of agents runs concurrently — each one does steps 1–4, and arxael keeps `main`
green the entire time. Clean up the demo worktree with `git -C "$HUB" worktree remove --force /tmp/agent1`.

## What just happened (the guarantee)

Your change was merged onto the **live** `main` and tested there before it was allowed to stay. If it had
gone red, arxael would have auto-reverted it (optimistic path) or never landed it (batched path) — so a
broken change from one agent never lands on everyone else's `main`. See
[ARCHITECTURE.md](ARCHITECTURE.md) for how, and [LIMITATIONS.md](LIMITATIONS.md) for the trust model.
