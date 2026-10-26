// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.jxl.kiosk.plugins.PluginHost;

public final class PortworldPanelPluginTest {
    /** A fake Kiosk Satellite that records what the plugin publishes. */
    private static final class FakeHost implements PluginHost {
        final List<String> events = new ArrayList<>();

        @Override public synchronized void publishSelect(String key, String name, String[] options, String state) {
            events.add("select." + key + "=" + state);
        }

        @Override public synchronized void publishBinarySensor(String key, String name, String deviceClass, Boolean state) {
            events.add("binary_sensor." + key + "(" + deviceClass + ")=" + state);
        }

        @Override public synchronized void removeBinarySensor(String key) {
            events.add("removed binary_sensor." + key);
        }

        @Override public synchronized void fireTrigger(String id) {
            events.add("trigger " + id);
        }

        @Override public synchronized void status(String message, boolean error) {
            if (error) events.add("error " + message);
        }

        @Override public void showWindow(String title, String message, String buttonLabel) {}

        @Override public void hideWindow() {}

        @Override public void log(String message) {}

        synchronized boolean has(String event) {
            return events.contains(event);
        }

        synchronized boolean hasPrefix(String prefix) {
            for (String event : events) if (event.startsWith(prefix)) return true;
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        config();
        session();
        noSensor();
        System.out.println("PortworldPanelPluginTest passed");
    }

    private static void config() {
        PortworldPanelPlugin.Config defaults = new PortworldPanelPlugin.Config(Collections.emptyMap());
        check(defaults.red + "/" + defaults.green + "/" + defaults.blue, "71/70/87");
        check(defaults.presencePath, "/sys/ex_ctl/human");
        check(defaults.zeroMeansPresent, true);
        check(defaults.triggerDetected, true);
        check(defaults.triggerCleared, false);
        check(defaults.pollMillis, 1000L);
        check(defaults.blinks, 5);

        Map<String, Object> odd = new HashMap<>();
        odd.put("redPin", -1.0);  // out of range: the default
        odd.put("greenPin", 12.0);
        odd.put("pollSeconds", 0.5);
        odd.put("clearDelaySeconds", 30L);
        odd.put("presencePath", "  ");
        odd.put("presenceInverted", false);
        PortworldPanelPlugin.Config config = new PortworldPanelPlugin.Config(odd);
        check(config.red, 71);
        check(config.green, 12);
        check(config.pollMillis, 500L);
        check(config.clearDelayMillis, 30_000L);
        check(config.presencePath, "/sys/ex_ctl/human");
        check(config.zeroMeansPresent, false);
        check(config.samePins(defaults), false);
        check(defaults.samePins(new PortworldPanelPlugin.Config(new HashMap<>())), true);
    }

    /** Off the panel su fails, so the LED never gets ready; presence works from any file. */
    private static void session() throws Exception {
        File node = File.createTempFile("human", null);
        node.deleteOnExit();
        write(node, "1\n\0");
        Map<String, Object> settings = new HashMap<>();
        settings.put("presencePath", node.getPath());
        settings.put("pollSeconds", 0.5);
        settings.put("triggerCleared", true);
        settings.put("redPin", 1000.0);  // a pin that exists nowhere

        FakeHost host = new FakeHost();
        PortworldPanelPlugin plugin = new PortworldPanelPlugin();
        plugin.start(host, settings);
        check(host.has("select.led=null"), true);
        waitFor(host, "binary_sensor.presence(occupancy)=false");
        waitForPrefix(host, "error LED: ");

        // Nothing fired for the first reading; a change fires the trigger.
        check(host.hasPrefix("trigger"), false);
        write(node, "0\n\0");
        waitFor(host, "binary_sensor.presence(occupancy)=true");
        waitFor(host, "trigger presenceDetected");
        write(node, "1\n\0");
        waitFor(host, "trigger presenceCleared");

        // A choice while the LED is not ready is refused and the state stays unknown.
        plugin.onEvent("select.led", Collections.<String, Object>singletonMap("option", "red"));
        check(host.has("error The LED is not ready yet."), true);

        plugin.stop();
        int count = host.events.size();
        write(node, "0\n\0");
        Thread.sleep(800);
        check(host.events.size(), count);  // stopped: nothing more published
    }

    /** A panel without the optional presence sensor gets no presence entity, also after a path change. */
    private static void noSensor() throws Exception {
        Map<String, Object> settings = new HashMap<>();
        settings.put("presencePath", "/nonexistent/human");
        settings.put("redPin", 1000.0);
        FakeHost host = new FakeHost();
        PortworldPanelPlugin plugin = new PortworldPanelPlugin();
        plugin.start(host, settings);
        Thread.sleep(300);
        check(host.hasPrefix("binary_sensor"), false);

        File node = File.createTempFile("human", null);
        node.deleteOnExit();
        write(node, "0\n\0");
        settings.put("presencePath", node.getPath());
        plugin.configure(settings);
        waitFor(host, "binary_sensor.presence(occupancy)=true");

        settings.put("presencePath", "/nonexistent/human");
        plugin.configure(settings);
        check(host.has("removed binary_sensor.presence"), true);
        plugin.stop();
    }

    private static void write(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void waitFor(FakeHost host, String event) throws InterruptedException {
        for (int i = 0; i < 100 && !host.has(event); i++) Thread.sleep(50);
        if (!host.has(event)) throw new AssertionError("never saw " + event + " in " + host.events);
    }

    private static void waitForPrefix(FakeHost host, String prefix) throws InterruptedException {
        for (int i = 0; i < 200 && !host.hasPrefix(prefix); i++) Thread.sleep(50);
        if (!host.hasPrefix(prefix)) throw new AssertionError("never saw " + prefix + " in " + host.events);
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but was " + actual);
    }
}
