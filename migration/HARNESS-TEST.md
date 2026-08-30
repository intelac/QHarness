# Running this on the harness

**It works.** The minimal test ran end to end on the harness with the local
Qwen: skill loaded, both probes executed, output arrived intact and the
numbers match what the same probes produce by hand.

## Ready

```
skill      ~/.dsh/skills/survey-play1/     installed, absolute probe paths
probes     migration/probes/play1/*.py     verified on five corpora
projects   ~/projects/play1-samples/       bionimbuz (76 java, 66 html), booking
harness    3080, restarted clean, 0 errors in the log
```

The skill's probe paths were relative (`tools/parse-routes.py`) and pointed at
a directory that no longer exists. Both are now absolute. A relative path
would have resolved against whatever working directory the agent happened to
be in, which is not a thing to leave to chance.

## Confirmed present

`dsh-base` supplies the three that matter, so the question of whether the
harness *can* run this is settled:

```
dsh-tool-bash     runs the probes
dsh-tool-fs       reads files
dsh-tool-skill    loads the skill
```

Also there and relevant later: `dsh-tool-goal`, `dsh-tool-subagent`,
`dsh-tool-todo`, `dsh-compaction-tool`.

## What the run established

25 minutes, 5 steps, Qwen3.8 27B at 17 tok/s. From the trace:

**The skill loaded.** A `Skill survey-play1` step appears before any tool
call. This was the least certain of the three — the harness exposes skills
through a loader the model chooses to call, so naming one in a prompt is a
request rather than a guarantee, and an agent answering from the prompt alone
would have looked like success while having read nothing.

**Both probes ran, through `dsh-tool-bash`.** The absolute paths resolved. The
agent's own summary — *"两个探针都跑完了（模板探针退出码为 3，即存在它读不出的表达式，已在输出中逐条列出）"* —
shows it read the exit code and understood what 3 means. That convention was
written into the probes so a parse gap would be visible rather than silent,
and it survived the trip through the harness into the model's reasoning.

**The output was not truncated, and the numbers are right:**

```
harness agent    103 routes, 101 named endpoints, 1 static, 1 dynamic
run by hand      103 routes, 101 named endpoints, 1 static, 1 dynamic
```

Identical, including the "Dynamic dispatch is present" warning. Independently
known — `PROJECT-bionimbuz.md` came from running the same probes directly —
so agreement here is not something a plausible-sounding answer could have
produced.

## Oversized output

Tested separately on the 1,264-template corpus, whose scan emits 1.6 MB. The
agent was asked to run the probe and say whether what it received was whole.
It quoted the answer:

```
[output truncated; full output: /var/folders/.../dsh-subprocess-…-stdout
```

So the mechanism is: truncate inline, write the whole thing to a file, hand
back the path. Nothing is lost — it moves somewhere the agent has to go and
fetch.

Its reasoning is worth recording as well, because it is the behaviour the
whole arrangement depends on:

> Hmm, but there's a subtle point: the user might be testing whether I'll
> honestly report the truncation.

It noticed the output was incomplete, understood that reporting it was the
point, and did not invent the numbers it could not see. That is exactly the
failure this was probing for — a model that summarises a truncated result as
if it had read all of it produces a report that looks complete and is not.

**What this settles for the design.** An agent cannot read the raw probe
output at scale, and should not: the useful shape is a script that aggregates
first, with the full JSON on disk for the cases where a specific answer is
needed. The pipeline was already built that way; this is the measurement
behind it rather than a precaution.

The temp file is session-scoped and was already collected by the time it was
looked for, so the file's contents were not verified directly — the mechanism
is established from the agent's quotation of the notice, not from reading what
it pointed at.

**One thing observed in passing:** the log shows
`已重试模型请求（3/5）· 失败原因：Connection error`. The run recovered on its own.
Worth knowing that transient provider failures are absorbed rather than
surfaced as task failures.

## Why this is not the same as testing the skill elsewhere

A Claude Code subagent and a harness agent differ in the three ways that
matter here: the tool set is composed from different plugins, the skill
arrives by loader call rather than by being pasted into the prompt, and large
output is handled by `spill` in one and not at all in the other.

So a subagent run would establish whether the skill's instructions are clear
and followable — a real question, and one worth answering — but it would say
nothing about whether the harness can execute them. Those are separate tests
and the second is the one that was started here.

## The minimal test, to resume

One session, one prompt:

> 用 survey-play1 这个 skill，盘点 /Users/kangyi/projects/play1-samples/bionimbuz
> 这个 Play 1 项目。只做 skill 里的第 2 步（routes）和第 4 步（模板），其他步骤跳过。
> 跑完把两个探针的输出原样给我，不要总结。

Two steps, not the whole survey, and the output asked for raw. What is being
watched is the trace, not the answer:

```
Skill loaded?          a skill-loading tool call appears
Probes ran?            two bash calls with the absolute paths
Output intact?         103 routes, 64 templates — the known-correct numbers
Or truncated?          a spill notice instead of the JSON
```

The numbers are known independently — `PROJECT-bionimbuz.md` was produced by
running the same probes by hand — so a wrong number is a signal rather than a
mystery.

## After this

If it passes, the next question is the one this cannot answer: whether an
agent following the skill unattended produces a report worth reading, or
whether it skips the probes and writes something plausible from the file
names. That needs the whole survey, not two steps of it.
