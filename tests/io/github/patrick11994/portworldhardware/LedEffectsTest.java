// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

public final class LedEffectsTest {
    /** Virtual time: waits return at once and move the clock. Past the budget, the effect is "stopped". */
    private static final class FakeTiming implements Timing {
        long now;
        final long budget;

        FakeTiming(long budgetMillis) {
            budget = budgetMillis * 1_000_000L;
        }

        @Override public long nanoTime() {
            return now;
        }

        @Override public void sleep(long millis) throws InterruptedException {
            waitUntil(now + millis * 1_000_000L);
        }

        @Override public void waitUntil(long deadlineNanos) throws InterruptedException {
            if (Thread.interrupted() || deadlineNanos > budget) throw new InterruptedException();
            now = Math.max(now, deadlineNanos);
        }
    }

    /** Records each change as "colour@millis" on the fake clock. */
    private static final class FakeLed implements Led {
        final FakeTiming timing;
        final List<String> changes = new ArrayList<>();
        final List<long[]> raw = new ArrayList<>();  // {nanos, red, green, blue}
        boolean closed;

        FakeLed(FakeTiming timing) {
            this.timing = timing;
        }

        @Override public synchronized void show(boolean red, boolean green, boolean blue) {
            String name = "?";
            for (Colour colour : Colour.values()) if (colour.red == red && colour.green == green && colour.blue == blue) name = colour.option;
            long at = timing == null ? 0 : timing.now;
            changes.add(name + "@" + at / 1_000_000L);
            raw.add(new long[] {at, red ? 1 : 0, green ? 1 : 0, blue ? 1 : 0});
        }

        @Override public void close() {
            closed = true;
        }

        synchronized String first(int n) {
            return String.join(" ", changes.subList(0, Math.min(n, changes.size())));
        }

        synchronized String last() {
            return changes.isEmpty() ? "" : changes.get(changes.size() - 1);
        }
    }

    private static final class Heard implements LedEffects.Listener {
        final List<String> shown = new ArrayList<>(), failed = new ArrayList<>();

        @Override public synchronized void shown(String option) {
            shown.add(option);
        }

        @Override public synchronized void failed(String message) {
            failed.add(message);
        }

        synchronized String all() {
            return String.join(", ", shown);
        }
    }

    public static void main(String[] args) throws Exception {
        options();
        colours();
        loops();
        oneShots();
        breathe();
        replacing();
        System.out.println("LedEffectsTest passed");
    }

    private static void options() {
        String[] options = LedEffects.options();
        check(options.length, 27);
        check(options.length <= 32, true);  // KS's select limit
        check(new HashSet<>(Arrays.asList(options)).size(), options.length);
        check(options[0], "off");
        check(String.join(",", Arrays.copyOfRange(options, 8, 18)),
                "flash,strobe,pulse,color loop,random,random flash,alarm,police,flash once,flash and off");
        for (String option : options) check(!option.isEmpty() && option.length() <= 80, true);
    }

    private static void colours() {
        FakeLed led = new FakeLed(null);
        Heard heard = new Heard();
        LedEffects effects = new LedEffects(led, new FakeTiming(0), heard, new Random(1));
        effects.select("cyan");
        check(led.last(), "cyan@0");
        check(heard.all(), "cyan");
        check(effects.current(), "cyan");
        effects.select("off");
        check(led.last(), "off@0");
        check(effects.current(), "off");
        try {
            effects.select("purple");
            throw new AssertionError("unknown option accepted");
        } catch (IllegalArgumentException expected) {
            check(effects.current(), "off");
        }
        effects.stop();
        check(led.last(), "off@0");
    }

    private static void loops() throws InterruptedException {
        check(run("flash", "cyan", 2500).first(6), "cyan@0 off@500 cyan@1000 off@1500 cyan@2000 off@2500");
        check(run("police", null, 2000).first(5), "red@0 off@300 blue@450 off@750 red@900");
        check(run("heartbeat", "green", 2400).first(6), "green@0 off@120 green@240 off@360 green@1200 off@1320");
        check(run("traffic light", null, 8000).first(4), "green@0 yellow@3000 red@4000 green@7000");
        // SOS: three short, three long, three short, then a word gap.
        FakeLed sos = run("sos", null, 7000);
        check(sos.first(13), "white@0 off@200 white@400 off@600 white@800 off@1000 white@1600 off@2200 white@2400 off@3000 white@3200 off@3800 white@4400");
        // Random colours never repeat back to back.
        FakeLed random = run("random", null, 50_000);
        for (int i = 1; i < random.changes.size(); i++) {
            String before = random.changes.get(i - 1).split("@")[0], after = random.changes.get(i).split("@")[0];
            check(!before.equals(after), true);
        }
    }

    private static void oneShots() throws InterruptedException {
        FakeTiming timing = new FakeTiming(60_000);
        FakeLed led = new FakeLed(timing);
        Heard heard = new Heard();
        LedEffects effects = new LedEffects(led, timing, heard, new Random(1));
        effects.setBlinks(2);
        effects.select("red");
        effects.select("flash once");
        effects.awaitRunner(2000);
        check(led.first(6), "red@0 red@0 off@300 red@600 off@900 red@1200");
        check(heard.all(), "red, flash once, red");
        check(effects.current(), "red");

        effects.select("flash and off");
        effects.awaitRunner(2000);
        check(led.last().split("@")[0], "off");
        check(effects.current(), "off");

        // Double blink goes back to what was shown before: a colour...
        effects.select("green");
        effects.select("double blink");
        effects.awaitRunner(2000);
        check(led.last().split("@")[0], "green");
        check(effects.current(), "green");

        check(heard.failed.isEmpty(), true);

        // ...or a running effect, which carries on (in real time: the effect never ends by itself).
        Heard resumed = new Heard();
        LedEffects real = new LedEffects(new FakeLed(null), Timing.REAL, resumed, new Random(1));
        real.select("alarm");
        real.select("double blink");
        Thread.sleep(900);
        check(real.current(), "alarm");
        check(resumed.all(), "alarm, double blink, alarm");
        real.stop();
    }

    /** breathe dims with PWM: on average 3/8 lit over a cycle, and fully dark at its start. */
    private static void breathe() throws InterruptedException {
        FakeLed led = run("breathe", "blue", 4000);
        long on = 0, darkStart = 0;
        List<long[]> raw = led.raw;
        for (int i = 0; i + 1 < raw.size(); i++) {
            long length = raw.get(i + 1)[0] - raw.get(i)[0];
            if (raw.get(i)[3] == 1) on += length;
            if (raw.get(i)[1] == 1 || raw.get(i)[2] == 1) throw new AssertionError("breathe lit another channel");
            if (raw.get(i)[0] < 200_000_000L && raw.get(i)[3] == 1) darkStart += length;
        }
        double average = on / 4e9;
        check(Math.abs(average - 0.375) < 0.02, true);
        check(darkStart < 1_000_000L, true);
    }

    /** With real time: a new choice stops the running effect before it shows. */
    private static void replacing() throws InterruptedException {
        FakeLed led = new FakeLed(null);
        Heard heard = new Heard();
        LedEffects effects = new LedEffects(led, Timing.REAL, heard, new Random(1));
        effects.select("strobe");
        Thread.sleep(350);
        effects.select("blue");
        int count = led.changes.size();
        Thread.sleep(250);
        check(led.changes.size(), count);  // the strobe is gone
        check(led.last(), "blue@0");
        effects.select("color fade");
        Thread.sleep(100);
        effects.stop();
        check(led.last(), "off@0");
        check(heard.all(), "strobe, blue, color fade");
    }

    private static FakeLed run(String option, String colour, long millis) throws InterruptedException {
        FakeTiming timing = new FakeTiming(millis);
        FakeLed led = new FakeLed(timing);
        LedEffects effects = new LedEffects(led, timing, new Heard(), new Random(7));
        if (colour != null) {
            effects.select(colour);
        }
        effects.select(option);
        effects.awaitRunner(5000);
        if (colour != null) led.changes.remove(0);
        return led;
    }

    static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but was " + actual);
    }
}
