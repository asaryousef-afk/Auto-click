package com.smarttouch.ai.shortcuts

/**
 * Start / Stop / Pause / Resume are exposed as Android static App Shortcuts
 * (see res/xml/shortcuts.xml, referenced from AndroidManifest's MainActivity).
 *
 * Long-press the app icon to see them, or long-press → "App info" → "Shortcuts" on
 * some launchers. On Samsung devices, these same shortcuts can be attached to a
 * Bixby Routine as an "Open app shortcut" action (Settings → Modes and Routines →
 * create/edit a Routine → Add action → Open app → Vigil → choose the
 * shortcut). Samsung does not expose a public API for third-party apps to register
 * arbitrary Bixby *voice* phrases directly - App Shortcuts + Routines is the
 * supported mechanism, so that's what this app uses rather than pretending a raw
 * voice command can call into the app directly.
 *
 * Each saved setup (Touch Settings -> Saved setups) also gets its own dynamic
 * shortcut this same way - see SetupShortcuts.kt.
 */
object ShortcutsInfo {
    const val DOCUMENTATION = "See res/xml/shortcuts.xml for the 4 declared shortcuts."
}
