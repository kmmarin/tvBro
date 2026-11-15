// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import android.app.Application

/** Process start: the Rust core rollout switch is read before anything (activity, worker, service) uses the network. */
class TvBroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugHooks.startProfile()
        io.github.kmmarin.tvbro.data.Diag.sink = { android.util.Log.i("tvbro", it) } // (release builds strip Log.i)
        io.github.kmmarin.tvbro.data.CoreSwitch.init(this)
    }
}
