# Changelog

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
