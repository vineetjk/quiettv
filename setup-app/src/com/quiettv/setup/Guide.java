package com.quiettv.setup;

/** Step-by-step instructions for turning on developer mode, by kind of TV. Kept short and literal. */
final class Guide {
    static final String[] KINDS = {
        "Google TV",
        "Android TV (older)",
        "Xiaomi Mi TV / Mi Box",
        "Not sure",
    };
    static final String[] KIND_HINTS = {
        "Most TVs since 2021 (Sony, TCL, Hisense, Philips, Acer...) and Chromecast with Google TV. The home screen says \"For you\" at the top.",
        "TVs and boxes from before Google TV. Settings has \"Device Preferences\".",
        "Xiaomi TVs and boxes with PatchWall or Android TV.",
        "We'll show steps that work on most TVs.",
    };

    static String[] developerSteps(int kind) {
        switch (kind) {
            case 1:
            case 2:
                return new String[] {
                    "On the TV, open Settings, then Device Preferences, then About.",
                    "Scroll down to \"Build\" and press OK on it 7 times, until it says you're a developer.",
                    "Go back to Device Preferences and open Developer options.",
                    "Turn on \"USB debugging\". If you also see \"Network debugging\" or \"ADB over network\", turn that on too.",
                };
            default:
                return new String[] {
                    "On the TV, open Settings (the gear at the top right of the home screen).",
                    "Go to System, then About.",
                    "Scroll to \"Android TV OS build\" and press OK on it 7 times, until it says you're a developer.",
                    "Go back to System and open Developer options.",
                    "Turn on \"USB debugging\". If you also see \"Network debugging\" or \"ADB over network\", turn that on too.",
                };
        }
    }

    static final String FIND_IP = "On the TV: Settings, then Network & Internet (or Network), then your Wi-Fi network. "
            + "The IP address looks like 192.168.1.50.";

    static final String PAIRING_NOTE = "Only see \"Wireless debugging\" with a pairing code? QuietTV Setup can't use pairing "
            + "codes yet. Turn on \"USB debugging\" too (many TVs then accept connections anyway), or use a computer for now.";

    private Guide() {}
}
