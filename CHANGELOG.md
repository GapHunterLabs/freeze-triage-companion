<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Freeze Triage Companion Changelog

## [Unreleased]

## [0.1.0]

### Added

- **Freeze Triage** tool window, also under Help | Diagnostic Tools: every UI freeze the IDE recorded, newest first,
  with start time, duration, build, blocking call and where it happened.
- Classification of the blocking call from the top of the frozen stack (file I/O, external process, network,
  `runBlocking`, waiting for another thread, class loading, index, VFS, resolve, service initialization and more),
  checked against 393 real stacks from public freeze tickets.
- Lock waits: the thread holding the read/write lock and what it was doing.
- The user-installed plugin in the blocking stack, with version and vendor, without internal API.
- Copy Report (Markdown, ready for an issue), Search YouTrack for the same method, Open Thread Dump, Show Folder.
- Freezes from other installed versions of the same IDE, with a toggle.

[Unreleased]: https://github.com/GapHunterLabs/freeze-triage-companion/compare/0.1.0...HEAD
[0.1.0]: https://github.com/GapHunterLabs/freeze-triage-companion/commits/0.1.0
