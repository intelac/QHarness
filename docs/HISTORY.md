# How this came to be

This repository starts from a single commit. What follows is the record of
the work that produced it — 51 commits across two repositories, kept here
because a commit message explains why a thing is the way it is, and that
reasoning outlives the diff it was attached to.

## nexum-fix

### A FIX order router with an agent interface, and a harness to test one

NEXUM holds the FIX sessions and every order's state, folded from an
append-only journal rather than held in memory, and exposes them to an agent as
MCP tools. The harness is the other half: it stands on both sides of someone
else's router — sending requests in as a client, answering as a market — and
checks what that system passed back.

Nothing the market side sends is derived. execType and ordStatus go out exactly
as given, including combinations a real venue would never produce, because how
a system handles those is what a conformance test is for.

422 tests, each checked by reintroducing the bug it guards against and watching
it catch it.

### fix: tell a caller what actually happened to its order

An agent placing orders for someone who does not know FIX has only what the
tools return, and three different situations were reaching it as the same
word. Told 'rejected' with no reason, it went looking for a fault in the
order — a symbol, a client — when the market simply could not be reached.

The reason a send failed survives into the record: the engine already carried
'the transport refused the message' and replaced it with a generic 'send
failed' on the way in.

An order the router would not route explains itself. It already generated a
per-rule account of why nothing matched; place_order now returns it instead of
waiting for a state that will never arrive.

An order queued behind a link that is down says so. QuickFIX/J queues a
message for a session that is not logged on and reports the send as
successful, so the order waits for an answer that cannot come — and a caller
that gave up waiting was told it had been refused. Nothing refused it.

Also: the session bar refreshes on traffic rather than only on logon and
logout, so its sequence numbers stop showing the state at the last connection;
it stops retrying an engine that was never there; and sync-plugins keeps
installed dependencies, which it was deleting on every run.


### fix: an order that never left is not a rejection

An order held because the link to its market was down read as REJECTED, and
the journal held nothing but its creation — so the state the system reported
could not be recovered from the record it is meant to be recoverable from, and
'why was this refused' had no answer at all.

Two faults, and the second explains why the first went unnoticed. Only
ExecutionReportHandler journalled anything, so a state change with no venue
report behind it — a send that failed — changed the cache and left no trace.
NewOrderHandler now records it, with the reason it already had.

NOT_SENT is a terminal state of its own. REJECTED says a venue looked at the
order and refused it; the two call for opposite responses, because a refusal
is about the order and this is about us, and the same order goes out unchanged
once the link returns. Reporting one as the other sends its reader after a
fault that is not there — which is exactly what happened: told 'rejected', an
agent spent half an hour inventing sessions and reading configuration files
looking for a fault in an order the market had never seen.

Three tests expected REJECTED here. Their names already said what the state
should have been — closesItLocally, aFailedSendClosesAnOrderThatNeverLeft —
so only the assertions were wrong.

The skill now carries what to do about each ending rather than only the happy
path: that not sent is not a rejection, that order_events holds the reason,
and that inventing a session to work around a failure leaves the deployment
holding something nobody configured.


### feat: ask where an order would go before sending it

Routing is decided from what a message carries, not from the connection it
arrived on. Nothing exposed those rules, so an agent holding order and session
tools could learn where an order goes in exactly one way: send it and watch
where it lands. That is an expensive question to ask on a live venue, and it
cannot be asked at all about an order that should not be sent yet.

explain_routing answers it without sending anything. With no arguments it
lists the rules; describe an order and it reports the client it would be
recognised as and the destination it would take, naming the condition that
failed when it would go nowhere. Both readings come from the same Router the
engine routes on, so the tool and the engine cannot disagree — a second
reading of the configuration would agree the day it was written and drift
after.

The trial runs as soon as anything is described rather than waiting for a
valid order, because the case that wants the answer most is an order not
written yet. Rules match on whatever tags they name and the tool cannot have a
parameter for each, so `fields` carries arbitrary tag=value pairs; what cannot
be parsed is skipped rather than refused.

It says what it does not answer, too. Where an order would go is not whether
it would arrive, and the tool points at list_sessions for the second question
— conflating the two is what sent an agent hunting for a fault in an order
whose venue was simply down.

Eleven tests, each confirmed to fail against an injected fault: a side left
untranslated, extra tags dropped, the destination hop skipped once the client
hop failed.


### docs: tell the skill where an order goes, now that it can be asked

The skill forbade reading the deployment's configuration to work out topology,
which was right, but it offered no way to answer the question that sent an
agent to those files: where does this order go. explain_routing answers it, so
the prohibition now names a replacement rather than only a rule.

Three places said less than the tools now allow. The order that leaves the
client and never reaches the market pointed at comparing tag 115 against the
configured clients by hand. `not sent` pointed only at the session being down,
which is one of its two causes — an order that matched no destination rule is
also never sent, and looking only at sessions would find nothing wrong. And a
new section says plainly that routing is decided by what an order carries
rather than by the link it arrives on, because a logged-on session reads as
proof that orders reach it and is not.

Writing the section turned up a fault in the tool it describes. An order
refused before routing was still told "Would be sent to OMS->LSE" — true of
the destination rule in isolation, a contradiction beside the refusal above
it, and the half a reader acts on is the wrong one. It now says the rule would
have been that one but the order does not get that far, and drops the
list_sessions hint, which is beside the point for an order that never leaves.

The existing test asserted both hops were reported, which they were; it said
nothing about the report reading coherently. The new one fails against the old
wording.


### feat: a session says how it is reached

Standing a counterparty up against a session needs the port, which side dials,
and the two CompIDs. The id carries the CompIDs and the engine knew the rest,
but reported none of it — so the only way to answer "how do I connect to this"
was to open the configuration file, which is the file a reader is told not to
trust because it may not be what is running. Testing a system on another host
cannot open it at all.

list_sessions now says. The port and role come from the settings QuickFIX/J was
started with rather than from a second copy kept alongside, because two copies
of the same fact drift and the engine's own is the one that is true.

Where the wiring is not known the port is 0 and the role is "unknown". A
guessed port would be worse than an admitted gap: a gap is investigated, a
guess is dialled.

SessionStatus gains a compact constructor for the three new fields, so every
existing caller stands unchanged.

Four tests, three of them confirmed to fail against a transport that cannot
read its own port.


### refactor: the harness is its own process

It tests systems that are not this one, and living inside the engine made that
awkward in ways that were not obvious until they were paid. Reaching it meant
running an engine — which meant a configuration file, and a placeholder session
on a port nobody uses, for a process meant to route nothing. Testing two
counterparties at once was impossible in a different way: the rig held exactly
two endpoints, keyed "client" and "market", and standing up a second client
silently took the first one's connection down.

So: three modules. fixprobe is the harness with its own Main, taking a port and
nothing else, because what it connects to is a scenario's answer rather than a
deployment's. nexum-mcp holds what a tool is, the gate in front of it, and the
MCP server, shared because both offer tools and a second copy of the gate is a
second thing to get right. nexum keeps the engine.

The dependency runs one way and only one way: fixprobe knows nothing of the
engine. The test that drives a real router from both sides lives in nexum with
fixprobe in test scope, which is the direction that keeps that true.

Endpoints are named now. "client" and "market" still mean what they did and
still imply which way they connect, and any other name works given `dials`.
Several run at once, which is what testing a router that serves several clients
over one session actually needs. A name already in use is refused rather than
replacing what is running, and a name that is not connected is answered with
the ones that are.

Two faults found while testing this, both mine: text() treats an absent value
as a caller error, which is right for a required parameter and wrong for the
two optional ones I added, so `dials` and `endpoint` were required in practice
while declaring themselves optional. Every pre-existing optional guards against
this correctly.

ToolSchemaTest asserted every choice offers its values, and listed "side" among
them. It no longer is a choice, so the assertion moved rather than the design.

Verified across two probe processes: one dials the other, an order crosses, and
a second client on a second port carries its own traffic without touching the
first.


### docs: point the harness scripts and skill at the probe

All three scripts dialled 18090, which is the engine, and the engine no longer
carries harness tools — every one of them answered "unknown tool". They take
FIXPROBE_PORT now, defaulting to the port fixprobe uses when started without
one, so several probes can be driven from the same shell.

A probe that is not running was a Python traceback. That is the ordinary
mistake now that it is a separate process, so it says what to start instead.

The skill said the harness came up with the engine. It now opens with starting
one, and covers what testing against an unfamiliar system actually runs into:

CompIDs are crossed and are not names you choose — they are what the
counterparty expects, and getting them wrong looks like TCP connecting and the
logon being dropped without an answer. A NEXUM under test answers this itself:
list_sessions names both CompIDs in each session id and now gives the port too,
so the connection parameters come from the running engine rather than from a
configuration file that may not match it.

Two endpoints claiming one CompID fight over the session — both report
connecting, each logon knocking the other off — which reads like the second
endpoint broke the first. Found by following the skill's own new section
literally, which is the reason to follow it rather than only write it.

Also: host is client-side only, so testing across machines means the system
under test dials your address; and every endpoint speaks FIX.4.4, so a system
on 4.2 or 5.0 cannot be reached at all.


### fix: an omitted LeavesQty is not a finished order

An agent driving a full lifecycle through the harness reported NEXUM as
returning LeavesQty=0 on every acknowledgement, and it was reading the wire
correctly — but the wire said that because the harness had sent it. The field
was optional and absent meant zero, so a market-side report that simply did
not mention it claimed the order was finished.

Zero is not "unstated". It is what a venue sends when nothing is left, so an
acknowledgement carrying OrderQty=1000 beside LeavesQty=0 says the order is
both fully open and entirely gone. A system reading the second field has a
position it believes closed.

It also quietly wasted the test. No venue sends that report, so a scenario
omitting the field asked the system under test about a case that cannot
happen while the case that does happen went unasked — a conformance harness
passing a system it never exercised.

Absent now means the identity every execution report holds: OrderQty - CumQty,
floored at zero because a negative quantity is not a smaller number but a field
no counterparty parses. Stating a figure still sends that figure, zero
included: a conformance harness has to be able to produce a malformed report
on purpose, and this only stops it happening by accident.

Five tests, two of them confirmed to fail against the old behaviour. The
derivation is called rather than recomputed — a test that restates the rule
passes whatever the rule becomes, including its deletion.


### feat: lay a message out the way it travelled

The three views of a message stacked in a column, each under a heading — "As
the client sent it", "As it went to the venue" — and which side a view came
from is the first thing a reader needs and the last thing a heading conveys.
Three similar tables one above another have to be read to be told apart.

They sit side by side now, in the order an order travels: the client on the
left, this system in the middle, the venue on the right, each coloured by side.
A view that is absent leaves its column empty rather than closing the gap, so a
report from the venue appears on the right whether or not the other two exist,
and the identifier translation this system performs is visible without reading
anything — the client's id on the left, ours on the right, on the same row.

That needed the width, so the summary moved above the events instead of beside
them. It is a few lines and reads across in a row; the events below it are what
needs the room.

Two faults found by looking at the result rather than at the DOM. Columns as
thirds of the panel left a single view stranded against the right edge with a
void beside it, so they are capped. And the tag table's fixed pixel widths were
set when one message had the whole panel: at a third of it the value column was
left about twenty pixels, and "UI-4" came out one character per line. They are
proportions now.


### feat: an order's history reads as a conversation

Opening an event showed every field of all three messages at once, which is
more than opening an event asks for — a reader wanted to see that something was
said and by whom. Each view is a bubble now, showing the line they are actually
comparing across the three: the identifier that side used, and the quantities.
The tags are behind a second click.

The bubbles are shaped like what they are, one party saying something, and the
tail points the way the message went. Both counterparties always speak toward
us — a client's order arrives from the left, a venue's report from the right —
so only this system's own message varies, and it is the one worth showing:
an order on its way out to the venue and a report going back to the client are
different halves of the conversation. Which way it went is read from who else
spoke in the same event.

The tail is two triangles, the outer in the border's colour and the inner in
the bubble's. One triangle in the border colour reads as a notch cut out of the
panel rather than as the bubble's own point — visible in a screenshot, and not
in the DOM, where a single ::after is as correct as two.


### fix: the summary took more width than it has content

Two blocks of five short lines were 175px and 186px wide, most of it empty:
the label column was a fixed 92px where the longest label needs about 74, and
each block carried a 210px floor set when the summary shared a row with the
events rather than sitting above them.

The label column is sized to its labels now and the floor is gone, so each
block is as wide as its own longest line — 153px and 133px. The gap between a
label and its value is a gap, not a gulf.


### fix: the summary reads across, not down

Nine short facts stacked in a column made a tall thin strip on a screen wide
enough to show three messages side by side, and every line of it pushed the
events — the part being read — further down. Label above value, the pairs in a
row: the whole summary is two lines now, and the history starts seventy pixels
higher.


### fix: the bubble tails pointed backwards

A CSS triangle faces away from the edge that carries the colour, so a tail
sitting on the right of a bubble and filled with border-left-color points left
— back into the bubble it grew out of. Two of the three were drawn that way:
the client's message and this system's outbound one both aimed at their own
speaker instead of at whoever they were addressed to.

The venue's was right by the same mistake twice over — a tail on the left
filled with border-right-color — which is why the conversation looked half
correct rather than plainly wrong.


### fix: point the tails the right way, for the right reason

The previous commit reversed all three and made the layout worse: it claimed a
triangle points away from its coloured edge, which is backwards. A CSS triangle
is a zero-size box with thick borders; colouring one side leaves a triangle
whose flat edge is that side and whose apex reaches the opposite way. A tail
sitting on the right and meant to point right is border-left-color — the filled
edge against the bubble, the point away from it.

So the original was right for the client's message and this system's outbound
one, and wrong only for the venue's. Both are correct now, and the reasoning in
the comment is the reasoning the code actually follows.


### feat: mark who is speaking, and which way

A heading says whose message a bubble holds, and a reader scanning a history is
matching shapes rather than reading sentences. Each speaker carries a mark now:
the client is a person, the venue is the market it goes to, and this system's
own message is an arrow — unlike the other two it is not a party but a
movement, and the direction is the news.

The arrow follows the message and the wording with it: an execution report
passed on to the client reads "As it went to the client" pointing left, where
before every one of them said "to the venue" whichever way it had gone. The two
counterparties never move, so their marks do not.

The venue's mark and heading sit at the right-hand end of its bubble, the side
its message arrives from, rather than at the left where the other two begin.


### fix: the speaker stands outside the bubble it blew

The mark went inside the heading, where it read as part of what was said rather
than as who was saying it — a bubble labelling itself. It stands beside the
bubble now, and the bubble is blown from them: the client and this system on
the left of theirs, the venue on the right of its own, which is the side its
message arrives from.

That settles the tails, which I had been reasoning about rather than looking
at, and got wrong twice. A tail reaches back to whoever is holding the bubble.
The speaker's mark stands on that side, so the two are always adjacent and
neither can be wrong on its own.


### fix: record what the client was told, and say it is that

A report arriving from the venue was journalled as the venue's message alone.
The engine forwarded it — the client had the report, with its own identifiers
back in place — but the record kept only half the moment, so it could answer
what the venue said and not what our client saw. The second is what a client
disputes.

Both halves go in the same entry now, the forwarded message under the `c.`
prefix the decoder already reads. The message has to be built before the entry
is written rather than after, which is the only reason it was missing.

Then the screen showed it under "As the client sent it" beside a person's mark
— a fill reported to us by the party we report fills to. The client column
holds two different things depending on which way the event went, and so does
this system's own: an order arriving, or a report being handed on. Both now
take their heading and their mark from the direction, worked out once per event
from whether the venue spoke.


### fix: this system keeps the middle column

The columns were laid out by the journal's prefixes, and those are not the
three parties. `c.` is a message a client sent on the way in and one this
system sent on the way back, so an inbound report put our own message in the
client's column and left the middle empty — the router stepping out of the
middle of its own conversation.

The columns are the parties now, always in the same places: the client on the
left, this system in the middle, the venue on the right. Which party spoke
decides the column; the prefix only says which wire the message was read from.

The headings and marks follow from that rather than from the direction, so both
get simpler: the middle column says which way it was passing something on, and
the two counterparties say what they always said.


### fix: this system's bubble points at whoever is being told

Its tail was fixed to the left on the reasoning that its mark stands on the
left and a tail reaches back to its speaker. That rule is right for the two
counterparties and wrong for this one: here the speaker is the bubble, so a
tail reaching back to the speaker points at itself, and a report being handed
on to the client was drawn blowing toward the venue that had just sent it.

It points at the recipient instead — right to the venue on the way out, left to
the client on the way back. That is the one thing about this bubble that
changes from event to event, which is why it is the thing worth drawing.


### fix: this system stands on the side it is speaking away from

Its mark was pinned to the left of its bubble whichever way the message was
going, so a report being handed back to the client showed the speaker on the
far side from the party being spoken to — the words travelling back over the
speaker's own head.

It changes sides with the direction now, the way the venue already did: on the
left carrying an order out to the venue, on the right handing a report back to
the client. The tail follows the mark, as it does for the other two, so the
speaker and the point the bubble grew from stay together and neither can be
wrong on its own.

The previous commit moved the tail without moving the mark, which separated
them and was worse than what it replaced.


### feat: this system speaks from one place

Its mark changes sides with the direction, and in a column sized like the other
two it moved by the width of a bubble between one event and the next — the
party that never moves, drawn moving.

The middle column is wider now and split in two halves with the mark on the
line between them, so it lands in the same place on every row and the bubbles
blow out from it: left toward the client, right toward the venue. A grid rather
than a flex row, because in a row the mark sat wherever the bubble left it,
which is a different place depending on which side the bubble is on.


### fix: an event describes itself in one line

Time, what happened and which wire it arrived over were stacked, so every event
cost three rows before a single message was read — twenty-one rows of heading
on an order with seven of them. They are one fact about the event and they read
across.


### fix: mark every event that moved the order

The dot was given to entries the journal files under "state", which left the
first row of every order without one — an order being created moves it to
pending new like any other event, and is only a different kind of entry because
it also carries what the order is.

It goes to any event that reports a state now. A first row drawn unlike the
rest reads as a distinction meant to be acted on, and there was none.


### feat: the split between list and detail can be dragged

It was fixed at 46%, which is a guess at something the page cannot know:
scanning a hundred orders wants the grid, following one order's conversation
wants the panel, and both happen in the same session.

The split is its own element rather than a border on the panel — a line one
pixel high is a line nobody can grab — and it carries a handle so it looks
like something to take hold of.

The size is kept as a fraction rather than pixels, so it survives the window
being resized: a panel pinned at 400px is most of a laptop screen and a corner
of a monitor. It is remembered per browser, since it is a preference about how
someone reads rather than anything about the deployment, and storage that
cannot be read just leaves the default. A double-click restores it, which is
quicker than dragging back to a size nobody measured on the way out.


### fix: an amended order is for what the venue agreed to

Ten orders across the lifecycle turned up one that read 1,500 filled of 1,000.
An accepted replace advanced the client's identifier and left its terms alone,
so an order amended from 1000 to 1500 and filled in full reported a sum that
says the record is wrong without saying which half to believe.

The terms the venue has just agreed to go onto the client's view with the
identifier. The monitor keeps its own projection and re-reads neither, so the
state change carries the quantity as of that moment — a projection built from
the original is exactly how this went unnoticed, since every screen agreed with
every other and all of them were wrong together.

Also, the tag tables size their columns to their contents. Fixed proportions
gave every column the same share of every message, so two-digit values held a
third of the width while "AutomatedNoIntervention" beside them was cut off and
a timestamp wrapped in a column with room to spare two rows below.

And this system's mark sat an icon's height above its bubble on the way back:
the mark is written first and belongs in a later column there, so automatic
placement gave it a row of its own.


### fix: give the value its width back, and put the meaning under it

Four columns do not fit in a third of the panel. Fixed shares gave the value
about twenty pixels, so a ClOrdID came down the page one character per line;
sizing the columns to their contents fixed that and moved the squeeze to the
meaning, where "AutomatedNoIntervention" arrived a syllable at a time.

The meaning goes underneath its value now. It has the width of the row there,
and costs a line only on the rows that have one — most FIX values are codes
nobody needs a gloss for.

The tag and the name take what they need and never wrap: they are short, and a
reader scans down them. The value keeps a floor, because it is what the reader
came for and a truncated value is a wrong value.


### fix: a refused cancel or replace is answered, not dropped

An agent amending an order that had not yet been acknowledged watched the
request vanish: nothing reached the venue, nothing came back, and the journal
showed the order arriving and then nothing at all. That is the shape of a
message this system lost, and it was not — the order had refused it, and said
why, and the reason went no further than the `if` that read it.

    if (!OrderEventPublisher.accepted(concluded)) {
        return;
    }

FIX has a message for exactly this. The reject names the request in ClOrdID(11)
and the order in OrigClOrdID(41), and CxlRejResponseTo(434) says which of the
two kinds it answers — the only field separating a refused cancel from a
refused amendment, and the one a client matches on. OrdStatus(39) carries what
the order still is, since the client has just been told what it is not, and
Text(58) carries the order's own words rather than a description composed at
the point of sending.

The refusal is journalled too. It changes no state, which is why the history
has to show it: an entry list of only what moved the order shows the request
arriving and nothing following.

While proving it, a second gap: an order with a request already in flight
accepted another. Both would then answer to the same order and the venue's
reply would match whichever this system guessed. One request at a time now —
the state already recorded what was outstanding and nothing consulted it.


### fix: show the reject, and show it going the right way

The refusal was recorded as facts about itself — which request, why — and not
as the messages it was made of, so the screen had an event with "no message
recorded" against it. A client asking what it was actually told would be sent
back to a session log to find out.

Both messages go in, the way an accepted request records them: what the client
asked for and what it was answered.

The reply carries an `r.` prefix rather than `d.`, because `d.` means it went
out to the venue. Taken at its word — and a screen does take it at its word —
the rejection was drawn travelling to a venue that had never seen the request,
which is the one thing the event exists to say did not happen. With the prefix
right, the direction follows from the data instead of being inferred from
whether a venue happened to speak.


### fix: find an order by any identifier it answers to

An agent that had just read O0000001 off the venue side asked for that order
by that name and was told no such order existed — three times, in three
different ways, while the order sat in the cache the whole while. It then
concluded the engine had released a settled order, which was wrong, and it had
nothing to go on that would have said otherwise.

Its choice was the only one available to it. One order answers to four names —
the identity this system mints, the ClOrdID the client sent, the one this
system puts on the wire, the venue's own — and which one a caller holds depends
on which message it just read. The tools took the first and said "From
place_order or list_orders", which is where the other three do not come from.

The cache already indexed all four. The lookup now tries them in turn, and
order_events resolves through it before reading the journal, which is keyed by
the minted identity alone.

A refusal names what would have worked, and says settled orders are released
about half an hour after their last report — "no order X" is true both of an
id never used and of one whose order finished, and those call for different
things.


### docs: split the harness skill along what is being asked, not which process answers

One skill covered both driving FIX traffic into a router and asking NEXUM why
an order ended where it did. Those are different jobs with different readers,
and the merged version served the second badly: an agent pointed at another
firm's engine was told to call explain_routing and create_session, which that
engine does not have, and read CompID guidance written around NEXUM's session
naming as though it were how engines generally work.

Splitting by process would have been the obvious cut and the wrong one. The
diagnostics are where the value is, and they are exactly what crosses: the
probe sees "nothing arrived" and cannot tell a refusal from a routing miss from
a downed venue, while the engine can name which. Cutting there leaves neither
half able to finish a diagnosis.

So the cut is by task. fix-harness drives any FIX engine and names none;
nexum-operate reads a running NEXUM. Each carries the diagnostics from its own
vantage, says what it cannot see, and points at the other for that part.

Also documents what the four-identifier lookup means for a reader: an id off
the venue side is a valid way to name an order, and a lookup that fails on one
is not evidence the order was released.


### chore: keep the running configuration out of git

nexum.yaml is per-machine — ports, journal paths, and on a deployment a
password. The example beside it says the real one is not in git; nothing was
enforcing that.


### docs: describe the repository as it is now

The README still described one project with a harness bolted onto it, under a
name the repository does not have. Since then the probe became its own module
that does not depend on the engine, the MCP server moved out into a third, the
skills split in two, and the test count moved by thirty-four.

The layout table listed five of the eight directories. A reader looking for
fixprobe or mcp found neither, which are precisely the two that explain why
testing NEXUM is the same job as testing anyone else's engine — done with a
tool that has no knowledge of it.

Every path and command here was checked against the working tree: the modules
build from the root, the scripts exist, and fixprobe's dependencies are
quickfixj and the shared MCP module, with no route to the engine.



## KDHarness (deployment)

### chore: put the project under version control

Nothing here was versioned: the FIX engine, its 368 tests, the deployment
files, and the browser plugin could all be lost to one mistake.

harness is a dependency rather than our code, so it is a submodule pinned to
the dsh-v0.1.1-rc.2 tag, and the session-bar plugin moves out of its packages
tree into plugins/. Kept inside a vendored tree, that plugin would be a local
commit to carry forward through every upstream upgrade, and the submodule
could not even record it — the commit exists only here, so a fresh clone
would fail to fetch it.

Excluded: build output, installed dependencies, browser-automation
screenshots, and the runtime state (journal and logs) that belongs to a
particular deployment rather than to the source.


### build: reproduce the plugin's place in the vendor tree from our own sources

The plugin has to sit inside harness/packages/client/ to build: the build
presets locate a package by globbing packages/*/*/package.json, and that glob
does not follow symlinks, so a link from the vendor tree to plugins/ is
invisible to them. Registering the package also means editing the aggregate
tsconfig, knip, the Model Experience audit list, and two generated catalogs —
47 lines that live in the vendor tree.

Rather than commit any of that into the submodule, plugins/ stays the source
of truth and scripts/sync-plugins.sh reproduces both halves: it copies the
sources into the workspace and applies the registration patch. Verified from
a pristine harness checkout — sync, install, build, 13 tests passing.


### build: ignore the harness working tree's generated modifications

sync-plugins.sh copies our plugin in and applies the registration patch, so
the submodule's working tree is always dirty by design. Only a change to the
recorded commit — an actual harness upgrade — should show up as a change here.


### chore: rename the project to KDHarness

The directory moved to ~/projects/KDHarness; the dsh profile's link: target
and the absolute paths in nexum/docs were updated to match.


### feat(sim): match orders against a moving price

The venue decided an order's fate from its symbol: a ticker was listed as
resting, partial, or rejected before the run started, so a limit price
changed nothing and an order routed to "resting" sat there for ever no matter
what it was worth. Fills and partial fills were therefore never driven by
anything — they were scripted, and the arithmetic they exercised was the
script's rather than a book's.

MatchingBook holds the resting orders and a price per symbol, and trades an
order when its limit crosses. It gives up a slice at a time, so an order
fills over several executions with cumQty accumulating across them, and the
runner walks the price so a resting order is reached by the market rather
than by a flag.

Matching is serialized on the book. Reading what an order has left, deciding
a slice, and writing it back is one step because a cancel arriving between
the read and the write retires quantity the fill then reports as traded, and
the client's position is wrong from then on with nothing to show why. The
race test drives a cancel against a price thread 200 times and checks each
report's cumQty equals the fills that produced it — with the synchronization
removed it fails.

The symbol behaviours stay: a test asserting one exact sequence needs a venue
that answers the same way every time. "match" is a new behaviour beside them.


### feat(harness): stand on both sides of an order router under test

Testing another system's order engine means being its client and its market at
once: sending it a request as a client would, then answering as the market
would and seeing what it passes back. CounterpartyHarness is one FIX endpoint
doing either job, HarnessRig holds the pair, and HarnessTools exposes both
halves so an agent can drive a scenario and read what the system did with each
message.

Nothing is answered automatically. A conformance test asserts what a system
does with a particular message, so execType and ordStatus are sent exactly as
given rather than derived from each other — including combinations a
well-behaved venue would never produce, which is what a test needs to provoke.

Everything is per instance, unlike the simulators this grew out of: a harness
runs both endpoints at once against different hosts, and static session state
would leak one into the other.

Verified against NEXUM, which has the shape the harness is built for and whose
behaviour is known, so a failure is the harness's rather than an unfamiliar
system's. Pointing it at another router is a change of host and port.

Writing the tests surfaced a real gap: the first version never sent
OnBehalfOfCompID, so NEXUM could not attribute an order to a client and
dropped it silently. A router serving many clients over one session identifies
them by that field, so it is now a parameter on every client-side tool.


### feat(harness): offer the harness through MCP behind its own grant

The harness reaches a system under test, not the deployment's venue, so it
carries its own destination and its own switch: harnessCalls permits talking
to a system under test, maxCalls permits sending real orders. Granting either
must not grant the other, and both directions are tested.

That required two changes to the gate. A tool can now name the destination it
reaches, so it is judged against that rather than the venue it never touches.
And the registry is told which venue a tool reaches by default, because
deciding what to *offer* previously asked only whether any unlock existed at
all — so enabling the harness listed place_order to a model that had no
permission to trade. Refusing at the call was already correct; a tool a model
cannot see is one it cannot be talked into calling, which is the point of
withholding it.

Verified on a running deployment: with the harness enabled and trading off,
the ten harness tools are offered, the order tools are neither listed nor
callable, and a direct call to place_order is refused naming the venue.


### test: pin down that sessions can be added and removed while running

Adding a counterparty was assumed to need a configuration edit and a restart,
which drops every other session with it. It does not: one plugin already owns
one session, its connector starts inside an effect, and the hub hands back a
disposer when a session registers — the pieces were all there, but nothing had
ever exercised them that way.

Five tests now do: a session appears once loaded, disappears once unloaded,
neither disturbs the others, and one can come back on the same port — which is
what shows the connector was stopped rather than merely deregistered.

Adding a session takes two steps rather than one, which the tests make
explicit: its dialect is declared first, because the transport asks the
registry which version a session speaks as it starts, and one added without a
dialect fails to load. DialectPlugin's constructor list is the startup path;
DialectRegistry.declareSession is the live one, and it already returned a
disposer for exactly this.


### feat: bring counterparties up and down while the engine runs

create_session, remove_session and list_added_sessions add a venue or a client
without a restart that would drop every other session with it. SessionManager
owns the two steps a session needs in the order they have to happen — the
dialect is declared before the plugin loads, because the transport asks which
version a session speaks as it starts — and takes the dialect back when the
load fails, so a second attempt on the same name behaves like the first.

A session added this way lives until the process ends and is not written back
to the configuration: that file is the record of what someone chose to run,
and a process that rewrites it makes it stop being that. remove_session
refuses a configured session for the same reason.

This also fixes what watchers were told. A session announces its own creation
while the connector is starting, which is before the hub can answer about it,
so the event carried an id and nothing else — and a watcher that refuses an
incomplete row, as the session bar does rather than render undefined sequence
numbers, dropped it entirely. The session then stayed invisible until
something else happened to it. The transport now announces again once the hub
has attached, and the second event carries the state.

Verified on a running deployment with a page already open: creating a session
puts its row in the bar without a reload.


### docs: a runnable conformance scenario over the harness tools

Drives the harness through MCP with the calls an agent makes: bring both sides
up against a router, send it an order as a client, answer as the market, and
check what it passed back. Useful as a smoke test when pointing the harness at
another system, and as the worked example the tool descriptions cannot carry.


### docs: walk a full order lifecycle in the harness scenario

The first version stopped at a partial fill and reported "PASS", which
overstated it: an order also gets amended, cancelled, refused a cancel, and
rejected outright, and none of those were exercised. Five scenarios now cover
what an order does in its life, and each checks the fields a router gets wrong
— the id it hands back to the client, and whether cumQty survives a cancel.

Verified against an injected fault: dropping the id translation in
ExecutionReportHandler makes three of the checks fail, naming the venue's
internal id where the client's own belongs.


### feat: a skill and a per-call script for driving the harness

Stepping through a scenario by hand meant building MCP JSON on a shell command
line, where a stray brace produces a parse error the tool never sees and the
reply is an empty line rather than a failure — which is exactly what happened.
scripts/harness takes key=value pairs and builds the request itself, and spells
raw FIX out with tag names so a scenario reads without a dictionary to hand.

The skill carries what the tool descriptions cannot: that the market side has
to be up first because the system under test dials out to it, that tag 115 is
usually why an order leaves the client and never arrives, that the id the
system forwards under is its own and every reply must name it, and what to
check in a report that comes back.


### test: one scenario that exercises every harness tool

Thirteen tools, each used at the point in an order's life where it belongs
rather than called in a list to tick it off: both sides come up, a venue joins
mid-session, the client buys, the market acknowledges and part-fills, the
client amends, tries to cancel, the market refuses, the order fills anyway, and
everything comes down. The run reports which tools it never called, so a tool
added later without a place in the story is visible rather than silently
uncovered.

It also found something worth a decision. After an accepted amendment NEXUM
keeps reporting the client's original ClOrdID, because an order's
client-facing identity is fixed when it is created and a replace only records
the new id as an alias. FIX 4.4 has later messages name the most recent one.
The scenario reports both rather than asserting either: which id a router
should use there is a decision about the engine, not something a test should
settle on its own.


### fix: advance the client's identifier when an amendment is accepted

An order's identity does not move — it is the day, the session, and the
ClOrdID it arrived with, derived rather than allocated, and every index in the
cache leads back to it. What the client is *told* is a separate question, and
it was answered wrongly: reports after an accepted amendment kept naming the
identifier the order was born with, so a client tracking its order by the id it
last sent saw fills for an id it had already replaced.

The view the client is told under now advances on an accepted REPLACED, and
only there: a refused amendment never took effect, so the identifier stays
where it was. The advance happens after the confirmation is built, because 41
on that message names what the amendment replaced — advancing first would have
the report replace itself, which is what the first attempt did.

Found by the harness scenario, which reported the discrepancy rather than
asserting either answer while it was an open question about the engine.


### deploy: script the aliyun update to the current build

The deployed engine is a day behind and has no mcp block, so the agent cannot
reach FIX tools there at all. This rebuilds the image around a jar that has
already been uploaded and checksum-verified, adds the block, and restarts —
backing the configuration up first and printing the rollback.

The MCP port is published to the docker bridge only. Those tools place and
amend real orders, and unlike the FIX acceptor on 9880 this is not something
to put on the public internet; an agent elsewhere comes through an SSH tunnel.


### fix: say in the schema what a caller has to decide

A model picks a tool from its schema and nothing else, and eleven parameters
that decide what a call does — which side to connect, which report to send,
which request a refusal answers — were optional, because Parameter.oneOf
hardcoded required=false. Listing a choice is what marks it as having no
defensible default, so oneOf now means required and optionalOneOf is the
spelling for one that genuinely defaults; create_session's version is the only
caller that wanted it.

Two descriptions were also missing what a model cannot deduce: that the market
side has to come up first, because the system under test dials out to it and a
client brought up before it has nothing to reach; and that the clOrdId a
market-side reply names is the one the system forwarded under, not the one the
client sent, which means reading harness_traffic first.

This is why driving the harness from a model failed twice. I put that down to
the model, which was only half of it.


### fix: tell a caller what actually happened to its order

An agent placing orders for someone who does not know FIX has only what the
tools return, and three different situations were reaching it as the same
word. Told 'rejected' with no reason, it went looking for a fault in the
order — a symbol, a client — when the market simply could not be reached, and
spent half an hour building fake sessions and reading configuration files to
work out why.

Three things now say what they mean:

The reason a send failed survives into the record. The engine already carried
'the transport refused the message' and replaced it with a generic 'send
failed' on the way in.

An order the router would not route explains itself. It already generated a
per-rule account of why nothing matched; place_order now returns it instead of
waiting for a state that will never arrive.

An order queued behind a link that is down says so. QuickFIX/J queues a message
for a session that is not logged on and reports the send as successful, so the
order waits for an answer that cannot come — and a caller that gave up waiting
was told it had been refused. Nothing refused it.

Also: the session bar refreshes on traffic rather than only on logon and
logout, so its sequence numbers stop showing the state at the last connection;
it stops retrying an engine that was never there; and sync-plugins keeps
installed dependencies, which it was deleting on every run.


### chore: keep only what is specific to this deployment

The engine and its tests now live in nexum-fix, which is public. Two copies of
the same source were already drifting: the fixes of the last few hours existed
only here, and a reader of the public repository would have got an engine that
reports a queued order as refused.

What stays is what would tell someone where the running system is: the
procedure, the compose file with its published ports, the nginx configuration
naming what shares the host, and the update script. The example configuration
stays too — its password is CHANGE-ME and it is the reference for what the
engine accepts.


