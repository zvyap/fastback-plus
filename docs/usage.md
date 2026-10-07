---
layout: default
title: Using FastBack
nav_order: 10
---

# Using FastBack

FastBack adds a custom `/backup` command that is used for all backup operations.  To get detailed help about
using it, just type.

**IMPORTANT:** Fastback requires that you have [native git and git-lfs](native-git.md) installed.

```
/backup help
```

This page explains how to do most common tasks.

## Enabling Backups on a world

To enable backups on your world, just run

```
/backup init
```

you can then type

```
/backup local
```

to do backup right away.  You can then run other commands to set up automatic backups, remote backups
pruning policies and more.  Read on.


## Listing available backup snapshots

Every time FastBack runs, it creates a *snapshot* of your world.  Snapshots are identified by the time 
they were created.

To see all snapshots in your backup of the current world, run
```
/backup list

Available snapshots:
2022-10-07_10-11-12
2022-10-02_23-43-01
2022-09-24_11-32-59
2022-05-08_08-56-33
```


## Restoring a backup snapshot

You can restore your world from any snapshot in the backup by running

```
/backup restore 2022-10-02_10-11-12
Restoring 2022-10-02_10-11-12 to
/home/pcal/minecraft/saves/MyWorld-2022-10-02_10-11-12
```

This will create a copy of your world as it was when that snapshot was made.  

Note that files in the current world are never touched by `restore`; the restored snapshot is placed in either your `saves` directory (in singleplayer mode) or under your system temp directory (in server mode).  In either case, the full path to restore location will be output when the command completes.

To look at the restored snapshot, quit the current world and open the restored snapshot world.  (In server mode, you'll have to manually copy
the restored files from the location displayed at the end of the command).

## Loading a snapshot on a dedicated server

Operators can replace the server's active world with a local backup using:

```
/backup load 2022-10-02_10-11-12 confirm
```

For a remote backup, use `/backup remote-load <snapshot> confirm`. Both commands
offer snapshot name completion. Omitting `confirm` displays the shutdown warning
without changing the world.

FastBack first downloads or copies the snapshot into a temporary sibling of the
world folder and checks it. A preparation failure leaves the server running and
the active world in place. Once the snapshot is ready, FastBack announces the
shutdown, stops the server normally, waits for backup tasks and the configured
shutdown action, and closes the world files before replacing the world folder.
**Restart the server after its process has finished** to play the selected snapshot;
FastBack does not restart the server process automatically.

The outgoing world is preserved beside the active world in a folder named
`<world-folder>-fastback-before-load-<unique-id>`. The server log prints the exact
path before shutdown and after loading. The original Git repository, snapshot
history, remote settings, and backup configuration remain attached to the active
world. Files added after the selected snapshot are kept only in the outgoing copy.
These recovery copies are not pruned automatically; remove one only after verifying
the restored world and no longer needing the outgoing data.

Loading needs enough free space beside the world for the extracted snapshot. It
uses that location for safe directory moves, independently of `restore-directory`.
If installation fails, FastBack attempts to put the original world back and logs
the active, staged, and outgoing paths. Check those paths before restarting.
If the process stops during installation or rollback fails, a sibling file named
`<world-folder>-fastback-load-pending.txt` records those paths. FastBack refuses
to start that world while the file exists, so an interrupted swap cannot silently
start a fresh world. Recover the intended world and its `.git`, then remove the
marker and restart. A successful load or rollback removes the marker automatically.
These checks protect against interrupted processes; they do not guarantee recovery
from power loss or storage failure.
To return to the outgoing world manually, stop the server, preserve the currently
active folder, move its `.git` into the outgoing folder if that folder has no
`.git`, and put the outgoing folder back at the configured world path before
restarting.

