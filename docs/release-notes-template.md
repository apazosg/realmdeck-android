# Release-notes template

Set the GitHub title separately to `RealmDeck Android <version> · <Preview or Release>`.
Replace every placeholder below before publishing. The first body line is parsed by
the app's release checker, so it must exactly match the version code and prerelease
flag. See [Releasing RealmDeck Android](releasing.md) for consistency checks.

```markdown
C<CODE> prerelease — version code <CODE>

<Brief, user-friendly English summary.>

## Download

[Download the APK](<direct APK asset URL>)

SHA-256: `<APK SHA-256>` ([checksum manifest](<SHA256SUMS asset URL>))

## What's new

- <User-visible change, stated without technical overclaim.>

## Installation

Install the APK manually. <State whether this is an upgrade and any safe steps.>

## Before you try

- <Experimental status, required platform, and important test limitations.>
- <Source/build limitations, if applicable.>

## Details

- [Current status](<current status URL>)
- [Source archive](<source asset URL>)
- [Technical README](<README.txt asset URL>)
- [Third-party notices](<THIRD_PARTY_NOTICES asset URL>)
```

For stable releases, replace `prerelease` with `release` on the exact first line
and leave GitHub's prerelease flag off. Keep end-user release notes distinct from
technical audit attachments; publish only technical files that were audited for
that exact build. Never rewrite frozen assets merely to polish release prose.
