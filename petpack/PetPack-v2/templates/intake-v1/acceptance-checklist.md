# PetPack intake acceptance checklist

- Intake ID: `{{INTAKE_ID}}`
- Status: `collecting`
- Scaffold ready: **NO**
- Reviewed by: `TBD`
- Reviewed at: `TBD`

Every completed item should name an owner and an evidence path. Any exception must
record its reason and approver.

## 1. Intake and source gate

- [ ] `brief.json`, source manifest, action manifest, and content plan use the same intake ID and revision.
- [ ] Every source has a stable source ID, relative locator, SHA-256, dimensions, and review status.
- [ ] Source coverage includes a readable face, complete body, visible feet, motion references, and an avatar crop source.
- [ ] Rejected and duplicate sources have a recorded reason and are not assigned to actions.

## 2. Rights and privacy gate

- [ ] The source owner or authorized provider is recorded.
- [ ] Consent for derivative character assets is documented.
- [ ] Public redistribution and release rights are documented.
- [ ] Attribution and expiry obligations are known.
- [ ] Raw sources are excluded from Git and from the eventual `.petpack`.

## 3. Identity and visual lock gate

- [ ] One candidate group is selected; identity status is `locked`.
- [ ] Outfit, palette, proportions, camera, canvas, and default facing are frozen.
- [ ] Head scale, body scale, body center, and visible-foot baseline are frozen.
- [ ] A visual revision identifies the exact approved reference set.
- [ ] `groundAnchor` values are measured from visible feet, not copied blindly from the canvas edge.

## 4. Action and game gate

- [ ] `idle`, `walk`, `run`, `wave`, and `photo_pose` each have approved source assignments and frame plans.
- [ ] Both theme actions have stable IDs, purposes, frame plans, and runtime uses.
- [ ] Walk includes contact, down, passing, up, and opposite-contact phases.
- [ ] Run includes contact, compression, drive, flight, and opposite phases.
- [ ] No action plans to fake missing frames with whole-frame alpha crossfades.
- [ ] Canvas padding, baseline tolerance, scale tolerance, center-jump limit, and loop closure are accepted.
- [ ] Game modes have matching action references and an approved avatar crop plan.

## 5. Content and protocol gate

- [ ] Voice, form of address, content rating, and prohibited expressions are approved.
- [ ] Dialogue and task conditions use only fields exposed by the protocol.
- [ ] Dialogue placeholders are limited to `city/date/hour/temperature/weather/weekday`.
- [ ] Weather-specific claims are conditionally routed.
- [ ] Every task has at most four compact options and references planned actions/modes.
- [ ] Planned settings have real consumers and accurate descriptions.
- [ ] Dialogue, tasks, settings, actions, and game modes form a closed reference matrix.

## 6. Scaffold-ready gate

- [ ] `packageIdentity.packId` is approved and matches `[a-z0-9][a-z0-9_-]{0,63}`.
- [ ] `packageIdentity.displayName` is approved and contains 1-80 characters.
- [ ] Initial version is the stable version `0.1.0`.
- [ ] The target `packs/<pack-id>` directory is absent or empty.
- [ ] The intake revision is frozen and mapped to the approved pack ID.
- [ ] No released character directory will be copied as the starting point.

Only after every scaffold-ready item is checked may `python tools/petpack.py new`
run. The generated placeholder is then replaced according to these manifests;
`checksums.json` is never edited by hand.

## 7. Later production and release evidence

- [ ] Placeholder frames and preview are fully replaced.
- [ ] `qa --strict` passes and its contact sheet is visually reviewed.
- [ ] `release --strict` produces byte-identical double builds and a recorded SHA-256.
- [ ] `publish` passes Android preflight, initial install, duplicate install, and cold load.
- [ ] Device visual/interaction smoke checks pass for representative actions and game modes.
