package com.smarttouch.ai

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.smarttouch.ai.data.SavedSetup

/**
 * Publishes one dynamic App Shortcut per saved setup (see Touch Settings ->
 * Saved setups), so each can be attached to a Samsung Routine as an "Open app
 * shortcut" action (Settings -> Modes and Routines -> Add action -> Open app
 * -> Vigil -> pick the setup's shortcut). Routines can be run by voice
 * ("Hi Bixby, run [Routine name]"), which is the supported way to trigger a
 * specific app action by voice - see ShortcutsInfo.kt for why a raw custom
 * Bixby voice phrase isn't something a third-party app can register directly.
 *
 * Opening one of these shortcuts applies that setup's saved touch position
 * and starts the engine if the accessibility service is already enabled -
 * see MainActivity.handleShortcutIntent().
 */
object SetupShortcuts {
    private const val ID_PREFIX = "setup_"

    fun sync(context: Context, setups: List<SavedSetup>) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val maxCount = manager.maxShortcutCountPerActivity.let { if (it > 0) it else 4 }
        val icon = Icon.createWithResource(context, R.mipmap.ic_launcher)

        val shortcuts = setups.take(maxCount).map { setup ->
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_APPLY_SETUP
                putExtra(MainActivity.EXTRA_SETUP_NAME, setup.name)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ShortcutInfo.Builder(context, ID_PREFIX + setup.name)
                .setShortLabel(setup.name)
                .setLongLabel(setup.name)
                .setIcon(icon)
                .setIntent(intent)
                .build()
        }

        // Replaces the whole dynamic set each time - simplest way to keep it in
        // sync with renames/deletes without tracking diffs ourselves.
        runCatching { manager.dynamicShortcuts = shortcuts }
    }
}
