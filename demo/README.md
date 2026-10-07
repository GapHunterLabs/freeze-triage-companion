# Demo freezes

Three synthetic freeze folders, in the layout the IDE writes to its log folder:

| Folder | What the tool window shows |
|---|---|
| `threadDumps-freeze-20260921-...-9sec` | File I/O on the EDT in `ConfigLoader.load` |
| `threadDumps-freeze-20260925-...-14sec` | The EDT waits for the write lock; a pooled thread holds a read lock while it waits for an external process |
| `threadDumps-freeze-20260930-...-6sec` | EDT idle when sampled (a modal dialog's event loop) |

To see them in a development IDE:

1. Run `./gradlew runIde` once, then close the IDE.
2. Copy the three folders from `demo/log/` into the sandbox log folder,
   `build/idea-sandbox/IU-2025.2.6.2/log/`.
3. Run `./gradlew runIde` again and open **Help | Diagnostic Tools | Freeze Triage**.
