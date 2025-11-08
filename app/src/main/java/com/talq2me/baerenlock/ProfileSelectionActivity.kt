package com.talq2me.baerenlock

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ProfileSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentProfile = ProfileFileManager.readProfile(this)

        // Create the main layout
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        val titleText = TextView(this).apply {
            text = "Choose Profile"
            textSize = 24f
            setPadding(0, 0, 0, 32)
            gravity = android.view.Gravity.CENTER
        }
        mainLayout.addView(titleText)

        // Radio group for profile selection
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val radioButtonA = RadioButton(this).apply {
            text = "Child A"
            id = View.generateViewId()
            isChecked = currentProfile == "A"
        }
        val radioButtonB = RadioButton(this).apply {
            text = "Child B"
            id = View.generateViewId()
            isChecked = currentProfile == "B"
        }

        radioGroup.addView(radioButtonA)
        radioGroup.addView(radioButtonB)
        mainLayout.addView(radioGroup)

        // Save button
        val saveButton = Button(this).apply {
            text = "Save Profile Selection"
            setOnClickListener {
                val selectedProfile = when (radioGroup.checkedRadioButtonId) {
                    radioButtonA.id -> "A"
                    radioButtonB.id -> "B"
                    else -> currentProfile ?: "A"
                }

                ProfileFileManager.writeProfile(this@ProfileSelectionActivity, selectedProfile)
                Toast.makeText(this@ProfileSelectionActivity, "Profile saved: Child $selectedProfile. Restart BaerenLock to see the new background.", Toast.LENGTH_LONG).show()
                finish() // Close the activity
            }
        }
        mainLayout.addView(saveButton)

        setContentView(mainLayout)
    }
}
