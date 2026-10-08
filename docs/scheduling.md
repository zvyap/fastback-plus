---
layout: default
title: Scheduling
nav_order: 20
---

# Scheduling

You can schedule backups to run automatically when the world shuts down or auto-saves.

## Backing up on shutdown

Backups can be run whenever the world shuts down (i.e., when exiting a single-player world or
shutting down a dedicated server). To do this,

```
/backup set shutdown-action [action]
```

Where `[action]` is one of

{% include_relative actions-list.md %}

## Backing up while the game is running

You can also set backups to run while the game is playing, immediately after the
regular auto-saves that Minecraft performs every 5 minutes.  To do this,

```
/backup set autoback-action [action]
```

Where `[action]` is one of the actions listed above.

If you don't want `autoback` backups to run every 5 minutes, you can schedule them to
run less-frequently:

```
/backup set autoback-wait [minutes]
```

This sets the minimum wait time between auto-backups.

A successful player-triggered `/backup local` or `/backup full` refreshes this
wait from its completion time, even if backup broadcasts are disabled. Failed
or cancelled attempts do not postpone the next automatic backup. If the server
became empty while the manual backup was running, its empty-server wait is also
refreshed; the manual backup does not consume the empty-server backup limit.

So, for example, setting `[minutes]` 
to 120 will cause backups to run *roughly* every two hours; the exact timing will depend 
on when the next autosave runs.

## Backing up an empty dedicated server

Dedicated servers can use a separate automatic-backup policy when no players are
connected. These settings are hidden in single-player worlds, including worlds
opened to LAN.

To stop scheduled backups while the server is empty:

```
/backup set backup-server-empty-action none
```

Alternatively, choose `local`, `full`, or `full-gc` and set the wait and limit:

```
/backup set backup-server-empty-action local
/backup set backup-server-empty-wait 30
/backup set backup-server-empty-max 2
```

This waits at least 30 minutes after the server becomes empty, then runs up to
two successful scheduled backups, at least 30 minutes apart. As with ordinary
automatic backups, the actual timing depends on the next Minecraft autosave.
The count resets when a player joins or the server restarts. A server that starts
with no players begins a new empty period at startup.

`backup-server-empty-wait` and `backup-server-empty-max` accept non-negative
integers. A wait of `0` allows a backup at the next autosave. A maximum of `0`
means unlimited backups while empty.

If the empty-server action or wait has never been set, it inherits
`autoback-action` or `autoback-wait`, respectively. With all three empty-server
settings unset, existing scheduling behavior is preserved. When a player joins,
ordinary automatic-backup settings apply again. Manual and shutdown backups
continue to use their own commands and settings; they do not consume the
empty-server limit. `/backup info` shows the effective empty-server settings
on dedicated servers.
