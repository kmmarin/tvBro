// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Restarts the app in one press (Settings > About > Advanced > Restart now): the app's process ends and this activity,
 * in its own small process, opens tvBro again once it is gone (a relaunch from the dying process itself could die
 * with it).
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(PID, -1)
        Thread {
            // wait (up to 3 s) for the old process to be gone
            val until = System.currentTimeMillis() + 3000
            while (pid > 0 && java.io.File("/proc/$pid").exists() && System.currentTimeMillis() < until) Thread.sleep(30)
            runOnUiThread {
                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
                finish()
                Process.killProcess(Process.myPid())
            }
        }.start()
    }

    companion object {
        private const val PID = "pid"

        /** Saves what is pending (the caller's [beforeExit]) and restarts the app. */
        fun restart(ctx: Context, beforeExit: () -> Unit = {}) {
            runCatching(beforeExit)
            ctx.startActivity(Intent(ctx, RestartActivity::class.java).putExtra(PID, Process.myPid()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // (a moment for the restart activity to be started before this process goes)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 150)
        }
    }
}
