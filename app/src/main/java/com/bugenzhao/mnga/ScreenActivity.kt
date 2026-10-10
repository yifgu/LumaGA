package com.bugenzhao.mnga

import android.app.Activity
import android.content.Intent
import com.bugenzhao.mnga.ui.nav.Route
import com.bugenzhao.mnga.ui.nav.RouteCodec

/** A task-stack destination so Android, rather than Compose, animates system back. */
class ScreenActivity : MainActivity() {
    override fun initialRoute(): Route? =
        intent.getStringExtra(EXTRA_ROUTE)?.let(RouteCodec::decode)

    companion object {
        private const val EXTRA_ROUTE = "com.bugenzhao.mnga.ROUTE"

        fun intent(activity: Activity, route: Route): Intent =
            Intent(activity, ScreenActivity::class.java)
                .putExtra(EXTRA_ROUTE, RouteCodec.encode(route))
    }
}
