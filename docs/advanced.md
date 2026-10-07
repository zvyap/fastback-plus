---
layout: default
title: Advanced Usage
nav_order: 90
---

# Advanced Usage

This page assumes you already know how to use git.  If you don't, you should skip it.

## Disabling file updates

FastBack automatically performs updates to some key git files in your world repo.  Advanced users
can disable these updates by changing the repo's git configuration:

| Config Key                              | Use                                                                                           |
|-----------------------------------------|-----------------------------------------------------------------------------------------------|
| `fastback.update-gitignore-enabled`     | Defaults to `true`.  Set to `false` to disable automatic updates to the root `.gitignore`     |
| `fastback.update-gitattributes-enabled` | Defaults to `true`.  Set to `false` to disable automatic updates to the root `.gitattributes` |

For example, running
```
git config fastback.update-gitignore-enabled false
```
in your world folder will disable `.gitignore` updates.  

Be aware that you might miss out on future optimizations or bug fixes in these files; by disabling the 
updates, you take full responsibility for maintaining them.  As an alternative, you might want to consider 
adding custom `.gitignore` or `.gitattributes` files in subdirectories and letting FastBack continue to
auto-update the root files.


### World UUID

FastBack tries to stop you from mixing remote backup snapshots from different worlds.  This is generally a bad idea both in terms of staying organized and backup performance.

If you want to live dangerously, you can view or change the UUID of a world by looking at a file in you world save directory: `fastback/world.uuid`.


## Manually Restoring a Remote Snapshot

FastBack backups are just regular git repos.  This means you can use the terminal and the `git` command line tool to interact with them.

To restore from a remote manually using `git`:

1. Install `git`.  Mac and Linux users should already have it; Windows users may need to go [here](https://git-scm.com/downloads).

2. Clone the backup repo and list snapshots

```
git clone [repo-url]
cd [directory that just got created]
git branch
```

This will list all of your available snapshot branches:

```
snapshots/12345678-1234-5678-1234-567812345678/2022-10-02_12_56_33
snapshots/12345678-1234-5678-1234-567812345678/2022-10-07_11_49_31
```

To retrieve one of them, type:

```
git checkout snapshots/12345678-1234-5678-1234-567812345678/2022-10-02_12_56_33
```

Your world save files will appear in the directory.  You can then copy them into your minecraft installation.


## Notes for Dedicated Servers:

By default, Fastback will broadcast a message when a backup is about to start, so players know that things 
might get choppy for a bit.

You can configure this in `[worlddir]/.git/config`:

```
[fastback]
	broadcast-notice-enabled = true
	broadcast-notice-message = My custom message.
```

### Loading a snapshot into the server

Run `/backup load <snapshot>` (or `/backup remote-load <snapshot>`) to view the snapshot's name,
date, world ID, and source. Click **[Confirm load]**, or run the displayed command ending in
`confirm`, to begin loading.

Players receive a countdown before the server stops. Its default is 10 seconds; change it with
`/backup set load-countdown-seconds <seconds>`, where `seconds` must be at least 0. FastBack may
prepare the snapshot during the countdown. If preparation takes longer, the server waits until
it finishes. Use `/backup cancel` during the countdown or preparation to cancel the load and
remove prepared world files. The same command cancels an ongoing backup.

After the countdown and preparation finish, FastBack stops the dedicated server cleanly, backs
up its saved world, and replaces the world with the chosen snapshot. It then starts the server
again automatically. Players can reconnect once startup finishes. The previous world is also
preserved in a sibling recovery folder; its path is recorded in the server log. Cancellation is
no longer available after shutdown begins.

Automatic restart relaunches the original Java command with its JVM options, arguments, working
directory, and environment after the old process exits. A service manager or hosting panel that
kills the entire process tree when that process exits must provide its own restart support;
FastBack's replacement process cannot survive that policy.

## Debugging

If things go haywire, you can run

```
/backup set force-debug-enabled true
```

to temporarily send debugging output to the minecraft logs.  This can be useful in tracking down problems.

