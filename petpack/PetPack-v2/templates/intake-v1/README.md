# SweetPet PetPack intake v1

This directory is a pre-scaffold planning template. It deliberately has no
`pack.json`, character frame, preview, or checksum file and therefore is not a
PetPack source directory.

Copy these planning files to `work/intake/<intake-id>/` while sources, rights,
identity, visual rules, actions, and content scope are still being decided.
Keep original source media below `sources/raw/`; that directory ignores every
file except its README and ignore rule, and its contents must never be packaged.

The lifecycle is:

1. `collecting`: register sources and unresolved requirements.
2. `reviewing`: review rights, source coverage, and the proposed action/content plans.
3. `identity-locked`: freeze identity, outfit, proportions, palette, camera, canvas,
   facing, and the visible-foot baseline.
4. `scaffold-ready`: approve stable package identity and satisfy every scaffold gate
   in `acceptance-checklist.md`.
5. Run `petpack.py new` once into an empty `packs/<pack-id>` directory.

Do not copy a released character such as `jk-beach-summer` as the starting point.
Released packs are useful examples of extensions and cross-references, but carry
character-, theme-, action-, and checksum-specific decisions.

Only after `scaffold-ready`:

```powershell
python tools/petpack.py new packs/<pack-id> `
  --id <pack-id> `
  --name "<display-name>" `
  --version 0.1.0
```

The ID must match `[a-z0-9][a-z0-9_-]{0,63}`, the name must contain 1-80
characters, and the destination must be absent or empty. `new` creates a valid
placeholder pack; it is not a character generator and is not an intake command.
