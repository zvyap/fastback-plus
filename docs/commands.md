---
layout: default
title: Commands
nav_order: 20
---

# Commands

*Note: A number of subcommands changed in version 0.15.0.  You can see the docs for version 0.14 [here](https://github.com/pcal43/fastback/blob/727a28247a4d2e6c9040293ab99e71a3c183b504/docs/commands-list.md).*

Type `/backup` followed by one of these subcommands:

{% include_relative commands-list.md %}

`/backup local [remark]` and `/backup full [remark]` accept an optional remark
after the command. The entire remaining text is saved as the remark, including
spaces. Remarks support multiple languages and emoji, with a maximum length of
64 Unicode code points, including spaces. No quotation marks are needed:

```text
/backup local Before updating the mods
/backup full 更新前のバックアップ 🚀
```

Use `/backup view <backup-id>` to see a local snapshot's ID, date and time in the server's local time zone,
who created it, and its remark. The creator is the player, console, or automatic
backup; older snapshots without this metadata display an unknown creator.
Snapshots without a remark display `-`. Backup ID autocomplete tooltips show
colored date, creator, and remark fields on one line. Dates use
`yyyy-MM-dd HH:mm:ss` followed by the server's time zone. Suggestions show up to
100 matching backup IDs, newest first; enter a longer prefix to find an older ID.
Remote suggestions use metadata from snapshots also stored locally; remote-only
snapshots show an unknown creator and remark.

`/backup list [page]` lists local snapshots, newest first, with up to eight entries
per page. The page defaults to 1. The title shows the total number of snapshots,
and the footer shows the current page and maximum page. Click `<<<` or `>>>` in
chat to go to the previous or next page, or enter a page directly:

```text
/backup list 2
```

The column header labels the backup ID, creator (player, console, or automatic backup),
and remark. Hover over an entry to see its full ID, server-local date and time, creator,
and remark; click it to copy the backup ID to your clipboard. Pages outside the
available range are rejected. `/backup remote-list [page]` uses the same controls;
remote-only snapshots display an unknown creator and remark.

Snapshot commands share a central cache. The list loads metadata for the shown
page, then warms the next page asynchronously. The first page and neighboring
pages stay cached for previous/next navigation. Backup creation, deletion,
configuration changes, and server lifecycle events invalidate the cache; the
snapshot index also refreshes after 30 seconds.

Successful backups broadcast their elapsed time, snapshot size, and total backup
storage by default: `Backup completed. Used 5 seconds (228.00 MB / 5.00 GB) [+140 MB].`
Disable this completion notice independently of the starting
notice with `/backup set broadcast-done-enabled false`. To customize it:

```text
/backup set broadcast-done-message Backup completed. Used {elapsed} ({current_size:2} / {total_size:2}) [+{added_size}].
```

Supported placeholders are `{elapsed}`, `{snapshot}`, `{current_size}`, `{snapshot_size}`, `{total_size}`, `{added_size}`,
`{creator}`, and `{remark}`. Elapsed time is the backup duration, including any
remote push, expressed in words; it excludes the subsequent size lookup.
Snapshot size is the logical size of the committed
files, including the original size of Git LFS files. Total size is the physical
storage used by the local Git repository, including its shared history and LFS
objects. These sizes differ because backups share unchanged data. Unavailable
sizes display `-`; a size lookup failure does not fail a completed backup.

`current_size` and `snapshot_size` are aliases for the saved snapshot's logical
size. `added_size` is the increase in locally stored Git LFS object bytes during
the current snapshot commit, excluding ordinary Git objects and temporary files.
Content already in the LFS store is shared and adds zero bytes. A backup without
new LFS objects displays `+0 bytes`. A failed lookup displays `[-]` in the default
notice. Custom `lfs.storage` locations are respected.

Size placeholders accept a decimal precision from 0 to 9, for example
`{current_size:2}`, `{total_size:2}`, or `{added_size:1}`. Units are selected
automatically using 1024 bytes per KB, MB, GB, and so on. The default notice uses
two decimals for current and total size and whole units for added size. Plain
placeholders and `:0` retain the existing whole-unit formatting; bytes remain
whole numbers. Unknown placeholders or invalid precision remain literal text.

When a player starts `/backup local` or `/backup full`, the starting notice is
`<player> is starting a manual backup.` Console and automatic backups keep the
existing starting notice, including any configured `broadcast-message`.
`broadcast-enabled` controls starting notices for all backup sources.

Both `broadcast-message` (the starting notice) and `broadcast-done-message`
support color and style tokens. Use Minecraft color names such as `{red}`,
`{green}`, `{aqua}`, or `{gold}`, or a six-digit hexadecimal color such as
`{#FFAA00}`. Styles are `{bold}`, `{italic}`, `{underlined}`, `{strikethrough}`,
and `{obfuscated}`. Tokens apply to the following text; `{reset}` restores the
message's default style. Unknown tokens remain literal text. For example:

```text
/backup set broadcast-message {gold}{bold}Backup starting{reset}...
/backup set broadcast-done-message {green}Backup completed. {reset}Used {aqua}{elapsed}{reset} ({#FFAA00}{current_size:2}{reset} / {gold}{total_size:2}{reset}) [+{added_size}].
```

The completion placeholders above can be combined with formatting tokens;
placeholder values such as remarks remain literal text.
