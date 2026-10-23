package app.gamenative

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Dedicated debug/DUMP entry; no MainActivity, XEnvironment, GUI probe, or autostart path. */
class ForeverWineSupervisorActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var consent: CheckBox
    private lateinit var start: Button
    private lateinit var cancel: Button
    private var controller: ForeverWineSupervisor? = null
    private var removeListener: (() -> Unit)? = null
    private var alive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        ForeverWineSupervisorPolicy.requireDebugProcOnly(this)
        super.onCreate(savedInstanceState)
        controller = ForeverWineSupervisor.get(applicationContext)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply { text = "Forever Wine server isolation"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Native-only SERVER_ISOLATION. No WoW client, graphics, audio, overlay, or download operation."
            setPadding(0, 20, 0, 20)
        })
        consent = CheckBox(this).apply { text = "I consent to start the isolated Wine control" }
        start = Button(this).apply { text = "Start control"; isEnabled = false; setOnClickListener { startControl() } }
        cancel = Button(this).apply { text = "Cancel control"; isEnabled = false; setOnClickListener { controller?.cancel() } }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 0) }
        consent.setOnCheckedChangeListener { _, checked -> start.isEnabled = checked && controller?.state?.value?.status == "IDLE" }
        root.addView(consent); root.addView(start); root.addView(cancel); root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
        removeListener = controller!!.addUiListener { value -> if (alive) runOnUiThread { if (alive) render(value) } }
    }

    override fun onDestroy() {
        alive = false
        removeListener?.invoke(); removeListener = null
        controller?.onActivityDestroyed(isChangingConfigurations)
        super.onDestroy()
    }

    private fun startControl() {
        if (controller?.start(consent.isChecked) == true) {
            consent.isEnabled = false; start.isEnabled = false; cancel.isEnabled = true
        }
    }

    private fun render(value: ForeverWineSupervisorState) {
        status.text = buildString {
            append(value.status); append('\n'); append(value.message)
            value.error?.let { append("\nError: "); append(it) }
        }
        start.isEnabled = value.status == "IDLE" && consent.isChecked
        cancel.isEnabled = value.status in setOf("PREPARING", "READY", "RUNNING", "CANCELLING")
    }
}
