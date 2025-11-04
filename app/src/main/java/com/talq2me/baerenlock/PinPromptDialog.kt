package com.talq2me.baerenlock

import android.app.AlertDialog
import android.content.Context
import android.widget.*

object PinPromptDialog {
    fun show(
        context: Context,
        promptText: String = "Enter PIN",
        onPinEntered: (String) -> Unit
    ) {
        var input = ""
        val dialog = AlertDialog.Builder(context).create()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val prompt = TextView(context).apply {
            text = promptText
            textSize = 20f
        }
        layout.addView(prompt)
        val pinDots = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
        }
        layout.addView(pinDots)
        val errorText = TextView(context).apply {
            setTextColor(0xFFFF4444.toInt())
            textSize = 16f
        }
        layout.addView(errorText)
        val numberPad = GridLayout(context).apply {
            rowCount = 4
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val density = context.resources.displayMetrics.density
        val buttonSize = (64 * density).toInt() // 64dp
        val buttons = listOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "⌫", "0", "⏎"
        )
        fun updatePinDots() {
            pinDots.removeAllViews()
            for (i in 0 until 4) {
                val dot = TextView(context).apply {
                    text = if (i < input.length) "●" else "○"
                    textSize = 32f
                    setPadding(8, 0, 8, 0)
                }
                pinDots.addView(dot)
            }
        }
        fun clearError() { errorText.text = "" }
        buttons.forEach { label ->
            val btn = Button(context).apply {
                text = label
                textSize = 22f
                minWidth = buttonSize
                minHeight = buttonSize
                width = buttonSize
                height = buttonSize
                setOnClickListener {
                    clearError()
                    when (label) {
                        "⌫" -> {
                            if (input.isNotEmpty()) input = input.dropLast(1)
                        }
                        "⏎" -> {
                            if (input.length < 4) {
                                errorText.text = "PIN must be 4 digits"
                                return@setOnClickListener
                            }
                            dialog.dismiss()
                            onPinEntered(input)
                        }
                        else -> {
                            if (input.length < 4) input += label
                        }
                    }
                    updatePinDots()
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = buttonSize
                height = buttonSize
                setMargins(8, 8, 8, 8)
            }
            numberPad.addView(btn, params)
        }
        layout.addView(numberPad)
        dialog.setView(layout)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        updatePinDots()
    }
} 
