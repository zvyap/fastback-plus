# Changelog

## 0.38.0 - 2026-10-17

For Minecraft 26.3, with Fabric and NeoForge builds. Requires Java 25.

### Added

- Configurable backup actions managed with `/backup action add <EVENT> <ACTION> [MAX]`,
  `/backup action list [page]`, and `/backup action remove <EVENT> <ACTION>`.
- Supported events: `SHUTDOWN` (world or dedicated server shutdown), `EMPTY`
  (dedicated server without connected players), `INTERVAL[duration]` (such as
  `INTERVAL[30m]`), and `SCHEDULED[HH:MM]` (daily at server-local time).
- Optional `MAX` execution limits for action rules per session.
- Two-step confirmation for removing action rules.
- Automatic migration of legacy shutdown, autoback, and empty-server scheduling
  settings to action rules.
- Dedicated permission nodes for action commands: `fastback.command.action`,
  `fastback.command.action.add`, `fastback.command.action.list`, and
  `fastback.command.action.remove`.

### Improved

- `/backup info` summarizes effective configured actions.

[Full changelog](https://github.com/zvyap/fastback-plus/compare/0.37.0%2B26.3.0...0.38.0)

## 0.37.0+26.3.0 - 2026-10-12

For Minecraft 26.3, with Fabric and NeoForge builds. Requires Java 25.

### Added

- Optional `{changed_size}` reports the signed change in total local backup
  storage during a backup, such as `+50MB` or `-120MB`. Decimal precision is
  supported with `{changed_size:2}`; unavailable measurements display `-`.
- Empty-server automatic-backup stop reasons are logged to the console when
  backups are disabled or their configured limit is reached. Repeated autosaves
  do not repeat the same stop message.
- Local and remote pruning log the number of deleted snapshots, including zero,
  and the retention policy used.

### Improved

- All size displays omit the space between the number and unit, such as
  `228.00MB` and `5.00GB`.
- `/backup remote-prune` reports an unset retention policy without throwing an
  error while trying to display a deleted count.

[Full changelog](https://github.com/zvyap/fastback-plus/compare/0.36.0%2B26.3.0...0.37.0%2B26.3.0)

## 0.36.0+26.3.0 - 2026-10-09

For Minecraft 26.3, with Fabric and NeoForge builds. Requires Java 25.

### Added

- Backup completion notices now include newly stored local Git LFS bytes:
  `Backup completed. Used 5 seconds (228.00 MB / 5.00 GB) [+140 MB].`
  Shared or reused LFS content adds zero bytes; unavailable statistics do not fail
  a completed backup.
- Size placeholders support decimal precision, such as `{current_size:2}` and
  `{total_size:2}`. `current_size` aliases `snapshot_size`; `{added_size}` reports
  new LFS storage. The default current and total sizes use two decimals, while
  added size uses whole units. Existing plain placeholders remain compatible.
- Player-triggered backups announce `<player> is starting a manual backup.`
  Console and automatic backups retain their existing starting notices.

### Improved

- Successful player backups refresh the automatic-backup wait from completion,
  including the empty-server wait, without consuming or resetting its backup
  limit. Failed or cancelled backups leave the scheduling wait unchanged.
- Completion size formatting preserves message colors and styles, and supports
  the existing translated notices and English fallback for older clients.

[Full changelog](https://github.com/zvyap/fastback-plus/compare/0.35.2%2B26.3.0...0.36.0%2B26.3.0)

## 0.35.2+26.3.0 - 2026-10-08

For Minecraft 26.3, with Fabric and NeoForge builds. Requires Java 25.

### Added

- Cancellable backups and snapshot loads with `/backup cancel`. Dedicated-server
  loads now include a configurable countdown and automatically restart the server
  after restoring the snapshot, preserving the previous world in a recovery folder.
- Optional Unicode remarks for `/backup local` and `/backup full`, plus
  `/backup view <backup-id>` to show a snapshot's date, creator, and remark.
- Clickable pagination for local and remote backup lists, with snapshot metadata
  on hover and backup IDs that can be copied from chat.
- Configurable backup-completion broadcasts with elapsed time, snapshot size,
  total backup storage, and snapshot metadata placeholders. Starting and
  completion messages support named colors, hexadecimal colors, and style tokens.
- Dedicated-server scheduling while no players are connected, with a separate
  action, minimum wait, and maximum successful backups per empty period.
  Unset settings preserve existing scheduling behavior.

### Improved

- Shared snapshot caching, lazy page metadata, and adjacent-page prefetch reduce
  repeated Git reads for lists and autocomplete.
- Backup details and suggestions use server-local dates and clearer formatting.
- Rejected remote Git pushes are reported as failures, and concurrent snapshot
  date parsing uses isolated formatters.

### Server restart note

Automatic restart reuses the original Java command and environment. Hosting
panels or service managers that terminate the entire process tree must provide
their own restart support. Loads can be cancelled before shutdown begins.

[Full changelog](https://github.com/zvyap/fastback-plus/compare/0.35.1%2B26.3.0...0.35.2%2B26.3.0)
