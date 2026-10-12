---
layout: default
title: Scheduling
nav_order: 20
---

# Scheduling

Automatic actions are stored in the world's backup configuration and managed with:

```
/backup action add <EVENT> <ACTION> [MAX]
/backup action list [page]
/backup action remove <EVENT> <ACTION>
```

New worlds default to `SHUTDOWN full` and `INTERVAL[30m] local`. Existing worlds
migrate their shutdown, autoback, and empty-server settings automatically. Legacy
`none` becomes an absent rule. Old action/wait/max settings are no longer exposed
by `/backup set`; use the action commands instead.
Older worlds with no explicit automation settings retain local shutdown backups
and disabled interval backups. A failed migration write keeps valid legacy rules active.

Migration discards the legacy empty-server wait because `EMPTY` now runs once
at the next autosave. Its maximum now applies to the whole session, across empty
periods. A legacy zero-minute interval is preserved internally, although newly
added intervals require a positive duration.

{% include_relative actions-list.md %}

Events use uppercase names; actions use the lowercase names above. A single
event can have several different actions, but the same event/action pair cannot
be added twice, even with a different time or maximum. Remove the existing rule
before adding its replacement. Click an action-list row, or run the remove
command using the plain event name, to review and confirm removal. Confirmations
expire after five minutes and must be reviewed again if the rule changes.

## Events and timing

| Event | Behavior |
|-------|----------|
| `SHUTDOWN` | Runs when exiting a single-player world or stopping a dedicated server, after the world is saved. Does not accept `MAX`. |
| `EMPTY` | Dedicated servers only. Runs once per continuous empty period, on the next autosave after the last player leaves. A server starting without players begins an empty period at startup. |
| `INTERVAL[30m]` | Runs after at least the given interval, on the next Minecraft autosave. `INTERVAL` uses `30m`. |
| `SCHEDULED[00:00]` | Runs at the next occurrence of the given server-local time after starting or adding the rule, on the first autosave after that time. `SCHEDULED` uses `00:00`. |

Intervals accept a positive whole number followed by `s`, `m`, `h`, or `d`.
For example, `INTERVAL[30m]` and `INTERVAL[2h]` are valid. Scheduled times use
24-hour `HH:MM`, from `00:00` to `23:59`, in the server's timezone.

```
/backup action add SHUTDOWN full
/backup action add INTERVAL[2h] full 4
/backup action add SCHEDULED[03:00] push
/backup action add EMPTY local
```

The interval and scheduled timers start when the world starts or the rule is
added. A successful player-triggered `/backup local` or `/backup full` refreshes
the interval wait from completion, including when broadcasts are disabled.
Failed or cancelled manual backups do not postpone the next automatic backup.
An interval action that completes unsuccessfully retains the interval wait
before retrying; an error thrown by the task allows retry at the next autosave.
Scheduled actions run successfully once per scheduled occurrence; failed
attempts retry at the next autosave.

On dedicated servers, interval actions pause while no players are connected,
even when there are no `EMPTY` rules. Empty periods use `EMPTY` actions instead.
Scheduled actions retain their daily timing while empty. `EMPTY` is hidden from
single-player suggestions, including worlds opened to LAN; entering it manually
shows an orange warning and does not add an ineffective rule.

## Maximum runs

`MAX` limits successful runs of that rule during the current world/server
session. It must be a non-negative whole number; the default `0` means unlimited.
Failed or cancelled actions and manual backups do not consume it. Restarting the
world/server resets the count. `EMPTY` also runs at most once per empty period,
so a player must join and leave before it can run again, while its session count
continues to apply. `SHUTDOWN` has no maximum argument.

`/backup info` summarizes the configured actions. `/backup action list` uses the
same columns, rows, and page navigation as the backup list. Managing actions
requires `fastback.command.action` plus the corresponding
`fastback.command.action.add`, `.list`, or `.remove` permission.
