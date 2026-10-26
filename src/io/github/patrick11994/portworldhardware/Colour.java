// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

/** The colours the LED can show: each channel is only on or off, so there are seven and off. */
enum Colour {
    OFF("off", false, false, false),
    RED("red", true, false, false),
    GREEN("green", false, true, false),
    BLUE("blue", false, false, true),
    YELLOW("yellow", true, true, false),
    CYAN("cyan", false, true, true),
    MAGENTA("magenta", true, false, true),
    WHITE("white", true, true, true);

    /** The seven lit colours, in the order the colour loop shows them. */
    static final Colour[] LIT = {RED, GREEN, BLUE, YELLOW, CYAN, MAGENTA, WHITE};

    final String option;
    final boolean red, green, blue;

    Colour(String option, boolean red, boolean green, boolean blue) {
        this.option = option;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    /** The colour for a select option, or null when the option is an effect. */
    static Colour named(String option) {
        for (Colour colour : values()) if (colour.option.equals(option)) return colour;
        return null;
    }
}
