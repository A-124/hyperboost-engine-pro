# Shizuku setup (easy, no computer needed)

HyperBoost uses Shizuku to run the optimization commands without root. This needs **Android 11 or newer**.

## Steps

1. **Install Shizuku** from Google Play or from its GitHub releases page.
2. **Turn on Developer options.** Open *Settings → About phone* and tap *Build number* 7 times.
3. **Turn on Wireless debugging.** Open *Settings → Developer options* and enable *Wireless debugging*. Keep Wi-Fi on.
4. **Pair Shizuku.** Open Shizuku and follow its Wireless debugging section. It will ask you to pair using a pairing code shown in Wireless debugging.
5. **Start Shizuku** using Wireless debugging from inside the Shizuku app.
6. **Connect HyperBoost.** Open HyperBoost, tap the refresh icon at the top, then tap **Grant Permission**. The Shizuku chip at the top should turn green.

## Troubleshooting

- **Shizuku Service Inactive:** Shizuku is not running. Start it again from the Shizuku app.
- **Shizuku Authorization Needed:** tap **Grant Permission** in HyperBoost and approve the prompt.
- **Stops after a reboot:** Shizuku usually needs to be started again after each restart.
- **Wireless debugging turns off:** Android can turn it off when you leave Wi-Fi or after a timeout. Turn it back on and start Shizuku again.
- **Android 10 or older:** wireless pairing is not available. Use Shizuku with a computer (`adb tcpip 5555`, then `adb connect`) as described in the Shizuku documentation.
