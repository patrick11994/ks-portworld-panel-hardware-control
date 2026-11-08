// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What the LED shows: a colour or an effect, chosen by its select option. Effects run on their own thread, one at a
 * time; choosing anything else interrupts the running one first. Effects that use "the colour" blink the last colour
 * chosen, white until one is. breathe and color fade dim the channels with software PWM, only while they run.
 * Notifications, double blink and timed effects are temporary: afterwards the LED goes back to what it showed before.
 */
final class LedEffects {
    /** Hears what the LED settled on and what went wrong. Called from the caller's or the effect's thread. */
    interface Listener {
        void shown(String option);

        void failed(String message);
    }

    private interface Effect {
        void run(Show show) throws IOException, InterruptedException;
    }

    /** A brightness 0..1 per channel at a point t (0..1) of a fade. */
    private interface Fade {
        double[] at(double t);
    }

    private static final long PWM_PERIOD_NANOS = 5_000_000L;  // 200 Hz, smooth on this panel in the spike
    private static final double PWM_MIN = 0.02, PWM_MAX = 0.98;

    private static final Map<String, Effect> LOOPS = new LinkedHashMap<>();
    private static final List<String> ONE_SHOTS =
            Arrays.asList("flash once", "flash and off", "double blink", "notify ok", "notify attention", "notify alert");
    private static final Map<String, Colour> NOTIFY = new LinkedHashMap<>();

    /** Ends a timed effect when its time is up. */
    private static final class TimeUp extends RuntimeException {
        TimeUp() {
            super(null, null, false, false);
        }
    }

    static {
        NOTIFY.put("notify ok", Colour.GREEN);
        NOTIFY.put("notify attention", Colour.YELLOW);
        NOTIFY.put("notify alert", Colour.RED);
        // The Termux script's effects, with its timings.
        LOOPS.put("flash", show -> show.blink(show.colour, 500, 500));
        LOOPS.put("strobe", show -> show.blink(show.colour, 100, 100));
        LOOPS.put("pulse", show -> show.blink(show.colour, 1500, 1500));
        LOOPS.put("color loop", show -> {
            for (Colour colour : Colour.LIT) show.hold(colour, 1000);
        });
        LOOPS.put("random", show -> show.hold(show.randomColour(), 1000));
        LOOPS.put("random flash", show -> show.blink(show.randomColour(), 300, 300));
        LOOPS.put("alarm", show -> show.blink(Colour.RED, 200, 200));
        LOOPS.put("police", show -> {
            show.blink(Colour.RED, 300, 150);
            show.blink(Colour.BLUE, 300, 150);
        });
        // Added ones.
        LOOPS.put("heartbeat", show -> {
            show.blink(show.colour, 120, 120);
            show.blink(show.colour, 120, 840);
        });
        LOOPS.put("sos", show -> {
            for (int i = 0; i < 3; i++) show.blink(show.colour, 200, i < 2 ? 200 : 600);
            for (int i = 0; i < 3; i++) show.blink(show.colour, 600, i < 2 ? 200 : 600);
            for (int i = 0; i < 3; i++) show.blink(show.colour, 200, i < 2 ? 200 : 1400);
        });
        LOOPS.put("warning", show -> show.blink(Colour.YELLOW, 1000, 1000));
        LOOPS.put("ambulance", show -> {
            show.hold(Colour.BLUE, 400);
            show.hold(Colour.WHITE, 400);
        });
        LOOPS.put("fire", show -> show.hold(show.random.nextInt(3) == 0 ? Colour.YELLOW : Colour.RED, 40 + show.random.nextInt(160)));
        LOOPS.put("traffic light", show -> {
            show.hold(Colour.GREEN, 3000);
            show.hold(Colour.YELLOW, 1000);
            show.hold(Colour.RED, 3000);
        });
        LOOPS.put("breathe", show -> {
            Colour colour = show.colour;
            show.fade(4000, t -> {
                double level = (1 - Math.cos(2 * Math.PI * t)) / 2;
                level *= level;  // eyes see brightness roughly squared
                return new double[] {colour.red ? level : 0, colour.green ? level : 0, colour.blue ? level : 0};
            });
        });
        LOOPS.put("color fade", show -> {
            Colour[] wheel = {Colour.RED, Colour.YELLOW, Colour.GREEN, Colour.CYAN, Colour.BLUE, Colour.MAGENTA, Colour.RED};
            for (int i = 0; i + 1 < wheel.length; i++) {
                Colour from = wheel[i], to = wheel[i + 1];
                show.fade(2000, t -> new double[] {mix(from.red, to.red, t), mix(from.green, to.green, t), mix(from.blue, to.blue, t)});
            }
        });
    }

    /** All select options: off and the colours, then the effects. At most KS's limit of 32. */
    static String[] options() {
        List<String> options = new ArrayList<>();
        for (Colour colour : Colour.values()) options.add(colour.option);
        List<String> effects = new ArrayList<>(LOOPS.keySet());
        effects.addAll(effects.indexOf("police") + 1, ONE_SHOTS);
        options.addAll(effects);
        return options.toArray(new String[0]);
    }

    private final Led led;
    private final Timing timing;
    private final Listener listener;
    private final Random random;
    private final Object state = new Object();

    private Thread runner;  // guarded by this
    private Thread active;  // the runner allowed to report; guarded by state
    private String current = Colour.OFF.option;  // guarded by state
    private String underlying = Colour.OFF.option;  // what a temporary effect goes back to; guarded by state
    private String lastLit;  // the last colour or lasting effect shown, for toggle; guarded by state
    private Colour colour = Colour.WHITE;  // guarded by state
    private volatile int blinks = 5;

    LedEffects(Led led, Timing timing, Listener listener, Random random) {
        this.led = led;
        this.timing = timing;
        this.listener = listener;
        this.random = random;
    }

    /** How often flash once and flash and off blink. */
    void setBlinks(int blinks) {
        this.blinks = blinks;
    }

    /** What the LED shows now: a colour, off or the running effect. */
    String current() {
        synchronized (state) {
            return current;
        }
    }

    /** Shows a colour or starts an effect. Returns quickly; throws IllegalArgumentException for an unknown option. */
    synchronized void select(String option) {
        start(option, 0);
    }

    /** Runs a lasting effect for a while, then goes back to what the LED showed before. */
    synchronized void selectFor(String option, long millis) {
        if (!LOOPS.containsKey(option) || millis <= 0) throw new IllegalArgumentException("Not a timed effect: " + option);
        start(option, millis);
    }

    /** Turns the LED off, or back on with the last colour or effect it showed. */
    synchronized void toggle() {
        String on;
        synchronized (state) {
            on = Colour.OFF.option.equals(current) ? (lastLit != null ? lastLit : colour.option) : Colour.OFF.option;
        }
        start(on, 0);
    }

    /** Shows the colour after the last one chosen. */
    synchronized void nextColour() {
        int index;
        synchronized (state) {
            index = Arrays.asList(Colour.LIT).indexOf(colour);
        }
        start(Colour.LIT[(index + 1) % Colour.LIT.length].option, 0);
    }

    /** Starts the lasting effect after the one running, or the first one. */
    synchronized void nextEffect() {
        List<String> effects = new ArrayList<>(LOOPS.keySet());
        String now;
        synchronized (state) {
            now = current;
        }
        start(effects.get((effects.indexOf(now) + 1) % effects.size()), 0);
    }

    private void start(String option, long millis) {
        Colour chosen = Colour.named(option);
        if (chosen == null && !LOOPS.containsKey(option) && !ONE_SHOTS.contains(option)) {
            throw new IllegalArgumentException("Unknown LED option: " + option);
        }
        stopRunner();
        boolean temporary = millis > 0 || ONE_SHOTS.contains(option);
        String previous;
        Colour base;
        synchronized (state) {
            previous = underlying;
            if (chosen != null && chosen != Colour.OFF) colour = chosen;
            base = colour;
            current = option;
            if (!temporary) {
                underlying = option;
                if (chosen != Colour.OFF) lastLit = option;
            }
        }
        if (chosen != null) {
            try {
                led.show(chosen.red, chosen.green, chosen.blue);
            } catch (IOException e) {
                listener.failed("Could not set the LED: " + e.getMessage());
            }
            listener.shown(option);
            return;
        }
        listener.shown(option);
        Show show = new Show(base, millis);
        Thread thread = new Thread(() -> runEffect(option, previous, show), "portworld-led-" + option.replace(' ', '-'));
        synchronized (state) {
            active = thread;
        }
        runner = thread;
        thread.start();
    }

    /** Stops the effect and turns the LED off. The LED stays open; the caller closes it. */
    synchronized void stop() {
        stopRunner();
        try {
            led.show(false, false, false);
        } catch (IOException ignored) {
            // Stopping: nobody to tell.
        }
    }

    /** Waits for a one-shot effect to finish, for tests. */
    void awaitRunner(long millis) throws InterruptedException {
        Thread thread;
        synchronized (this) {
            thread = runner;
        }
        if (thread != null) thread.join(millis);
    }

    private void stopRunner() {
        synchronized (state) {
            active = null;
        }
        Thread thread = runner;
        runner = null;
        if (thread == null) return;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runEffect(String option, String resume, Show show) {
        try {
            Effect loop = LOOPS.get(option);
            String settled;
            if (loop != null) {
                try {
                    while (true) loop.run(show);
                } catch (TimeUp up) {
                    settled = resume;
                }
            } else if (option.equals("double blink")) {
                for (int i = 0; i < 2; i++) show.blink(show.colour, 150, 150);
                settled = resume;
            } else if (NOTIFY.containsKey(option)) {
                for (int i = 0; i < 3; i++) show.blink(NOTIFY.get(option), 200, 200);
                settled = resume;
            } else {
                for (int i = 0; i < blinks; i++) show.blink(show.colour, 300, 300);
                settled = option.equals("flash once") ? show.colour.option : Colour.OFF.option;
            }
            // Without a time limit from here: what comes back lasts.
            Show lasting = new Show(show.colour, 0);
            Colour still = Colour.named(settled);
            if (still != null) lasting.hold(still, 0);
            if (!settle(settled)) return;
            Effect next = LOOPS.get(settled);
            if (next != null) {
                while (true) next.run(lasting);
            }
        } catch (InterruptedException e) {
            // Replaced by another choice, or stopped.
        } catch (IOException | RuntimeException e) {
            listener.failed("The LED effect " + option + " stopped: " + e.getMessage());
        }
    }

    /** A temporary effect is done: report what the LED shows now, unless something else was chosen meanwhile. */
    private boolean settle(String option) {
        synchronized (state) {
            if (active != Thread.currentThread()) return false;
            current = option;
            underlying = option;
            if (!Colour.OFF.option.equals(option)) lastLit = option;
        }
        listener.shown(option);
        return true;
    }

    private static double mix(boolean from, boolean to, double t) {
        double level = (from ? 1 : 0) + ((to ? 1 : 0) - (from ? 1 : 0)) * t;
        return level * level;
    }

    /** The building blocks effects are made of, for one run. */
    private final class Show {
        final Colour colour;
        final Random random = LedEffects.this.random;
        private final long deadline;  // System.nanoTime() when a timed effect ends, or 0
        private Colour last;

        Show(Colour colour, long millis) {
            this.colour = colour;
            deadline = millis > 0 ? timing.nanoTime() + millis * 1_000_000L : 0;
        }

        void hold(Colour shown, long millis) throws IOException, InterruptedException {
            if (Thread.interrupted()) throw new InterruptedException();
            checkTime();
            led.show(shown.red, shown.green, shown.blue);
            if (millis <= 0) return;
            if (deadline != 0 && timing.nanoTime() + millis * 1_000_000L > deadline) {
                timing.waitUntil(deadline);
                throw new TimeUp();
            }
            timing.sleep(millis);
        }

        private void checkTime() {
            if (deadline != 0 && timing.nanoTime() >= deadline) throw new TimeUp();
        }

        void blink(Colour shown, long onMillis, long offMillis) throws IOException, InterruptedException {
            hold(shown, onMillis);
            hold(Colour.OFF, offMillis);
        }

        /** A lit colour other than the one shown last. */
        Colour randomColour() {
            Colour next;
            do {
                next = Colour.LIT[random.nextInt(Colour.LIT.length)];
            } while (next == last);
            last = next;
            return next;
        }

        /** Dims the channels along a fade for a while, switching each one on and off every PWM period. */
        void fade(long millis, Fade fade) throws IOException, InterruptedException {
            long start = timing.nanoTime(), length = millis * 1_000_000L;
            for (long period = start; period - start < length; period += PWM_PERIOD_NANOS) {
                checkTime();
                double[] level = fade.at((double) (period - start) / length);
                boolean[] on = new boolean[3];
                long[] offAt = new long[3];
                for (int i = 0; i < 3; i++) {
                    on[i] = level[i] >= PWM_MIN;
                    offAt[i] = level[i] >= PWM_MAX ? Long.MAX_VALUE : period + (long) (level[i] * PWM_PERIOD_NANOS);
                }
                led.show(on[0], on[1], on[2]);
                // Switch the channels off one by one, the shortest first.
                while (on[0] || on[1] || on[2]) {
                    int next = -1;
                    for (int i = 0; i < 3; i++) if (on[i] && offAt[i] != Long.MAX_VALUE && (next < 0 || offAt[i] < offAt[next])) next = i;
                    if (next < 0) break;
                    timing.waitUntil(offAt[next]);
                    on[next] = false;
                    led.show(on[0], on[1], on[2]);
                }
                timing.waitUntil(period + PWM_PERIOD_NANOS);
            }
        }
    }
}
