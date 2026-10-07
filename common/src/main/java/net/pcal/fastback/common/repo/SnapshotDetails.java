package net.pcal.fastback.common.repo;

/** A local snapshot and its creation metadata, which is null for legacy snapshots. */
public record SnapshotDetails(SnapshotId id, SnapshotMetadata metadata) {
}
