# What zread produced, and what the probes produced

Same codebase, same model. BionimbuzWeb, Qwen3.8-27B on oMLX.

The comparison is not close, and not in the direction the probe work assumed.

## The two outputs

**Six probes, 0.3 seconds:**

```
32,942 lines / 209 files
101 routes, 1 static mount, 1 dynamic dispatch
64 templates, 676 field reads, 96 method calls
53 of 73 files touch Play
25 entities, 42 query sites, 218 fields, 438 accessors
3 framework-free tests
```

**zread's catalog, twenty minutes:**

```
项目概览：BioNimbus 生物信息学云门户
Play 1.5 框架与项目整体架构
自定义 CRUD 框架：BaseController 与模板回退机制
控制器分层与路由设计（adm/guest/security）
数据模型与 PostgreSQL 数据库设计
云插件、凭证与镜像管理
执行器与协调器：应用定义模型
云实例创建与远程执行流程
工作流 DAG 编排与执行引擎
存储空间与文件管理
外部访问接口：令牌认证与文件传输
价格表定时同步机制
用户认证与基于角色的菜单权限
加密文件字段：FileField 与 Hibernate UserType
视图模板体系与 CRUD 标签库
单元测试：图解析与加密器
数据库初始化脚本与运维工具
… 20 pages
```

## The difference

The probes say **how much of what kind of thing**. zread says **what the
system is**.

It is a bioinformatics cloud portal. It orchestrates workflows as DAGs. It
provisions cloud instances and runs jobs on them. It keeps a price table
synchronised on a schedule. Files are encrypted through a custom Hibernate
`UserType`.

None of that is derivable from 101 routes and 25 entities. All of it is
what someone would want to know first.

## The one that stings

**`自定义 CRUD 框架：BaseController 与模板回退机制`** — a custom CRUD framework
with template fallback.

`scan-template` counted `#{crud.custom}` 68 times and reported the number.
zread read the same code and worked out that the project *built its own CRUD
layer on top of Play's*, with a fallback path for templates.

That fact changes a migration estimate more than any count in the probe
output, and no probe would ever produce it — not because of a gap in the
probes, but because it is a conclusion rather than a measurement.

## What this settles

The probes were built on a premise: scripts enumerate reliably, models
hallucinate, therefore facts come from scripts. The first two clauses are
still true — Swimm's 24–35% coverage figure has not changed, and neither has
the template scanner's own bug history.

The premise that does not survive is the third one. **Enumeration is not the
same as fact.** "101 routes" is a fact of a kind, and a weak one. "This
system orchestrates workflows across cloud instances" is also a fact, a more
useful one, and no script produces it.

The probes' actual role is narrower than claimed: they are a floor, not a
description. They guarantee the counts are complete and the parse gaps are
visible. They do not, and cannot, say what the thing is.

## What zread does not give

Reading its catalog against the probe output, three things are missing that
the probes have:

- **No denominators.** 20 pages of prose about a system with 101 routes;
  nothing says whether all 101 are covered, and nothing would say if 12 were
  missed.
- **No provenance.** Every heading is stated in the same voice. Which of them
  came from reading a class and which from inferring across three files is
  not distinguishable.
- **No parse gaps.** The probes report exit 3 and list what they could not
  read. A wiki generator has no equivalent — anything it did not understand
  is simply absent, and absence is invisible.

The published accuracy figure for this category on Java projects is 57.9%
(CodeWiki). Four in ten claims unsound, with no way to tell which four. That
number is exactly what the missing provenance costs.

## The shape this suggests

Not probes *or* a reader. The probes as the floor, the reader for the
description, and the provenance labels applied to the reader's output rather
than the probes':

```
probes    complete counts, visible gaps        — the denominator
reader    what the system is                   — the description
labels    which claims were read vs inferred   — applied to the description
```

The labelling belongs on the part that infers. Putting it on the counting is
where the probe work spent its effort, and counting was never the part that
needed it.

## Method notes

zread ran at `--yes --stdio`. Without `--stdio` it exits 0 having done
nothing — no TTY, no interface, silent success. Worth knowing generally: an
external tool's exit code is not evidence that it ran.

Catalog took about twenty minutes on a local 17 tok/s model, 55K prompt
tokens in, 2.7K out. zread's own deployment uses cloud GLM, so this says
nothing about zread's speed and everything about what it costs to run this
kind of reading locally.

## How zread does it

Extracted from the binary (`strings -n 20`), so this is what it actually
sends, not what its documentation claims.

### Two agents, and the first one's job is not extraction

The catalog agent's opening line is the whole difference:

> You are an expert software engineer and technical writer with deep
> experience in deconstructing complex codebases. **Your specialty is not just
> reading code, but understanding its design philosophy, identifying its
> target audience, and communicating its essence** in a clear, structured, and
> user-oriented manner.

Then a four-step framework, in this order:

```
Step 1  Why it exists      — what problem, what a reader would learn
Step 2  What and how       — architecture, the 2-3 modules that are its heart
Step 3  Who for            — frontend / backend / algorithms / learners
Step 4  How to present     — structure the catalog
```

**"Why" comes before "what".** The survey skill written here starts at
step 2 and never reaches step 1, which is why its output is a table of counts:
that is what step 2 alone produces.

Step 4 carries the rule that shows up directly in the output:

> **Abstract, Don't Mirror:** Do not use file or folder names as headings.
> Create meaningful topic titles.

Hence `工作流 DAG 编排与执行引擎` rather than `controllers/`. The probes here
mirror by construction — one per file type — and so does everything built on
them.

### The page agent

```
Documentation Framework: Diátaxis methodology + AIDA narrative structure
Analysis Pattern: Start with first principles, identify core patterns,
                  then examine implementation detail
Tool Usage Protocol: Hypothesis-driven investigation — formulate specific
                     architectural questions, select precise tools
```

Three tools only: `dir_structure`, `view_file`, `run_bash`. Nothing
specialised, no parsers, no index. The leverage is in the framing, not the
instrumentation — which is worth sitting with, given how much of the work here
went into instrumentation.

### On provenance, it chose the other strategy

> **Evidence Standard:**
> - Sources: `[filename](relative/path/to/file#L<start>-L<end>)` at paragraph
>   boundaries
> - **Zero speculation — document only verifiable patterns**

So it does demand evidence, with line ranges, at paragraph granularity. What
it does not have is a label for inference. The two approaches are different
bets:

```
zread    forbid speculation, cite everything
here     allow inference, label it [inferred]
```

zread's is simpler to follow and loses whatever a careful inference would have
contributed. The labelling approach keeps the inference and depends on the
model labelling honestly — which is the thing the published 57.9% accuracy
figure suggests models do not reliably do.

Neither is obviously right. But the choice was never made deliberately here;
labelling was assumed, and this is the first look at what the alternative
costs.
