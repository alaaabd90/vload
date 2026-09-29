package io.nekohasekai.sagernet.security

import android.app.Activity
import android.os.Bundle
import android.content.*
import android.view.WindowManager
import android.widget.*
import io.nekohasekai.sagernet.ui.MainActivity

class ActivationActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Activation.isActive(this)) { enter(); return }
        val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,64,32,32) }
        setContentView(ScrollView(this).apply { addView(layout) })
        fun label(value: String) = TextView(this).apply { text=value; textSize=18f; setPadding(0,16,0,16); layout.addView(this) }
        label("Activate vload")
        if (!SecurityIdentity.trusted(this)) { label("This build has an unrecognized signing certificate."); return }
        label("Send this phone's request to the owner. Paste the activation code returned by vload Authenticator. Codes refresh every 30 seconds. Once accepted, activation stays valid through updates on this installation.")
        val status=label("Preparing phone request…")
        val request=EditText(this).apply { isSingleLine=false; setTextIsSelectable(true); layout.addView(this) }
        val copy=Button(this).apply { text="Copy phone request"; isEnabled=false; layout.addView(this) }
        val input=EditText(this).apply { hint="Paste activation code"; maxLines=6; layout.addView(this) }
        val activate=Button(this).apply { text="Activate"; isEnabled=false; layout.addView(this) }
        copy.setOnClickListener { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("vload request",request.text)); status.text="Request copied" }
        Thread {
            val result=runCatching { Activation.request(this) }
            runOnUiThread { result.fold({request.setText(it); request.keyListener=null; copy.isEnabled=true; activate.isEnabled=true; status.text="Ready"},{status.text="Could not create the device key. Android 6 or newer is required."}) }
        }.start()
        activate.setOnClickListener {
            val token=input.text.toString().trim(); activate.isEnabled=false
            Thread {
                val valid=runCatching { Activation.accept(this,token) }.getOrDefault(false)
                runOnUiThread { activate.isEnabled=true; if(valid) enter() else status.text="Invalid or expired code. Check both clocks and request a fresh code for this phone." }
            }.start()
        }
    }
    private fun enter() { startActivity(Intent(this,MainActivity::class.java)); finish() }
}
