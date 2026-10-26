// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The LED's GPIOs through /sys/class/gpio. Exporting a pin is root-only and is lost at every boot, so open() runs
 * su once to export the pins, make them outputs and make their value files writable for everyone. After that the
 * plugin writes the value files itself, a few microseconds a write, without starting a process per change.
 */
final class SysfsLed implements Led {
    private static final String GPIO = "/sys/class/gpio";
    private static final String[] SU = {"/system/xbin/su", "/system/bin/su", "su"};
    private static final long SU_TIMEOUT_SECONDS = 5;
    private static final int MAX_OUTPUT = 2048;

    private final FileOutputStream[] values = new FileOutputStream[3];
    private final boolean[] shown = new boolean[3];
    private boolean known;  // shown holds what the pins really are

    private SysfsLed(int[] pins) throws IOException {
        try {
            for (int i = 0; i < 3; i++) values[i] = new FileOutputStream(value(pins[i]));
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** Prepares the pins, with su when they are not ready yet, and opens them. Blocks for up to a few seconds. */
    static SysfsLed open(int red, int green, int blue) throws IOException, InterruptedException {
        int[] pins = {red, green, blue};
        if (!ready(pins)) {
            prepare(pins);
            if (!ready(pins)) throw new IOException("The LED pins are still not writable after su.");
        }
        return new SysfsLed(pins);
    }

    @Override public synchronized void show(boolean red, boolean green, boolean blue) throws IOException {
        boolean[] wanted = {red, green, blue};
        for (int i = 0; i < 3; i++) {
            if (known && shown[i] == wanted[i]) continue;
            values[i].write(wanted[i] ? '1' : '0');
            shown[i] = wanted[i];
        }
        known = true;
    }

    @Override public synchronized void close() {
        for (FileOutputStream value : values) {
            if (value == null) continue;
            try {
                value.close();
            } catch (IOException ignored) {
                // Nothing left to do with a pin that will not close.
            }
        }
    }

    private static boolean ready(int[] pins) {
        for (int pin : pins) {
            if (!new File(value(pin)).canWrite() || !"out".equals(read(GPIO + "/gpio" + pin + "/direction"))) return false;
        }
        return true;
    }

    /** Runs the setup as root. The script is built from pin numbers only, so settings cannot inject into it. */
    private static void prepare(int[] pins) throws IOException, InterruptedException {
        StringBuilder script = new StringBuilder();
        for (int pin : pins) {
            String dir = GPIO + "/gpio" + pin;
            script.append("[ -d ").append(dir).append(" ] || echo ").append(pin).append(" > ").append(GPIO).append("/export; ")
                    .append("echo out > ").append(dir).append("/direction && chmod 0666 ").append(dir).append("/value || exit 1; ");
        }
        IOException last = null;
        for (String su : SU) {
            // AOSP's su takes "su WHO COMMAND...", Magisk-style ones "su -c SCRIPT".
            for (String[] command : new String[][] {{su, "root", "sh", "-c", script.toString()}, {su, "-c", script.toString()}}) {
                try {
                    run(command);
                    return;
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        throw new IOException("Could not prepare the LED pins as root: " + last.getMessage(), last);
    }

    private static void run(String[] command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IOException(command[0] + " did not answer.");
            if (process.exitValue() != 0) {
                throw new IOException(command[0] + " exited with " + process.exitValue() + ": " + output(process.getInputStream()));
            }
        } finally {
            process.destroy();
        }
    }

    private static String output(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int n;
        while (out.size() < MAX_OUTPUT && (n = in.read(buffer, 0, Math.min(buffer.length, MAX_OUTPUT - out.size()))) > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8").trim();
    }

    private static String value(int pin) {
        return GPIO + "/gpio" + pin + "/value";
    }

    private static String read(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buffer = new byte[16];
            int n = in.read(buffer);
            return n <= 0 ? "" : new String(buffer, 0, n, StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            return "";
        }
    }
}
