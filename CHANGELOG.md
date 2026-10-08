# Changelog

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
