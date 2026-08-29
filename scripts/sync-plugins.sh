#!/usr/bin/env bash
# Copy our plugin sources into the harness workspace so they can be built.
#
# The plugins live in plugins/ because they are ours, not the vendor's. They
# are built by harness, whose build presets locate a package by globbing
# packages/*/*/package.json — a glob that does not follow symlinks, so the
# package has to be a real directory inside that tree. This copies it there.
#
# plugins/ is the source of truth; the copy under harness/ is build input and
# is ignored by git.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

for source in "$root"/plugins/*/; do
  name="$(basename "$source")"
  target="$root/harness/packages/client/$name"
  # Keep the installed dependencies: they belong to the workspace rather than
  # to the sources, and removing them means a reinstall before anything can be
  # built or tested.
  find "$target" -mindepth 1 -maxdepth 1 ! -name node_modules -exec rm -rf {} + 2>/dev/null || true
  mkdir -p "$target"
  # Everything but the build output and installed dependencies.
  (cd "$source" && tar --exclude=node_modules --exclude=lib -cf - .) | (cd "$target" && tar -xf -)
  echo "synced $name -> harness/packages/client/$name"
done

# The harness workspace also has to know the package exists: it registers in
# the aggregate tsconfig, knip, the Model Experience audit list, and the two
# generated catalogs. Those edits live in the vendor tree, so they are kept as
# a patch this repository owns and reapplied here.
patch_file="$root/patches/harness-register-nexum-plugin.patch"
if [ -f "$patch_file" ]; then
  if git -C "$root/harness" apply --check "$patch_file" 2>/dev/null; then
    git -C "$root/harness" apply "$patch_file"
    echo "applied $(basename "$patch_file")"
  else
    echo "note: $(basename "$patch_file") is already applied, or no longer applies to this harness revision"
  fi
fi
