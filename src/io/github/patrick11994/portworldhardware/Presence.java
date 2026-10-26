// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

/**
 * Turns readings of the vendor's presence node into an occupancy state. The node is not an Android sensor: a read
 * returns "1\n\0", with a NUL that trimming whitespace leaves, and on this panel 0 means someone is there. Nobody is
 * reported only after the node has read empty for the clear delay, so a person standing still does not flicker it.
 */
final class Presence {
    private Boolean state;  // null: not read yet, or unreadable
    private long lastSeen;

    /** Someone there (true), nobody (false), or null when the bytes are neither 0 nor 1. */
    static Boolean parse(byte[] raw, int length, boolean zeroMeansPresent) {
        int start = 0, end = Math.max(0, Math.min(length, raw.length));
        while (end > start && (raw[end - 1] == 0 || raw[end - 1] <= ' ')) end--;
        while (start < end && raw[start] <= ' ') start++;
        if (end - start != 1 || (raw[start] != '0' && raw[start] != '1')) return null;
        return (raw[start] == '0') == zeroMeansPresent;
    }

    /** The reported state: true, false, or null when unknown. */
    Boolean state() {
        return state;
    }

    /** Takes a reading (null when the node could not be read). Returns true when the reported state changed. */
    boolean update(Boolean seen, long nowMillis, long clearDelayMillis) {
        Boolean next;
        if (seen == null) {
            next = null;
        } else if (seen) {
            lastSeen = nowMillis;
            next = true;
        } else if (Boolean.TRUE.equals(state) && nowMillis - lastSeen < clearDelayMillis) {
            next = true;
        } else {
            next = false;
        }
        if (next == null ? state == null : next.equals(state)) return false;
        state = next;
        return true;
    }
}
