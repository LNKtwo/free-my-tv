# Home Assistant TV

A small separate app that opens Home Assistant on the TV, kept apart from Free My TV because Free My TV holds ADB access. The arrows move a frame (`assets/tvnav.js`) and OK taps where it is.

The address is set in `MainActivity.java` and `res/xml/network_security_config.xml`. Build with aapt2, javac, d8, zipalign and apksigner, as `build.sh` does for the main app. Updates need the same signing key, or uninstall first and sign in again.

It opens the `tv-dashboard` dashboard in Home Assistant: tiles on one screen, the frame goes from tile to tile and OK does the tile's action. Other pages keep frame-per-control navigation.
