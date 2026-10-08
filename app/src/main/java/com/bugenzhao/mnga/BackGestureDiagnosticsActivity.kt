package com.bugenzhao.mnga

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bugenzhao.mnga.util.BackDiagnostics
import com.bugenzhao.mnga.util.RawBackGestureTrace

/** A separate window with no ComponentActivity/AndroidX back bridge or NavHost. */
class BackGestureDiagnosticsActivity : Activity() {
    private val session = SystemClock.uptimeMillis()
    private val trace = RawBackGestureTrace(SystemClock::uptimeMillis) {
        BackDiagnostics.log("raw-probe session=$session $it")
    }
    private var unregisterCallback: (() -> Unit)? = null
    private lateinit var status: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_back_gesture_diagnostics)
        status = findViewById(R.id.back_probe_status)
        progressBar = findViewById(R.id.back_probe_progress)
        findViewById<Button>(R.id.back_probe_close).setOnClickListener { finish() }
        val root = findViewById<android.view.View>(R.id.back_probe_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            status.setText(R.string.back_probe_unsupported)
        }
        BackDiagnostics.log(
            "raw-probe session=$session opened app=${BuildConfig.VERSION_NAME} " +
                "sdk=${Build.VERSION.SDK_INT} targetSdk=${applicationInfo.targetSdkVersion}",
        )
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            unregisterCallback = Api34.register(this)
            BackDiagnostics.log("raw-probe session=$session callback registered priority=default")
        }
    }

    override fun onStop() {
        unregisterCallback?.invoke()
        unregisterCallback = null
        trace.interrupted()?.let(::showEvent)
        BackDiagnostics.log("raw-probe session=$session callback stopped")
        super.onStop()
    }

    private fun showEvent(event: String) {
        status.text = event
        progressBar.progress = (trace.progress.coerceIn(0f, 1f) * progressBar.max).toInt()
    }

    @RequiresApi(34)
    private object Api34 {
        fun register(activity: BackGestureDiagnosticsActivity): () -> Unit {
            val callback = object : OnBackAnimationCallback {
                override fun onBackStarted(backEvent: BackEvent) {
                    activity.showEvent(activity.trace.started(backEvent.progress, backEvent.swipeEdge))
                }

                override fun onBackProgressed(backEvent: BackEvent) {
                    activity.showEvent(activity.trace.progressed(backEvent.progress, backEvent.swipeEdge))
                }

                override fun onBackCancelled() {
                    activity.showEvent(activity.trace.cancelled())
                }

                override fun onBackInvoked() {
                    // Stay in the probe for repeated trials; only the Close button exits.
                    activity.showEvent(activity.trace.invoked())
                }
            }
            val dispatcher = activity.onBackInvokedDispatcher
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            return { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    }
}
