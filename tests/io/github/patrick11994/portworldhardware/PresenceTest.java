// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

import java.nio.charset.StandardCharsets;

public final class PresenceTest {
    public static void main(String[] args) {
        // What the node really returns: a digit, a newline and a NUL. 0 is someone there on this panel.
        check(parse("1\n\0", true), false);
        check(parse("0\n\0", true), true);
        check(parse("0\n\0", false), false);
        check(parse("1", false), true);
        check(parse(" 0 \n", true), true);
        check(parse("", true), null);
        check(parse("\0", true), null);
        check(parse("2\n", true), null);
        check(parse("10\n", true), null);
        check(Presence.parse(new byte[] {'0', '\n', 0}, 99, true), true);  // a length past the buffer is clipped

        Presence presence = new Presence();
        check(presence.state(), null);
        check(presence.update(false, 0, 0), true);
        check(presence.state(), false);
        check(presence.update(false, 1000, 0), false);  // no change, nothing to publish
        check(presence.update(true, 2000, 0), true);
        check(presence.update(false, 3000, 0), true);
        check(presence.state(), false);

        // With a clear delay, presence holds until the node has read empty that long.
        presence.update(true, 10_000, 5000);
        check(presence.update(false, 11_000, 5000), false);
        check(presence.update(true, 12_000, 5000), false);  // seen again: the delay starts over
        check(presence.update(false, 16_000, 5000), false);
        check(presence.state(), true);
        check(presence.update(false, 17_000, 5000), true);
        check(presence.state(), false);

        // An unreadable node makes the state unknown, and a reading brings it back.
        check(presence.update(null, 18_000, 0), true);
        check(presence.state(), null);
        check(presence.update(null, 19_000, 0), false);
        check(presence.update(true, 20_000, 0), true);
        System.out.println("PresenceTest passed");
    }

    private static Boolean parse(String raw, boolean zeroMeansPresent) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        return Presence.parse(bytes, bytes.length, zeroMeansPresent);
    }

    private static void check(Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
