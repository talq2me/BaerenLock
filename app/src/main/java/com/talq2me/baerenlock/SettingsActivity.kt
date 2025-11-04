package com.talq2me.baerenlock

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        // Create UI programmatically
        val scrollView = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        scrollView.addView(mainLayout)
        setContentView(scrollView)

        // Title
        val titleText = TextView(this).apply {
            text = "Settings"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }
        mainLayout.addView(titleText)

        // Parent Email Section
        addSectionHeader(mainLayout, "Parental Controls")

        val emailInput = EditText(this).apply {
            hint = "Parent Email Address"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        // Load current email
        val currentEmail = prefs.getString("parent_email", "")
        emailInput.setText(currentEmail)

        mainLayout.addView(emailInput)

        val saveEmailButton = Button(this).apply {
            text = "Save Email"
            setOnClickListener {
                val email = emailInput.text.toString().trim()
                if (email.isNotEmpty() && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    prefs.edit().putString("parent_email", email).apply()
                    Toast.makeText(this@SettingsActivity, "Email saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                }
            }
        }
        mainLayout.addView(saveEmailButton)

        // Memory Management Section
        addSectionHeader(mainLayout, "Memory Management")

        val memoryCleanupSwitch = Switch(this).apply {
            text = "Aggressive Background App Cleanup"
            textSize = 16f
            isChecked = prefs.getBoolean("aggressive_cleanup", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("aggressive_cleanup", isChecked).apply()
                val status = if (isChecked) "enabled" else "disabled"
                Toast.makeText(this@SettingsActivity, "Background cleanup $status", Toast.LENGTH_SHORT).show()
                Log.d("SettingsActivity", "Aggressive cleanup $status")
            }
        }

        val memoryStatusText = TextView(this).apply {
            text = "Only BaerenLock, BaerenEd, and PokemonGo will be allowed to run in the background. All other apps will be closed when not in focus. Current allowed apps for launcher: ${com.talq2me.baerenlock.RewardManager.getAllowedAppsList().joinToString(", ")}"
            textSize = 12f
            setPadding(0, 8, 0, 16)
        }
        mainLayout.addView(memoryStatusText)

        // Manual cleanup button
        val cleanupButton = Button(this).apply {
            text = "Clean Up Background Apps Now"
            setOnClickListener {
                com.talq2me.baerenlock.RewardManager.killUnauthorizedBackgroundApps(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "Background apps cleaned up", Toast.LENGTH_SHORT).show()
            }
        }
        mainLayout.addView(cleanupButton)

        // Action buttons
        addSectionHeader(mainLayout, "Actions")

        val testReportButton = Button(this).apply {
            text = "Send Test Usage Report"
            setOnClickListener {
                val intent = Intent(this@SettingsActivity, MainActivity::class.java)
                intent.putExtra("test_report", true)
                startActivity(intent)
            }
        }
        mainLayout.addView(testReportButton)

        val deviceOwnerButton = Button(this).apply {
            text = "Device Owner Setup"
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, DeviceOwnerSetupActivity::class.java))
            }
        }
        mainLayout.addView(deviceOwnerButton)

        val restrictionsButton = Button(this).apply {
            text = "Device Restrictions"
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, DeviceRestrictionsActivity::class.java))
            }
        }
        mainLayout.addView(restrictionsButton)

        val appManagementButton = Button(this).apply {
            text = "App Management"
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, AppManagementActivity::class.java))
            }
        }
        mainLayout.addView(appManagementButton)

        // Back button
        val backButton = Button(this).apply {
            text = "Back to Main"
            setOnClickListener {
                finish()
            }
        }
        mainLayout.addView(backButton)
    }

    private fun addSectionHeader(parent: LinearLayout, title: String) {
        val header = TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
        parent.addView(header)
    }
} 
