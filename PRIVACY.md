# Privacy Policy — Freeze Triage Companion

**Effective date:** 2026-10-07

Freeze Triage Companion is a Gap Hunter Labs plugin for IntelliJ Platform IDEs. This policy is short because the
plugin's design makes it short: there is nothing to disclose beyond what's below.

## What this plugin collects

**Nothing.** Freeze Triage Companion does not collect, transmit, or sell any data — no source code, no thread dumps,
no file paths, no usage analytics, no telemetry, no crash reports, no personally identifiable information.

## What it reads on your machine

- The IDE's log folder, and the log folders of other installed versions of the same IDE (this can be turned off from
  the tool window toolbar): only the `threadDumps-freeze-*` folders and the thread dumps inside them.
- The jars in your plugins folder: only the plugin name, id, version and vendor from each `plugin.xml`, and the
  package names of the classes. Classes are never loaded.

What it reads is kept in memory while the tool window is open, and is never written anywhere else.

## What it keeps on your machine

- Whether freezes from other IDE versions are listed (one setting).
- To decide when to show its one-time rating prompt: whether you have answered the prompt, and a list of up to 500
  freezes you copied a report for or searched. Each entry is a one-way fingerprint that cannot be turned back into a
  folder name.

These values live in the IDE's own settings and are never sent anywhere.

## Network access

**None from the plugin.** The **Search YouTrack** button opens `youtrack.jetbrains.com` in your browser, only when you
click it, with the name of one method (for example `CapturingProcessHandler.runProcess`) as the search query. That
request is made by your browser, under JetBrains' privacy policy.

**Copy Report** puts a report on your clipboard; where you paste it is up to you. The report contains stack frames, the
IDE build and the name of the thread holding the lock, and no file paths or project names.

## Third parties

None. Freeze Triage Companion has no third-party SDKs, no analytics libraries, no ad networks, no dependencies that
phone home.

## Changes to this policy

If this ever changes, this file will be updated and the change will be noted in the plugin's `CHANGELOG.md`.

## Contact

Questions about this policy: **gaphunterlabs@gmail.com**
