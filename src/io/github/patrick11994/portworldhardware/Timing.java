// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

/** Time for the effects, as a seam for tests. Both waits end with InterruptedException when the effect is stopped. */
interface Timing {
    long nanoTime();

    void sleep(long millis) throws InterruptedException;

    /** Waits until a System.nanoTime() deadline precisely enough for software PWM: sleeps, then spins the last bit. */
    void waitUntil(long deadlineNanos) throws InterruptedException;

    Timing REAL = new Timing() {
        @Override public long nanoTime() {
            return System.nanoTime();
        }

        @Override public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }

        @Override public void waitUntil(long deadlineNanos) throws InterruptedException {
            long left;
            while ((left = deadlineNanos - System.nanoTime()) > 2_000_000L) Thread.sleep((left - 1_000_000L) / 1_000_000L);
            while (System.nanoTime() < deadlineNanos) {
                if (Thread.interrupted()) throw new InterruptedException();
            }
        }
    };
}
