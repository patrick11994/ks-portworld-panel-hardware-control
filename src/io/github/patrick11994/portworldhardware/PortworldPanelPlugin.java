// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * The Portworld wall panel's own hardware as Kiosk Satellite entities: the RGB LED as a select of colours and
 * effects, and the presence sensor as an occupancy binary sensor with optional gesture triggers. Everything that
 * touches the hardware runs on the plugin's own threads; a generation number makes work from an earlier start or
 * pin change go quiet when it finishes late.
 */
public final class PortworldPanelPlugin implements KioskPlugin {
    static final String LED = "led", PRESENCE = "presence";
    private static final String LED_NAME = "LED", PRESENCE_NAME = "Presence";
    private static final String[] OPTIONS = LedEffects.options();

    /** The settings, read once per change. */
    static final class Config {
        final int red, green, blue, blinks;
        final String presencePath;
        final boolean zeroMeansPresent, triggerDetected, triggerCleared;
        final long pollMillis, clearDelayMillis;

        Config(Map<String, Object> settings) {
            red = pin(settings.get("redPin"), 71);
            green = pin(settings.get("greenPin"), 70);
            blue = pin(settings.get("bluePin"), 87);
            blinks = (int) number(settings.get("blinkCount"), 5, 1, 20);
            Object path = settings.get("presencePath");
            presencePath = path instanceof String && !((String) path).trim().isEmpty() ? ((String) path).trim() : "/sys/ex_ctl/human";
            zeroMeansPresent = !Boolean.FALSE.equals(settings.get("presenceInverted"));
            triggerDetected = !Boolean.FALSE.equals(settings.get("triggerDetected"));
            triggerCleared = Boolean.TRUE.equals(settings.get("triggerCleared"));
            pollMillis = Math.round(number(settings.get("pollSeconds"), 1, 0.5, 10) * 1000);
            clearDelayMillis = Math.round(number(settings.get("clearDelaySeconds"), 0, 0, 600) * 1000);
        }

        boolean samePins(Config other) {
            return other != null && red == other.red && green == other.green && blue == other.blue;
        }

        private static int pin(Object value, int fallback) {
            return (int) number(value, fallback, 0, 1023);
        }

        private static double number(Object value, double fallback, double min, double max) {
            if (!(value instanceof Number)) return fallback;
            double number = ((Number) value).doubleValue();
            return Double.isNaN(number) || number < min || number > max ? fallback : number;
        }
    }

    private final AtomicInteger generation = new AtomicInteger();
    private volatile PluginHost host;
    private volatile Config config;
    private volatile LedEffects effects;  // null until the pins are ready
    private SysfsLed led;  // guarded by this
    private volatile Thread presenceThread, setupThread;

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        config = new Config(settings);
        int run = generation.incrementAndGet();
        publishLed(null);
        startLed(run, config);
        startPresence(run, config, false);
    }

    @Override public void configure(Map<String, Object> settings) {
        Config previous = config, next = new Config(settings);
        config = next;
        LedEffects current = effects;
        if (current != null) current.setBlinks(next.blinks);
        if (!next.samePins(previous)) {
            // Other pins: put the old ones out and prepare the new ones. A setup still running for the old pins
            // sees that the config changed and closes what it opened.
            closeLed();
            publishLed(null);
            startLed(generation.get(), next);
        }
        if (!next.presencePath.equals(previous.presencePath)) startPresence(generation.get(), next, true);
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        if (command.equals("ledOff")) choose(Colour.OFF.option);
        else if (command.equals("doubleBlink")) choose("double blink");
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if (event.equals("select." + LED)) {
            Object option = payload == null ? null : payload.get("option");
            if (option instanceof String) choose((String) option);
        }
    }

    @Override public void stop() {
        // The host is already revoked here: only hardware cleanup, no host calls.
        generation.incrementAndGet();
        Thread presence = presenceThread, setup = setupThread;
        if (presence != null) presence.interrupt();
        if (setup != null) setup.interrupt();  // ends a waiting su, which destroys its process
        closeLed();
        host = null;
    }

    private void choose(String option) {
        LedEffects current = effects;
        if (current == null) {
            status("The LED is not ready yet.", true);
            publishLed(null);
            return;
        }
        try {
            current.select(option);
        } catch (IllegalArgumentException e) {
            status(e.getMessage(), true);
            publishLed(current.current());
        }
    }

    /** Prepares the pins on a worker: su can take a moment, and callbacks must finish within three seconds. */
    private void startLed(int run, Config pins) {
        Thread thread = new Thread(() -> {
            SysfsLed opened;
            try {
                opened = SysfsLed.open(pins.red, pins.green, pins.blue);
            } catch (IOException e) {
                if (generation.get() == run && pins == config) status("LED: " + e.getMessage(), true);
                return;
            } catch (InterruptedException e) {
                return;
            }
            synchronized (this) {
                if (generation.get() != run || pins != config) {
                    opened.close();
                    return;
                }
                led = opened;
                LedEffects ready = new LedEffects(opened, Timing.REAL, new LedEffects.Listener() {
                    @Override public void shown(String option) {
                        if (generation.get() == run) publishLed(option);
                    }

                    @Override public void failed(String message) {
                        if (generation.get() == run) status(message, true);
                    }
                }, new Random());
                ready.setBlinks(pins.blinks);
                effects = ready;
                ready.select(Colour.OFF.option);
            }
            status("Ready.", false);
        }, "portworld-led-setup");
        setupThread = thread;
        thread.start();
    }

    private synchronized void closeLed() {
        LedEffects current = effects;
        effects = null;
        if (current != null) current.stop();
        if (led != null) led.close();
        led = null;
    }

    /**
     * Watches the presence node on a thread of its own, replacing the one before. The sensor is an extra the vendor
     * fits on order: without its node, there is no presence entity at all.
     */
    private synchronized void startPresence(int run, Config watched, boolean restart) {
        Thread previous = presenceThread;
        presenceThread = null;
        if (previous != null) previous.interrupt();
        if (!new File(watched.presencePath).exists()) {
            log("No presence sensor at " + watched.presencePath + "; this panel may not have one.");
            if (restart) removePresence();
            return;
        }
        publishPresence(null);
        Thread thread = new Thread(() -> watchPresence(run), "portworld-presence");
        presenceThread = thread;
        thread.start();
    }

    private void watchPresence(int run) {
        Presence presence = new Presence();
        byte[] buffer = new byte[16];
        while (generation.get() == run && presenceThread == Thread.currentThread()) {
            Config now = config;
            Boolean seen;
            try (FileInputStream in = new FileInputStream(now.presencePath)) {
                int n = in.read(buffer);
                seen = Presence.parse(buffer, Math.max(n, 0), now.zeroMeansPresent);
            } catch (IOException e) {
                seen = null;
            }
            Boolean before = presence.state();
            if (presence.update(seen, System.currentTimeMillis(), now.clearDelayMillis) && generation.get() == run
                    && presenceThread == Thread.currentThread()) {
                Boolean after = presence.state();
                publishPresence(after);
                if (after == null) status("Cannot read the presence sensor at " + now.presencePath + ".", true);
                else if (before != null && after && now.triggerDetected) fire("presenceDetected");
                else if (before != null && !after && now.triggerCleared) fire("presenceCleared");
            }
            try {
                Thread.sleep(now.pollMillis);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void publishLed(String option) {
        PluginHost current = host;
        if (current == null) return;
        try {
            current.publishSelect(LED, LED_NAME, OPTIONS, option);
        } catch (RuntimeException e) {
            log("Could not publish the LED: " + e.getMessage());
        }
    }

    private void publishPresence(Boolean present) {
        PluginHost current = host;
        if (current == null) return;
        try {
            current.publishBinarySensor(PRESENCE, PRESENCE_NAME, "occupancy", present);
        } catch (RuntimeException e) {
            log("Could not publish presence: " + e.getMessage());
        }
    }

    private void removePresence() {
        PluginHost current = host;
        if (current == null) return;
        try {
            current.removeBinarySensor(PRESENCE);
        } catch (RuntimeException e) {
            log("Could not remove presence: " + e.getMessage());
        }
    }

    private void fire(String trigger) {
        PluginHost current = host;
        if (current == null) return;
        try {
            current.fireTrigger(trigger);
        } catch (RuntimeException e) {
            log("Could not fire " + trigger + ": " + e.getMessage());
        }
    }

    private void status(String message, boolean error) {
        PluginHost current = host;
        if (current == null) return;
        try {
            current.status(message, error);
            if (error) current.log(message);
        } catch (RuntimeException ignored) {
            // Revoked: the session is ending.
        }
    }

    private void log(String message) {
        PluginHost current = host;
        if (current == null) return;
        try {
            current.log(message);
        } catch (RuntimeException ignored) {
            // Revoked: the session is ending.
        }
    }
}
