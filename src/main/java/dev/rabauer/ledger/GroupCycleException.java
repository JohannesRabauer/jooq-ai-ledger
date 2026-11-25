package dev.rabauer.ledger;

public class GroupCycleException extends RuntimeException {

    public GroupCycleException(long groupId, long newParentId) {
        super("Moving group " + groupId + " under " + newParentId + " would create a cycle");
    }
}
