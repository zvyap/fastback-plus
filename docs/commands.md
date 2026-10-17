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
storage by default: `Backup completed. Used 5 seconds (228.00MB / 5.00GB) [+140MB].`
Disable this completion notice independently of the starting
notice with `/backup set broadcast-done-enabled false`. To customize it:

```text
/backup set broadcast-done-message Backup completed. Used {elapsed} ({current_size:2} / {total_size:2}) [+{added_size}].
```

Supported placeholders are `{elapsed}`, `{snapshot}`, `{current_size}`, `{snapshot_size}`, `{total_size}`, `{added_size}`, `{changed_size}`,
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
new LFS objects displays `+0bytes`. A failed lookup displays `[-]` in the default
notice. Custom `lfs.storage` locations are respected.

`changed_size` is the signed change in total local Git repository storage from
just before the backup to its successful completion, including any remote push.
It includes ordinary Git files and objects as well as LFS objects stored inside
the repository. Growth displays `+50MB`, shrinkage displays `-120MB`, and no
change displays `+0bytes`. Unavailable measurements display `-`. Pruning or
garbage collection performed after the completion notice is excluded. To show it:

```text
/backup set broadcast-done-message Backup completed. Used {elapsed} ({current_size:2} / {total_size:2}) [+{added_size}] [{changed_size}].
```

Size placeholders accept a decimal precision from 0 to 9, for example
`{current_size:2}`, `{total_size:2}`, `{added_size:1}`, or `{changed_size:2}`. Units are selected
automatically using 1024 bytes per KB, MB, GB, and so on, with no space before
the unit in any size display. The default notice uses
two decimals for current and total size and whole units for added size. Plain
placeholders and `:0` retain whole-unit precision; bytes remain
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

## Partial rollback (dedicated servers)

```text
/backup rollback <snapshot> region <dimension-id> <block-x> <block-y> <block-z>
/backup rollback <snapshot> chunk <dimension-id> <block-x> <block-y> <block-z>
/backup rollback <snapshot> dimension <dimension-id>
/backup rollback <snapshot> player <username>
```

Select a local snapshot, then a scope. Dimension IDs and online/cached offline
usernames have tab completion. XYZ input selects the region or chunk containing
that block position. It accepts absolute coordinates and Minecraft's relative
coordinates, such as `~ ~ ~`. Y is accepted as part of the position; rollback
always affects the full vertical column. Negative coordinates use floor division.
For example, `region minecraft:overworld -1 64 0` selects region file
`r.-1.0.mca`: chunks X=-32..-1, Z=0..31 and blocks X=-512..-1, Z=0..511.
`chunk minecraft:overworld -1 64 0` selects just chunk (-1, 0), blocks
X=-16..-1, Z=0..15. The earlier two-number X/Z syntax remains available and
interprets its numbers as region or chunk indexes, respectively.

The preview shows snapshot metadata, the dimension and region, and inclusive
chunk and block ranges. Dimension rollback confirms that the whole dimension
is affected. Player rollback shows the username and UUID; the confirmation
button pins that UUID even if the player disconnects or the command source moves.
Offline identity lookup reads the server's existing in-memory username cache,
case-insensitively; it makes no network requests and never guesses a UUID for
unknown names. Online identities take precedence over cached identities.
Player rollback restores inventory,
position and other player NBT, advancements and statistics for that UUID only.
The UUID in the button is an internal confirmation parameter.

Click **Confirm rollback** to start preparation and the configured
`load-countdown-seconds` countdown. `/backup cancel` works until shutdown begins.
The server stops cleanly, makes a mandatory safety snapshot, builds the new
world from the final saved world, applies only the selected snapshot data, and
uses the normal world swap and automatic restart. The previous world remains
in the recovery directory logged by the server. This requires room for both an
extracted snapshot and a copy of the current world, in addition to backup storage.
Legacy migrations can also require temporary space for Minecraft's conversion.

Region and chunk rollback include terrain, entities and points of interest,
including oversized external chunk files. Chunk rollback preserves neighboring
chunks in the same region. Missing entity/POI data and optional player files
are removed within the selection to match the snapshot. A missing terrain
region, terrain chunk, dimension or required player data fails before shutdown.
Before selecting files, partial rollback uses the running Minecraft version's
native file migrations on the disposable snapshot extraction. This handles
legacy Overworld/`DIM-1`/`DIM1`, custom dimensions, player folders and namespaced
dimension data, as well as the newer `dimensions/...` and `players/...` layout.
The live world and original backup are untouched during migration. Snapshot
data versions newer than the running game, or missing version metadata, are
rejected before shutdown; downgrades are not attempted.

Dimension rollback replaces that dimension's entire directory, including its
saved dimension data. Other dimensions, players and shared world data such as
scoreboards remain current. Partial rollback does not reconcile entities or
items moved across the selection boundary since the snapshot, or external mod
databases. Automatic restart has the same hosting-panel limitations as `load`.
