// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.portworldhardware;

import java.io.IOException;

/** The RGB LED's three on/off channels, as a seam for tests. */
interface Led {
    void show(boolean red, boolean green, boolean blue) throws IOException;

    void close();
}
