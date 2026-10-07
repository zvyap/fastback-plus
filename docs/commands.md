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
storage by default: `Backup complete. Used 5 seconds (228 MB / 5 GB).`
Disable this completion notice independently of the starting
notice with `/backup set broadcast-done-enabled false`. To customize it:

```text
/backup set broadcast-done-message Backup complete. Used {elapsed} ({snapshot_size} / {total_size}).
```

Supported placeholders are `{elapsed}`, `{snapshot}`, `{snapshot_size}`, `{total_size}`,
`{creator}`, and `{remark}`. Elapsed time is the backup duration, including any
remote push, expressed in words; it excludes the subsequent size lookup.
Snapshot size is the logical size of the committed
files, including the original size of Git LFS files. Total size is the physical
storage used by the local Git repository, including its shared history and LFS
objects. These sizes differ because backups share unchanged data. Unavailable
sizes display `-`; a size lookup failure does not fail a completed backup.
