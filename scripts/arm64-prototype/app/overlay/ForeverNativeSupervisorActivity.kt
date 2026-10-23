package app.gamenative

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** DUMP-protected, native-only laboratory UI; it never initializes the normal app. */
class ForeverNativeSupervisorActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var consent: CheckBox
    private lateinit var start: Button
    private lateinit var cancel: Button
    private var scenario = ForeverNativeSupervisorPolicy.Scenario.NORMAL
    private var controller: ForeverNativeSupervisor? = null
    private var removeListener: (() -> Unit)? = null
    private var alive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        ForeverNativeSupervisorPolicy.requireDebugProcOnly(this)
        super.onCreate(savedInstanceState)
        scenario = ForeverNativeSupervisorPolicy.scenarioFromIntent(intent)
        controller = ForeverNativeSupervisor.get(applicationContext)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply { text = "Forever native supervisor fixture"; textSize = 22f })
        root.addView(TextView(this).apply {
            text = "Native-only ownership control. No Wine, runtime, prefix, client, or patch operation.\nScenario: ${scenario.wireName}"
            setPadding(0, 20, 0, 20)
        })
        consent = CheckBox(this).apply { text = "I consent to start this native fixture control" }
        start = Button(this).apply { text = "Start control"; isEnabled = false; setOnClickListener { startControl() } }
        cancel = Button(this).apply { text = "Cancel control"; isEnabled = false; setOnClickListener { controller?.cancel() } }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 24, 0, 0) }
        consent.setOnCheckedChangeListener { _, checked -> start.isEnabled = checked && controller?.state?.value?.status == "IDLE" }
        root.addView(consent); root.addView(start); root.addView(cancel); root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
        removeListener = controller!!.addUiListener { state -> if (alive) runOnUiThread { if (alive) render(state) } }
    }

    override fun onDestroy() {
        alive = false
        removeListener?.invoke(); removeListener = null
        controller?.onActivityDestroyed(isChangingConfigurations)
        super.onDestroy()
    }

    private fun startControl() {
        if (controller?.start(scenario.wireName, consent.isChecked) == true) {
            consent.isEnabled = false; start.isEnabled = false; cancel.isEnabled = true
        }
    }

    private fun render(state: ForeverNativeSupervisorState) {
        status.text = buildString {
            append(state.status); append('\n'); append(state.message)
            state.error?.let { append("\nError: "); append(it) }
        }
        if (state.status != "IDLE") start.isEnabled = false
        cancel.isEnabled = state.status in setOf("STARTING", "RUNNING", "CANCELLING")
    }
}
