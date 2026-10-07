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

Use `/backup view <backup-id>` to see a local snapshot's ID, date and time in UTC,
who created it, and its remark. The creator is the player, console, or automatic
backup; older snapshots without this metadata display an unknown creator.
Snapshots without a remark display that no remark is available. Backup ID
autocomplete tooltips show the date and time (UTC), creator, and remark when you
select or hover over a suggestion.
Remote suggestions use metadata from snapshots also stored locally; remote-only
snapshots show an unknown creator and remark.
