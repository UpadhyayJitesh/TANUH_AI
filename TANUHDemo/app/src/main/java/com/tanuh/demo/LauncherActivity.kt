package com.tanuh.demo

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.tanuh.demo.sdktest.SdkTestActivity

/**
 * Entry screen. Lets the tester choose between:
 *  1. the original voice-memo flow (Vosk ASR -> MobileBERT sentiment), unchanged, and
 *  2. the SDK exploration flow (JSON-driven preprocessing + path-loaded ONNX/LiteRT models).
 */
class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_launcher)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.launcherRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<MaterialCardView>(R.id.voiceMemoCard).setOnClickListener {
            startActivity(Intent(this, VoiceMemoActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.sdkTestCard).setOnClickListener {
            startActivity(Intent(this, SdkTestActivity::class.java))
        }
    }
}
