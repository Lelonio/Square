# Working on Square

## Commits

Every commit is authored by Lelonio <21023829+Lelonio@users.noreply.github.com>
and by nobody else. No `Co-Authored-By` trailer, no assistant credit, no
generated-with footer — in commit messages, pull requests or releases. This does
not change.

## The vendored code

`native/vendor/librespot-core` carries local patches; `app/src/main/java/.../ui`
carries components copied from AndroidLiquidGlass with their notice. Never
rename or rewrite inside third-party trees — a project-wide substitution once
turned `librespot_playback` into `libresquare_playback`.

## Icons

Phosphor only. Apple's SF Symbols are licensed for Apple platforms and cannot be
used here.

## Secrets

`square-release.jks` and `keystore.properties` stay out of git. Passwords,
keystore passwords and tokens are typed by the user, never handled here.
