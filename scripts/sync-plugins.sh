#!/usr/bin/env bash
#
# Install this repository's browser plugins into the harness workspace.
#
# The harness is a submodule: a plugin committed inside it would be a local
# commit in somebody else's tree, carried forward by hand through every upgrade
# or lost to the next checkout. The source of truth is plugins/ here, and this
# script copies it in and registers it, so an upgrade is: check out the new tag,
# run this again.
#
# Registration is three lines in files the harness owns. Each is applied only
# when absent, so running this twice changes nothing the second time.
#
# Usage: scripts/sync-plugins.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
harness="$root/harness"

if [ ! -d "$harness/packages/client" ]; then
    echo "sync-plugins: $harness is not a harness checkout; is the submodule initialised?" >&2
    exit 1
fi

for source in "$root"/plugins/*/; do
    name="$(basename "$source")"
    target="$harness/packages/client/$name"

    # The copy is one way and replaces what is there: the harness copy is a
    # build input, never somewhere to edit.
    rm -rf "$target"
    mkdir -p "$target"
    (cd "$source" && tar --exclude node_modules --exclude lib -cf - .) | (cd "$target" && tar -xf -)
    echo "sync-plugins: installed $name"

    package="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['name'])" "$source/package.json")"

    # 1. tsconfig.client.json: the client typecheck aggregate has to see it.
    python3 - "$harness/tsconfig.client.json" "$name" <<'PY'
import json, re, sys
path, name = sys.argv[1], sys.argv[2]
raw = open(path, encoding='utf-8').read()
entry = f'{{ "path": "./packages/client/{name}" }}'
if f'./packages/client/{name}"' in raw:
    sys.exit(0)
marker = '{ "path": "./packages/client/ui-sidebar-files" },'
if marker not in raw:
    marker = re.search(r'\{ "path": "\./packages/client/[^"]+" \},', raw).group(0)
open(path, 'w', encoding='utf-8').write(raw.replace(marker, f'{marker}\n    {entry},', 1))
print(f'sync-plugins: registered {name} in tsconfig.client.json')
PY

    # 2. tsconfig.base.json: the workspace path mapping both faces resolve through.
    python3 - "$harness/tsconfig.base.json" "$name" "$package" <<'PY'
import re, sys
path, name, package = sys.argv[1], sys.argv[2], sys.argv[3]
raw = open(path, encoding='utf-8').read()
if f'"{package}"' in raw:
    sys.exit(0)
anchor = re.search(r'( *)"@deepseek-ai/dsh-client-ui-sidebar-files": \[[^\]]*\],\n( *)"@deepseek-ai/dsh-client-ui-sidebar-files/client": \[[^\]]*\],\n', raw)
if anchor is None:
    print('sync-plugins: no anchor in tsconfig.base.json; add the paths by hand', file=sys.stderr)
    sys.exit(1)
indent = anchor.group(1)
added = (f'{indent}"{package}": ["./packages/client/{name}/src"],\n'
         f'{indent}"{package}/client": ["./packages/client/{name}/src/client"],\n')
open(path, 'w', encoding='utf-8').write(raw[:anchor.end()] + added + raw[anchor.end():])
print(f'sync-plugins: registered {package} in tsconfig.base.json')
PY

    # 3. The web bundle's plugin roster, and its dependency on the package.
    python3 - "$harness/packages/bundle/web-app/cordis.patch.yml" "$name" "$package" <<'PY'
import re, sys
path, name, package = sys.argv[1], sys.argv[2], sys.argv[3]
raw = open(path, encoding='utf-8').read()
if package in raw:
    sys.exit(0)
anchor = re.search(r'( *)- id: ui-sidebar-files\n *name: \'[^\']*\'\n', raw)
if anchor is None:
    print('sync-plugins: no anchor in cordis.patch.yml; add the entry by hand', file=sys.stderr)
    sys.exit(1)
indent = anchor.group(1)
added = f"{indent}- id: {name}\n{indent}  name: '{package}'\n"
open(path, 'w', encoding='utf-8').write(raw[:anchor.end()] + added + raw[anchor.end():])
print(f'sync-plugins: registered {name} in cordis.patch.yml')
PY

    python3 - "$harness/packages/bundle/web-app/package.json" "$package" <<'PY'
import json, sys
path, package = sys.argv[1], sys.argv[2]
data = json.load(open(path, encoding='utf-8'))
deps = data.setdefault('dependencies', {})
if package in deps:
    sys.exit(0)
deps[package] = 'workspace:^'
data['dependencies'] = dict(sorted(deps.items()))
with open(path, 'w', encoding='utf-8') as out:
    json.dump(data, out, indent=2, ensure_ascii=False)
    out.write('\n')
print(f'sync-plugins: added {package} to the web bundle dependencies')
PY
done

echo
echo "sync-plugins: done. Next:"
echo "  cd harness && pnpm install && pnpm run build"
