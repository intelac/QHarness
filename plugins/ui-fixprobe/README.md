# ui-fixprobe

A right-Sidebar tab for the DSH web UI: the FIX probes running on this machine,
each endpoint they have up, and where its sequence numbers stand.

```
端口 18099
  ● client   已登录   2130 条消息   出 1066 · 入 1066
  ○ market   未启动
```

## Why it reads the probes directly

A probe tests whichever FIX engine it was pointed at — this repository's NEXUM,
or somebody else's. Nothing it connects to can be asked which probes exist, so
the panel asks each probe: `/api/status` on the port it serves MCP on, which
sets `Access-Control-Allow-Origin` for exactly this.

That also keeps the harness out of it. There is no host-side namespace to keep
in step with an upgrade, and the panel is one browser package.

## Discovery

A probe registers itself under `~/.nexum/probes` as `<port>.json`, which any
process that can read a directory can list — a browser cannot. The panel sweeps
`18099` and the seven ports above it instead, then narrows: later sweeps re-ask
only the ports that answered, plus one unproven port per pass, so a panel left
open does not retry dead ports every two seconds.

A probe started later is still found, within a few sweeps.

## Installing it

The harness is a submodule, so this package lives here and is copied in:

```sh
scripts/sync-plugins.sh
cd harness && pnpm install && pnpm run build
```

Run it again after every harness upgrade — a checkout of a new tag takes the
copy with it. The script also adds the four registration lines the harness
needs (`tsconfig.base.json`, `tsconfig.client.json`, the web bundle's
`cordis.patch.yml` and its `package.json`), and skips each one already present.
