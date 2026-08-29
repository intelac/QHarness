package io.nexum.web;

/**
 * The monitor screen: an order grid, and the detail of whichever row is
 * selected.
 *
 * <p>Held as a string rather than a resource file so the jar carries its own
 * interface — one artifact to deploy, and no chance of the page and the API it
 * talks to being different versions.
 *
 * <p>AG Grid loads from a CDN. A deployment without outbound access should
 * vendor the two files and change the two URLs; nothing else here depends on
 * where they come from.
 */
final class WebPage {

    private WebPage() {}

    static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>Order Monitor</title>
            <!--
              Every path below is relative, so this page works wherever it is
              mounted — at the root in development, under /nexum/ behind a
              reverse proxy that already gives /api/ to something else. The
              base tag is what makes that safe when the URL has no trailing
              slash: without it "api/orders" resolves against the parent.
            -->
            <base href="./">
            <!--
              Served from the jar rather than a CDN. This runs in a network
              where jsdelivr is not reliably reachable, and a monitor that
              renders a blank grid during an incident is worse than useless.
            -->
            <link rel="stylesheet" href="vendor/ag-grid.css">
            <link rel="stylesheet" href="vendor/ag-theme-quartz.css">
            <script src="vendor/ag-grid-community.min.js"></script>
            <style>
              :root {
                --bg: #0f1115; --panel: #171a21; --line: #262b36;
                --text: #e6e8ec; --dim: #8b93a3;
                --ok: #3fb950; --warn: #d29922; --bad: #f85149;
                /* The venue's side of a message. Not a judgement like the three
                   above — it says which counterparty a view came from. */
                --venue: #6fa8f5;
              }
              * { box-sizing: border-box; }
              body {
                margin: 0; height: 100vh; display: flex; flex-direction: column;
                background: var(--bg); color: var(--text);
                font: 13px/1.5 ui-sans-serif, -apple-system, "Segoe UI", sans-serif;
              }
              header {
                display: flex; align-items: center; gap: 20px;
                padding: 10px 16px; border-bottom: 1px solid var(--line);
              }
              h1 { font-size: 14px; font-weight: 600; margin: 0; }
              .sessions { display: flex; gap: 14px; font-size: 12px; }
              .session { display: flex; align-items: center; gap: 6px; color: var(--dim); }
              .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--bad); }
              .dot.up { background: var(--ok); }
              .spacer { flex: 1; }
              .counts { display: flex; gap: 16px; font-size: 12px; color: var(--dim); }
              .counts b { color: var(--text); font-weight: 600; }
              .counts .bad b { color: var(--bad); }

              /* Stacked, not side by side: the list needs the full width for
                 the columns that matter — state, destination, quantities — and
                 a detail panel squeezed into a column beside it pushed them out
                 of view. */
              main { flex: 1; display: flex; flex-direction: column; min-height: 0; }
              #grid { flex: 1; min-height: 0; }
              aside {
                height: var(--detail-height, 46%); min-height: 60px;
                background: var(--panel); overflow-y: auto; padding: 14px 18px;
              }

              /*
                The split between the list and the detail, draggable because how
                much of each a reader wants is not something this page can know:
                scanning a hundred orders wants the grid, following one order's
                conversation wants the panel, and both happen in a session.
                Its own element rather than a border on the panel — a line one
                pixel high is a line nobody can grab.
              */
              #split {
                height: 7px; flex: none; cursor: row-resize;
                background: var(--panel); border-top: 1px solid var(--line);
                position: relative;
              }
              #split::after {
                content: ''; position: absolute; left: 50%; top: 3px;
                width: 34px; margin-left: -17px; height: 1px;
                background: var(--line);
              }
              #split:hover { background: #1f2430; }
              #split:hover::after, #split.dragging::after { background: var(--dim); }
              /* While dragging, the pointer owns the page: without this a fast
                 drag selects the grid's text and the panel stops following. */
              body.resizing { cursor: row-resize; user-select: none; }
              aside h2 {
                font-size: 12px; text-transform: uppercase; letter-spacing: .06em;
                color: var(--dim); margin: 0 0 10px; font-weight: 600;
              }
              aside h2:not(:first-child) { margin-top: 22px; }

              /* Stacked: the summary is a few lines and reads across, while an
                 expanded event needs the full width to put the three views of a
                 message side by side. Beside each other, the views were narrow
                 enough that their headings — the only thing saying which side a
                 message was seen from — had to be read rather than seen. */
              .detail { display: block; }
              /* Sized to their content, not stretched across the width: two
                 blocks of five short lines spread over a wide screen put the
                 label and its value at opposite ends of the row. */
              .detail .facts {
                display: flex; flex-wrap: wrap; gap: 0 40px; align-items: start;
              }
              /* No minimum: each block is as wide as its own longest line.
                 A floor set for a narrower panel padded both of them. */
              .detail .facts > .block { min-width: 0; }
              .detail .facts > .block { min-width: 0; }
              .detail .facts h2:first-child { margin-top: 0; }
              .detail .history { margin-top: 22px; }
              .empty { color: var(--dim); padding-top: 30px; text-align: center; }

              /*
                Across, not down. Nine short facts in a column is a tall thin
                strip on a screen wide enough for three messages side by side,
                and it pushed the events — the part being read — below the fold.
                Label above value, the pairs in a row.
              */
              .kv {
                display: grid; grid-auto-flow: column; grid-template-rows: auto auto;
                justify-content: start; gap: 2px 26px; margin: 0;
              }
              .kv dt {
                color: var(--dim); font-size: 10px; text-transform: uppercase;
                letter-spacing: .04em; white-space: nowrap;
              }
              .kv dd { margin: 0; font-variant-numeric: tabular-nums; white-space: nowrap; }

              .ids { background: #12151b; border: 1px solid var(--line);
                     border-radius: 5px; padding: 9px 11px; }
              .ids .kv dd { font-family: ui-monospace, SFMono-Regular, monospace;
                            font-size: 12px; }

              .event {
                border-left: 2px solid var(--line); padding: 5px 0 9px 11px;
                position: relative;
              }
              /* `--dim` and not `--line`: a 6px dot in the line colour is not
                 dark, it is absent, and a row with no dot beside rows that
                 have one reads as a rendering fault rather than as a quieter
                 event. */
              .event::before {
                content: ''; position: absolute; left: -4px; top: 10px;
                width: 6px; height: 6px; border-radius: 50%; background: var(--dim);
              }
              /* Any event that moved the order, not only the ones the journal
                 files under "state": an order being created moves it to pending
                 new, and a first row drawn differently from the rest reads as a
                 distinction the reader is meant to act on. */
              .event.moved::before { background: var(--ok); }
              /* A refusal moves nothing, which is exactly why it needs its own
                 colour: drawn as an ordinary quiet event it reads as "nothing
                 happened here", when what happened is that this system decided
                 not to carry a request and said so. That is the line on the
                 order most worth stopping at. */
              .event.refused::before { background: var(--warn); }
              /*
                One line, not three. Time, what happened and which wire it came
                over are a single fact about the event, and stacked they took
                three rows each — for a seven-event order that is twenty-one
                rows of heading before any message is read.
              */
              .event .head {
                display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap;
              }
              .event .when { color: var(--dim); font-size: 11px;
                             font-variant-numeric: tabular-nums; flex: none; }
              .event .what { flex: none; }
              /* The two halves read as different kinds of thing: seven event
                 names are also state names, and in one colour a row says
                 nothing about which half is which. */
              .event .what .ev { color: var(--dim); }
              .event .what .to { color: var(--line); margin: 0 6px; }
              .event .what .st {
                color: var(--text); font-weight: 500;
                background: #1e2128; border-radius: 3px; padding: 1px 6px;
              }
              .event .wire {
                font-family: ui-monospace, monospace; font-size: 11px;
                color: var(--dim); flex: none;
              }
              .event .fields {
                margin-top: 6px; display: none;
              }
              .event.open .fields { display: block; }

              /*
                One message, up to three ways: what the client sent on the left,
                what this system passed on in the middle, what the venue said on
                the right. Which side a view belongs to is the first thing a
                reader needs and the last thing a heading conveys — laid out in
                a column, the three read as a list of similar tables and the
                headings had to be read rather than seen.

                Fixed thirds rather than auto: an event with only one view keeps
                it under the same heading as the same view on the event above,
                so a reader following one side down the history is not tracking
                a column that moves.
              */
              /*
                The middle column is wider than the other two so that this
                system's mark lands in the same place on every row: it changes
                sides with the direction, and in a column sized like the others
                it moved by the width of a bubble from one event to the next.
                Given room to sit at the column's centre, it stays put and the
                bubbles blow out from it — left toward the client, right toward
                the venue.
              */
              .views {
                display: grid;
                grid-template-columns: minmax(0, 330px) minmax(0, 600px) minmax(0, 330px);
                gap: 0 18px; align-items: start;
              }
              /*
                The mark held at the column's centre by a grid rather than a
                flex row: in a row it sat wherever the bubble left it, which is
                a different place depending on which side the bubble is on.
                Two equal halves and the mark on the line between them puts it
                in one place, and the bubble takes the half it speaks toward.
              */
              .msgview.destination {
                display: grid; grid-template-columns: 1fr auto 1fr; gap: 0;
                /* One row, explicitly. The mark is written before the bubble in
                   the markup and on the way back it belongs in a later column,
                   so auto placement put it on a row of its own and the bubble
                   started an icon's height below it. */
                grid-template-rows: auto;
              }
              .msgview.destination > .who,
              .msgview.destination > .bubble { grid-row: 1; }
              .msgview.destination > .who { grid-column: 2; }
              .msgview.destination.outbound > .bubble {
                grid-column: 3; margin-left: 10px;
              }
              .msgview.destination.inbound > .bubble {
                grid-column: 1; margin-right: 10px;
              }
              @media (max-width: 900px) {
                .views { grid-template-columns: 1fr; }
              }

              /*
                Each view is a bubble, because that is what it is: one party
                saying something. The tail points the way the message went — the
                client's toward us, ours toward whichever side it was sent to,
                the venue's back toward us — so a glance down the history reads
                as a conversation rather than as three tables.
              */
              .msgview { min-width: 0; }
              .bubble {
                position: relative; cursor: pointer;
                background: #1b1f28; border: 1px solid var(--line);
                border-radius: 10px; padding: 7px 10px;
              }
              .bubble:hover { border-color: #38404f; }
              /*
                Two triangles, not one: the outer is the border's colour and
                the inner the bubble's, a pixel further in. A single triangle
                in the border colour reads as a notch cut out of the panel
                rather than as the bubble's own point.
              */
              .bubble::after, .bubble::before {
                content: ''; position: absolute; top: 13px;
                border: 7px solid transparent;
              }
              .bubble::before { border-width: 7px; }
              .bubble::after { border-width: 6px; top: 14px; }

              /*
                A CSS triangle is a zero-size box with thick borders: each side
                is a trapezoid, and colouring one leaves a triangle whose flat
                edge is that side and whose point faces the opposite way. So a
                tail on the right that should point right is border-LEFT-color
                — the filled edge sits against the bubble and the apex reaches
                away from it.
              */
              /*
                The tail reaches back to whoever is holding the bubble, because
                that is where it was blown from. The speaker's mark stands on
                that side, so the two are always adjacent.
              */
              .bubble.client::before { left: -14px; border-right-color: var(--line); }
              .bubble.client::after { left: -12px; border-right-color: #1b1f28; }
              /*
                This system's own message points the way it was sent. Both
                counterparties always speak toward us — a client's order comes
                in from the left, a venue's report from the right — so only
                this one varies, and it is the one whose direction is worth
                showing: an order going out to the venue and a report going
                back to the client are different halves of the conversation.
              */
              /* Like the other two, the tail reaches back to the mark beside
                 it — and for this system the mark changes sides with the
                 direction, so the tail does too: on the left carrying an order
                 out, on the right handing a report back. */
              .bubble.destination.outbound::before {
                left: -14px; border-right-color: var(--line);
              }
              .bubble.destination.outbound::after {
                left: -12px; border-right-color: #1b1f28;
              }
              .bubble.destination.inbound::before {
                left: auto; right: -14px; border-left-color: var(--line);
              }
              .bubble.destination.inbound::after {
                left: auto; right: -12px; border-left-color: #1b1f28;
              }
              /*
                The venue speaks from the right of the row, so its tail is on
                its own right-hand side and the point reaches left, toward us.
                On the left it read as the venue talking to something further
                left still — there is nothing there, and the message came from
                the other direction.
              */
              /* The venue stands to the right of its bubble, so the tail is
                 on the right and reaches back to it. */
              .bubble.venue::before {
                left: auto; right: -14px; border-left-color: var(--line);
              }
              .bubble.venue::after {
                left: auto; right: -12px; border-left-color: #1b1f28;
              }

              @media (max-width: 900px) {
                .bubble::after, .bubble::before { display: none; }
              }

              /*
                Who is speaking, as a mark rather than as a sentence. The
                heading says it in words, but a reader scanning a history is
                matching shapes — the same speaker down a column should be the
                same mark, found without reading.
              */
              .msghead {
                font-size: 10px; letter-spacing: .04em; text-transform: uppercase;
                margin-bottom: 4px;
              }

              /*
                The speaker stands outside the bubble, which is blown from them:
                inside the heading the mark read as part of what was said rather
                than as who was saying it.
              */
              .msgview { display: flex; align-items: flex-start; gap: 10px; }
              .msgview > .bubble { flex: 1; min-width: 0; }
              .who {
                font-size: 17px; line-height: 1.4; flex: none;
              }
              /* The venue is on the right of the row and speaks leftward, so it
                 stands to the right of its own bubble.
                 So does this system when it is passing a report back: it is
                 speaking to the client, which is to its left, and a speaker
                 stands on the side its words are travelling away from. */
              .msgview.venue,
              .msgview.destination.inbound { flex-direction: row-reverse; }
              /* Coloured by side, so which is which survives peripheral vision:
                 the two counterparties are what a reader is comparing, and this
                 system's own view sits between them. */
              .msghead.client { color: var(--ok); }
              .msghead.destination { color: var(--dim); }
              .msghead.venue { color: var(--venue); }

              /* The line a closed bubble shows. */
              .gist {
                font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                font-size: 11px; display: flex; flex-wrap: wrap; gap: 2px 12px;
              }
              .gist .gk { color: var(--dim); margin-right: 4px; }
              .gist .gv { color: var(--text); }
              .bubble.open .gist { display: none; }

              /* The tags, behind the second click. */
              .bubble table.tags { display: none; }
              .bubble.open table.tags { display: table; }
              /*
                Every column left aligned, and every row of one table sized
                together — a table lays its columns out once, so the tag numbers
                still line up down the column even though the widths come from
                the content rather than from fractions of the panel.
              */
              table.tags {
                border-collapse: collapse; width: 100%;
                font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                font-size: 11px;
              }
              table.tags td {
                padding: 3px 12px 3px 0; vertical-align: top; text-align: left;
                border-bottom: 1px solid var(--line);
              }
              table.tags tr:last-child td { border-bottom: none; }
              /*
                Proportions, not pixels. Fixed widths were set when one message
                had the whole panel; three side by side gave each a third of it,
                the fixed columns still claimed their pixels, and the value was
                left with whatever remained — about twenty of them, so "UI-3"
                came out one character per line.
              */
              table.tags .tag { width: 1%; white-space: nowrap; color: var(--dim); }
              table.tags .name { width: 1%; white-space: nowrap; color: var(--text); }
              /* The one field that can be genuinely long, so it wraps rather
                 than being cut off — a truncated value is a wrong value. */
              table.tags .val {
                min-width: 7em; color: var(--ok);
                white-space: normal; word-break: break-word;
              }
              /*
                The meaning goes under its value rather than beside it. Four
                columns do not fit in a third of the panel: giving the meaning
                its own column squeezed it to about seven characters, so
                "AutomatedNoIntervention" came down the page a syllable at a
                time. Underneath it has the width of the row and costs a line
                only on the rows that have one.
              */
              table.tags .mean { display: none; }
              table.tags .val .mean {
                display: block; color: var(--dim); font-size: 10px;
                margin-top: 1px; white-space: normal;
              }
              /*
                Session fields, marked by colour alone.

                Named "sessionfield" and not "session": the header's session
                indicators use that class with display:flex, and it matched
                these rows too. A table row turned into a flex container leaves
                the table layout entirely — the cells collapsed to 12px and
                "FIX.4.4" stacked one character per line, which reads as a
                column-width problem and is not one.
              */
              table.tags tr.sessionfield .tag { color: var(--line); }
              table.tags tr.sessionfield .name,
              table.tags tr.sessionfield .val,
              table.tags tr.sessionfield .mean { color: var(--dim); }

              .alert {
                border-left: 2px solid var(--bad); background: #1c1416;
                padding: 8px 11px; margin-bottom: 7px; border-radius: 0 4px 4px 0;
              }
              .alert.WARNING { border-color: var(--warn); background: #1c1a12; }
              .alert .rule { font-weight: 600; }
              .alert .why { color: var(--dim); margin-top: 2px; }

              .state { font-weight: 600; }
              .state.working { color: var(--ok); }
              .state.pending { color: var(--warn); }
              .state.done { color: var(--dim); }
              .state.bad { color: var(--bad); }
              .attention { color: var(--bad); }
            </style>
            </head>
            <body>

            <header>
              <h1>Order Monitor</h1>
              <div class="sessions" id="sessions"></div>
              <div class="spacer"></div>
              <div class="counts">
                <span>working <b id="c-working">0</b></span>
                <span>pending <b id="c-pending">0</b></span>
                <span>done <b id="c-done">0</b></span>
                <span class="bad">attention <b id="c-attn">0</b></span>
              </div>
            </header>

            <main>
              <div id="grid" class="ag-theme-quartz-dark"></div>
              <div id="split" title="Drag to resize"></div>
              <aside id="detail"><div class="empty">Select an order</div></aside>
            </main>

            <script>
            const WORKING = ['NEW','PARTIALLY_FILLED','CANCEL_REJECTED','REPLACE_REJECTED'];
            const PENDING = ['CREATED','PENDING_NEW','PENDING_CANCEL','PENDING_REPLACE'];
            const BAD     = ['REJECTED', 'NOT_SENT'];

            function stateClass(s) {
              if (BAD.includes(s)) return 'bad';
              if (WORKING.includes(s)) return 'working';
              if (PENDING.includes(s)) return 'pending';
              return 'done';
            }

            const time = ms => ms ? new Date(ms).toISOString().slice(11, 23) : '';
            const num  = v => (v === null || v === undefined) ? '' :
                              Number(v).toLocaleString();

            const columns = [
              { field: 'orderId', headerName: 'Order', width: 100, pinned: 'left' },
              { field: 'clientClOrdId', headerName: 'Client ID', width: 130 },
              { field: 'client', headerName: 'Client', width: 90 },
              { field: 'symbol', headerName: 'Symbol', width: 90 },
              { field: 'side', headerName: 'Side', width: 60,
                valueFormatter: p => ({'1':'Buy','2':'Sell','5':'Sell short'})[p.value] ?? p.value },
              { field: 'orderQty', headerName: 'Qty', width: 90, type: 'numericColumn',
                valueFormatter: p => num(p.value) },
              { field: 'cumQty', headerName: 'Filled', width: 90, type: 'numericColumn',
                valueFormatter: p => num(p.value) },
              { field: 'leavesQty', headerName: 'Leaves', width: 90, type: 'numericColumn',
                valueFormatter: p => num(p.value) },
              { field: 'state', headerName: 'State', width: 150,
                cellRenderer: p =>
                  `<span class="state ${stateClass(p.value)}">${p.data.stateLabel ?? p.value}</span>` },
              { field: 'destination', headerName: 'Destination', width: 130 },
              { field: 'silentSeconds', headerName: 'Quiet', width: 80,
                type: 'numericColumn', valueFormatter: p => p.value + 's' },
              { field: 'attention', headerName: '', width: 40,
                cellRenderer: p => p.value ? '<span class="attention">!</span>' : '' },
            ];

            const grid = agGrid.createGrid(document.getElementById('grid'), {
              columnDefs: columns,
              rowData: [],
              // Identity, not row position: rows are refreshed in place rather
              // than rebuilt, so a selection survives an update.
              getRowId: p => p.data.orderId,
              rowSelection: 'single',
              defaultColDef: { sortable: true, filter: true, resizable: true },
              onSelectionChanged: e => {
                const [row] = e.api.getSelectedRows();
                if (row) loadDetail(row.orderId);
              },
              onGridReady: () => refresh(),
            });

            let selected = null;

            /**
             * Fetch JSON, or say what went wrong instead of throwing on it.
             *
             * <p>A proxy in front of this answers a restart with an HTML error
             * page, and handing that to JSON.parse threw "Unexpected token '<'"
             * — which reads as a bug in this page rather than as the backend
             * being briefly away.
             */
            async function getJson(path) {
              const response = await fetch(path);
              if (!response.ok) {
                throw new Error(path + ' returned ' + response.status);
              }
              return response.json();
            }

            async function refresh() {
              try {
                const [orders, anomalies] = await Promise.all([
                  getJson('api/orders'),
                  getJson('api/anomalies'),
                ]);

                grid.setGridOption('rowData', orders.orders);

                const rows = orders.orders;
                document.getElementById('c-working').textContent =
                  rows.filter(o => WORKING.includes(o.state)).length;
                document.getElementById('c-pending').textContent =
                  rows.filter(o => PENDING.includes(o.state)).length;
                document.getElementById('c-done').textContent =
                  rows.filter(o => o.terminal).length;
                document.getElementById('c-attn').textContent =
                  anomalies.anomalies.length;

                // Sessions are not drawn here: they arrive on their own
                // stream, which reports a drop the moment it happens rather
                // than up to a poll later.

                if (selected) loadDetail(selected);
              } catch (e) {
                // A refresh that fails leaves the last good picture on screen
                // rather than blanking it — stale and labelled beats empty.
                console.warn('refresh failed', e);
              }
            }

            async function loadDetail(orderId) {
              // A different order starts closed: an entry left open on the
              // previous one re-opened here and pushed the summary out of view.
              if (openFor !== orderId) {
                openEvents.clear();
                openFor = orderId;
              }
              selected = orderId;
              let d;
              try {
                d = await getJson('api/order?id=' + encodeURIComponent(orderId));
              } catch (unavailable) {
                // Leave whatever is on screen. Blanking the panel on a blip
                // loses the order someone was reading.
                return;
              }

              // The order is gone — evicted after settling, or lost with a
              // restart. Keeping the shell on screen shows a panel of dashes
              // beside an empty list, which reads as the page having broken.
              if (!d || !d.orderId) {
                selected = null;
                openFor = null;
                openEvents.clear();
                document.getElementById('detail').innerHTML =
                  '<div class="empty">That order is no longer held</div>';
                return;
              }

              const ids = d.identifiers ?? {};
              const alerts = (d.anomalies ?? []).map(a => `
                <div class="alert ${a.severity}">
                  <div class="rule">${a.rule}</div>
                  <div class="why">${a.summary}</div>
                </div>`).join('');

              // Keyed by time and type rather than by position: the list grows
              // while an order is live, and an index would move what is open
              // out from under the reader.
              const events = (d.events ?? []).map(e => {
                const key = e.at + '/' + e.type;
                return `
                <div class="event ${e.state ? 'moved' : ''} ${
                       e.type === 'request-refused' ? 'refused' : ''} ${
                       openEvents.has(key) ? 'open' : ''}"
                     onclick="toggleEvent(this, '${key}')">
                  <div class="head">
                    <span class="when">${time(e.at)}</span>
                    <span class="what">
                      <span class="ev">${e.event ?? e.summary}</span>${e.state
                        ? `<span class="to">→</span><span class="st">${e.state}</span>` : ''}
                    </span>
                    ${e.wire ? `<span class="wire">wire ${e.wire}</span>` : ''}
                  </div>
                  <div class="fields">${message(e, key)}</div>
                </div>`;
              }).join('');

              // The summary above, the history below. It is a few lines and
              // reads across in a row; the events below it need the full width,
              // because an expanded one puts three views of a message side by
              // side and each of those is a table.
              document.getElementById('detail').innerHTML = `
                <h2>${d.orderId ?? orderId}</h2>
                <div class="detail">
                  <div class="facts">
                    <div class="block">
                      <h2>Order</h2>
                      <dl class="kv">
                        <dt>State</dt><dd class="state ${stateClass(d.state)}">${d.stateLabel ?? d.state ?? '-'}</dd>
                        <dt>Symbol</dt><dd>${d.symbol ?? '-'}</dd>
                        <dt>Filled</dt><dd>${num(d.cumQty)} / ${num(d.orderQty)}</dd>
                        <dt>Client</dt><dd>${d.client ?? '-'}</dd>
                        <dt>Destination</dt><dd>${d.destination ?? '-'}</dd>
                      </dl>
                    </div>

                    <div class="block">
                      <h2>Identifiers</h2>
                      <div class="ids">
                        <dl class="kv">
                          <dt>Client</dt><dd>${ids.client ?? '-'}</dd>
                          <dt>Ours</dt><dd>${ids.ours ?? '-'}</dd>
                          <dt>Venue</dt><dd>${ids.venue ?? '-'}</dd>
                        </dl>
                      </div>
                    </div>

                    ${alerts ? '<div class="block"><h2>Attention</h2>' + alerts + '</div>' : ''}
                  </div>

                  <div class="history">
                    <h2>Events</h2>
                    ${events || '<div style="color:var(--dim)">No recorded events</div>'}
                  </div>
                </div>
              `;
            }

            /**
             * Dragging the split between the list and the detail.
             *
             * <p>Kept as a percentage rather than pixels so it survives the
             * window being resized: a panel pinned to 400px is most of a laptop
             * screen and a corner of a monitor.
             *
             * <p>Remembered per browser, because it is a preference about how
             * someone reads rather than anything about the deployment. A stored
             * value that cannot be read — a private window, cleared data — just
             * leaves the default.
             */
            (() => {
              const split = document.getElementById('split');
              const detail = document.getElementById('detail');
              const main = split.parentElement;

              const MIN = 60;
              const HEADROOM = 80;

              function apply(px) {
                const height = main.getBoundingClientRect().height;
                const clamped = Math.max(MIN, Math.min(px, height - HEADROOM));
                document.documentElement.style.setProperty(
                    '--detail-height', (clamped / height * 100).toFixed(2) + '%');
                return clamped / height;
              }

              try {
                const saved = localStorage.getItem('nexum.detailFraction');
                if (saved) {
                  document.documentElement.style.setProperty(
                      '--detail-height', (parseFloat(saved) * 100).toFixed(2) + '%');
                }
              } catch (unavailable) {
                // No stored preference; the default in the stylesheet stands.
              }

              split.addEventListener('pointerdown', event => {
                event.preventDefault();
                split.setPointerCapture(event.pointerId);
                split.classList.add('dragging');
                document.body.classList.add('resizing');

                const move = moved => {
                  // From the bottom of the window: what the reader is dragging
                  // is the top edge of the panel, so its height is whatever is
                  // below the pointer.
                  apply(main.getBoundingClientRect().bottom - moved.clientY);
                };

                const done = () => {
                  split.classList.remove('dragging');
                  document.body.classList.remove('resizing');
                  split.removeEventListener('pointermove', move);
                  split.removeEventListener('pointerup', done);
                  split.removeEventListener('pointercancel', done);
                  try {
                    localStorage.setItem('nexum.detailFraction',
                        String(detail.getBoundingClientRect().height
                               / main.getBoundingClientRect().height));
                  } catch (unavailable) {
                    // Not storable here; the size still holds for this page.
                  }
                };

                split.addEventListener('pointermove', move);
                split.addEventListener('pointerup', done);
                split.addEventListener('pointercancel', done);
              });

              // A double-click restores the default, which is quicker than
              // dragging back to a size you did not measure on the way out.
              split.addEventListener('dblclick', () => {
                document.documentElement.style.removeProperty('--detail-height');
                try {
                  localStorage.removeItem('nexum.detailFraction');
                } catch (unavailable) {
                  // Nothing stored to remove.
                }
              });
            })();

            /**
             * Which events are expanded.
             *
             * <p>Held outside the DOM because the panel is rebuilt every two
             * seconds, and a class on an element that is about to be replaced
             * closes itself again a moment after it was opened — long enough
             * to look like the page ignoring the click.
             */
            const openEvents = new Set();

            /**
             * Which order the open set belongs to.
             *
             * <p>Cleared when the selection moves: an entry left open on one
             * order re-opened on the next, pushing what the order is out of
             * view before the reader had looked at it.
             */
            let openFor = null;

            function toggleEvent(element, key) {
              if (openEvents.has(key)) {
                openEvents.delete(key);
                element.classList.remove('open');
              } else {
                openEvents.add(key);
                element.classList.add('open');
              }
            }

            /**
             * The FIX message behind one event, tag by tag.
             *
             * Three columns because that is what reading a message actually
             * needs: the number to search a log with, the name to know what it
             * is, and the value with its meaning spelled out where the value is
             * a code. Session fields are dimmed rather than hidden — they are
             * how a message is matched to a line in the engine's own log.
             */
            function message(e, eventKey) {
              const views = e.message ?? [];
              if (!views.length) {
                // Nothing was recorded from a message: an internal step, or an
                // entry written before messages were kept.
                return '<div class="nomsg">no message recorded</div>';
              }

              // Placed by side rather than in the order they arrive: the
              // client's column stays the client's column, so a reader
              // following one counterparty down the history is not tracking a
              // heading that moves. A view that is absent leaves its column
              // empty rather than closing the gap.
              const raw = Object.fromEntries(views.map(v => [v.view, v]));

              // The columns are the three parties, always in the same places:
              // the client on the left, this system in the middle, the venue on
              // the right. The journal's prefixes are not those parties — `c.`
              // is a message a client sent on the way in and one this system
              // sent on the way back — so an inbound report put our own message
              // in the client's column and left the middle empty. Which party
              // spoke is what the column means; the prefix only says which
              // wire it was read from.
              // Which way the event went. A venue view means a report coming
              // back; a reply view means this system answering the client
              // directly, which never involves a venue at all — a refused
              // amendment is the case, and inferring the direction from the
              // venue alone had it drawn as going out to one.
              const inbound = !!raw.venue || !!raw.reply;
              const byView = raw.reply
                ? { client: raw.client, destination: raw.reply }
                : inbound
                ? { destination: raw.client, venue: raw.venue }
                : { client: raw.client, destination: raw.destination };

              return '<div class="views">' + VIEW_ORDER.map(name => {
                const v = byView[name];
                if (!v) {
                  return '<div class="msgview empty-slot"></div>';
                }
                // A bubble first, its tags behind a second click. Every field
                // of three messages at once is more than a reader asked for by
                // opening an event — they wanted to see that something was
                // said, and by whom.
                const key = eventKey + '/' + name;
                // Which way our own message went, told by who else spoke: a
                // client view beside it means an order arriving, so ours is on
                // its way out to the venue; a venue view means a report coming
                // back, so ours is going on to the client.
                const way = inbound ? ' inbound' : ' outbound';
                return `
                <div class="msgview ${name}${way}">
                  <span class="who">${icon(name)}</span>
                  <div class="bubble ${name}${way} ${openViews.has(key) ? 'open' : ''}"
                       onclick="event.stopPropagation(); toggleView(this, '${key}')">
                    <div class="msghead ${name}">${label(name, way)}</div>
                    <div class="gist">${gist(v)}</div>
                    <table class="tags">
                      ${v.fields.map(f => `
                        <tr class="${f.session ? 'sessionfield' : ''}">
                          <td class="tag">${f.tag}</td>
                          <td class="name">${f.name ?? ''}</td>
                          <td class="val">${escape(f.value)}${f.meaning
                            ? `<span class="mean">${f.meaning}</span>` : ''}</td>
                        </tr>`).join('')}
                    </table>
                  </div>
                </div>`;
              }).join('') + '</div>';
            }

            /**
             * Which message bubbles are expanded.
             *
             * <p>Separate from the events they sit in, and outside the DOM for
             * the same reason: the panel is rebuilt every two seconds.
             */
            const openViews = new Set();

            function toggleView(element, key) {
              if (openViews.has(key)) {
                openViews.delete(key);
                element.classList.remove('open');
              } else {
                openViews.add(key);
                element.classList.add('open');
              }
            }

            /**
             * The one line a closed bubble shows.
             *
             * <p>What a reader is comparing across the three: the identifier
             * this side used, and the quantities. Everything else is a click
             * away, and putting it here would make the bubble a table again.
             */
            const GIST_TAGS = [11, 41, 14, 151, 38, 44, 39];

            function gist(v) {
              const parts = [];
              for (const tag of GIST_TAGS) {
                const f = v.fields.find(x => x.tag === tag);
                if (!f) continue;
                const label = GIST_NAMES[tag] ?? f.name ?? tag;
                parts.push(`<span class="gk">${label}</span>` +
                           `<span class="gv">${escape(f.meaning ?? f.value)}</span>`);
              }
              return parts.length ? parts.join('') : '<span class="gk">no fields</span>';
            }

            /** Short enough to sit several to a line. */
            const GIST_NAMES = {
              11: 'id', 41: 'was', 14: 'done', 151: 'left',
              38: 'qty', 44: 'px', 39: 'status',
            };

            /**
             * Left to right: the client, us, the venue.
             *
             * <p>The order an order actually travels. A message this system
             * sent sits between the two counterparties whatever direction it
             * was going, which is what makes a translation visible — the
             * identifier on the left and the one on the right are different by
             * design, and the middle column is where that is decided.
             */
            const VIEW_ORDER = ['client', 'destination', 'venue'];

            /**
             * What each column holds, which depends on which way the event went.
             *
             * <p>The client column carries two different things: what a client
             * sent in, and what a client was told. Labelling both "as the
             * client sent it" claimed a fill had been reported to us by the
             * party we report fills to. Same for the middle column, which is
             * this system speaking to whichever side the message was headed.
             */
            const VIEW_NAMES = {
              inbound: {
                client: 'As the client sent it',
                destination: 'As it went to the client',
                venue: 'As the venue sent it',
              },
              outbound: {
                client: 'As the client sent it',
                destination: 'As it went to the venue',
              },
            };

            /**
             * A mark per speaker.
             *
             * <p>Three parties, so three marks: the client placing the order,
             * this system carrying it, the venue it goes to. The middle one was
             * an arrow for a while, which left the one participant doing the
             * work as the only one without a face — and the direction it stood
             * for is already carried by the tail and said in the heading.
             */
            const VIEW_ICONS = {
              client: '👤',
              // With the variation selector: without it this one falls back
              // to a text glyph, and sits smaller and greyer beside two
              // marks that are emoji.
              destination: '⚙️',
              venue: '🏦',
            };

            /** Whose mark stands beside a bubble; the column is the party. */
            function icon(name) {
              return VIEW_ICONS[name] ?? '';
            }

            function label(name, way) {
              const set = way.includes('inbound') ? VIEW_NAMES.inbound : VIEW_NAMES.outbound;
              return set[name] ?? name;
            }

            /** Values come from a counterparty; never let one close a tag. */
            function escape(value) {
              return String(value ?? '')
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;');
            }

            /**
             * Session state, pushed rather than polled.
             *
             * <p>A session dropping is the moment the delay matters, and a
             * poll shows it up to an interval late. The stream sends the
             * current state on connect, so this is also how the row is first
             * drawn.
             *
             * <p>Every session, because this page watches a whole deployment;
             * the endpoint takes a `sessions` parameter for a subscriber that
             * cares about one.
             */
            function watchSessions() {
              const seen = new Map();

              const draw = () => {
                document.getElementById('sessions').innerHTML =
                  [...seen.values()].map(s => `
                    <span class="session">
                      <span class="dot ${s.loggedOn ? 'up' : ''}"></span>
                      ${s.sessionId}
                      <span style="opacity:.6">${s.nextSenderSeqNum}/${s.nextTargetSeqNum}</span>
                    </span>`).join('');
              };

              const stream = new EventSource('api/sessions/stream');

              stream.addEventListener('snapshot', e => {
                const data = JSON.parse(e.data);
                seen.clear();
                for (const s of data.sessions) seen.set(s.sessionId, s);
                draw();
              });

              stream.addEventListener('session', e => {
                const s = JSON.parse(e.data);
                // An event carries the state it left the session in, so the
                // row is redrawn from it without asking for anything.
                seen.set(s.sessionId, s);
                draw();
              });

              // EventSource reconnects on its own; the reconnect brings a
              // fresh snapshot, so nothing has to be reconciled here.
              stream.onerror = () => {
                for (const s of seen.values()) s.loggedOn = false;
                draw();
              };
            }

            watchSessions();
            setInterval(refresh, 2000);
            </script>
            </body>
            </html>
            """;
}
