# Portworld Panel Hardware Control

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=patrick11994/ks-portworld-panel-hardware-control)

A [Kiosk Satellite](https://kiosksatellite.com) plugin that controls the hardware built into Portworld wall panels: the **RGB side light** and the optional **presence sensor**. Both appear as Kiosk Satellite entities, and through its ESPHome integration in Home Assistant. Nothing else has to run on the panel: no MQTT broker, Termux or scripts.

| Entity | Type | What |
|---|---|---|
| **LED** | Select | `off`, seven colours, and 19 effects |
| **Presence** | Binary sensor (occupancy) | Someone in front of the panel. Only on panels that have the sensor |

## Compatibility

| Panel | State |
|---|---|
| Portworld **YC-SM08P**, 8″, RK3576, Android 14 (firmware `P76S01-…-SM08P-…`) | Tested, Kiosk Satellite 2026.10.41 |
| YC-SM08P, older RK3566 revision (Android 11–13) | Unknown. Another owner reports the same LED pins on an 8″ unit |
| Other Portworld panels | Unknown. Panels without the side light, such as the 4″ YC-SM06E, have nothing to control |

The YC-SM08P is also sold as Wiren Board "Wall panel 8 inches Portworld", ALLNET "Touch Display Tablet 8 Zoll" and herry-24 "Android Panel 8 Zoll POR-Serie". Android only calls it `rockchip rk3576s_u`. The model shows in the firmware tag (**About tablet > Build number**, or `getprop ro.build.display.id`).

**Your panel probably works if** `/sys/ex_ctl/human` exists (for presence), and GPIO 71, 70 and 87 drive the light (for the LED). If your panel uses other pins, set them under **Hardware**.

The presence sensor is an extra the vendor fits on order. Without it, the plugin shows no Presence entity, and the LED still works.

## The LED

Each channel of the light can only be on or off, so there are seven colours and no brightness. That is why the LED is a select rather than a light entity.

| Effect | What it does |
|---|---|
| flash, strobe, pulse | Blink the colour: 0.5 s, 0.1 s, 1.5 s |
| heartbeat | Two quick blinks, then a pause |
| sos | ··· ––– ··· in Morse |
| breathe | Fades the colour in and out |
| color loop, random | Change colour every second |
| color fade | Fades smoothly around the colour wheel |
| random flash | A random colour, blinking |
| alarm | Fast red flashing |
| police | Red, then blue |
| warning | Slow yellow flashing |
| ambulance | Blue, then white |
| fire | Flickering red and yellow |
| traffic light | Green, yellow, red |
| flash once | Blinks a few times, then stays on the colour |
| flash and off | Blinks a few times, then turns off |
| double blink | Blinks twice, then goes back to what was showing before. Use it as a notification from an automation |

The effects that blink, pulse or fade use the last colour you picked, which is white until you pick one. Other effects choose their own colours.

**breathe** and **color fade** dim the light by switching it on and off 200 times a second, which costs some CPU while they run. All other effects only switch the light when they change it.

## Actions and triggers

- **Actions:** **LED off** and **LED double blink**. Use them in Gestures and the kiosk menu, or turn them into Home Assistant buttons on the plugin's page.
- **Gesture triggers:** **Person detected** and **Nobody detected**. Choose what they run under **Gestures > Plugin trigger**. Each can be turned off in the settings.

Turning the screen on when someone approaches is left to the panel's own presence service, which already does it.

## Settings

| Setting | Default | What it does |
|---|---|---|
| Blinks for flash once | 5 | How often flash once and flash and off blink |
| Person detected trigger | On | Fire the trigger when someone comes near |
| Nobody detected trigger | Off | Fire the trigger when the sensor clears |
| Clear after | 0 s | How long the sensor must read empty before Presence turns off. 0 follows the sensor directly |
| Check every | 1 s | Time between sensor readings |
| **Hardware** | | Only change these for another panel or revision |
| Sensor reads 0 when someone is there | On | The YC-SM08P's sensor is inverted |
| Presence sensor file | `/sys/ex_ctl/human` | |
| Red, green and blue LED GPIO | 71, 70, 87 | Linux GPIO numbers |

## Requirements

- Kiosk Satellite **2026.10.41** or later.
- **Root through the firmware's `su`.** The stock YC-SM08P firmware has a `su` that every app may use. The plugin needs it once at each start to export the LED's GPIOs, make them outputs, and make their value files writable. Presence needs no root.

## Privacy and security

- The plugin opens **no network connections**. It reads one sensor file and writes three GPIO files.
- **What runs as root:** at start, for each LED pin, `echo N > /sys/class/gpio/export`, `echo out > …/direction` and `chmod 0666 …/value`. The command contains only the three pin numbers, which must be whole numbers from 0 to 1023, so no setting can add anything else to it.
- After that, any app on the panel can switch the LED, as it could already through the firmware's `su`. Treat the panel as a trusted device: its firmware lets every app become root, and SELinux is permissive.

## Troubleshooting

- **Status "LED: Could not prepare the LED pins as root"**: the firmware's `su` is missing or refuses apps. The LED can't work on this panel without it.
- **No Presence entity**: the panel has no presence sensor, or it is at another path. Check with `ls /sys/ex_ctl/`.
- **Presence shows the opposite**: turn off **Sensor reads 0 when someone is there**.
- **The LED does odd things after a reboot**: another script may drive the same GPIOs, such as an MQTT controller started by Termux:Boot. Stop it.
- **Logs**: Remote Admin **Logs** shows the plugin's messages.

## Building

```sh
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
python3 tools/test.py && python3 tools/build.py --android-platform 34
```

Install `dist/portworld-yc-sm08p-<version>.zip` with **Plugin Manager > Developer Tools > Install from ZIP**. Releases are built by the GitHub workflow from a published release tag.

## Credits

- The Kiosk Satellite plugin SDK, template and build tools by [jxlarrea](https://github.com/jxlarrea/kiosk-satellite-plugins) (Apache-2.0).
- A post by TobiasFFM in the knx-user-forum.de thread "8 Zoll Smart Panel mit PoE" confirmed the LED pins on another unit.

Portworld is a trademark of its owner. This project is not affiliated with or endorsed by Portworld or the Kiosk Satellite project.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
