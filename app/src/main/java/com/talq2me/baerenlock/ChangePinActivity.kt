package com.talq2me.baerenlock

import android.content.Context
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ChangePinActivity : AppCompatActivity() {
    private var newPin = ""
    private var step = 0 // 0: new, 1: confirm
    private lateinit var prompt: TextView
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        prompt = TextView(this).apply {
            textSize = 20f
            text = "Enter new PIN"
        }
        layout.addView(prompt)
        errorText = TextView(this).apply {
            setTextColor(0xFFFF4444.toInt())
            textSize = 16f
        }
        layout.addView(errorText)
        setContentView(layout)
        showNewPinPrompt()
    }

    private fun showNewPinPrompt() {
        PinPromptDialog.show(this, "Enter new PIN") { pin ->
            if (pin.length == 4) {
                newPin = pin
                prompt.text = "Confirm new PIN"
                showConfirmPinPrompt()
            } else {
                errorText.text = "PIN must be 4 digits"
                showNewPinPrompt()
            }
        }
    }

    private fun showConfirmPinPrompt() {
        PinPromptDialog.show(this, "Confirm new PIN") { pin ->
            if (pin == newPin) {
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                prefs.edit().putString("parent_pin", newPin).apply()
                Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                errorText.text = "PINs do not match"
                prompt.text = "Enter new PIN"
                showNewPinPrompt()
            }
        }
    }
} 
