package com.talq2me.baerenlock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class DeviceRestrictionsActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var restrictionsManager: RestrictionsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Device Policy Manager
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        restrictionsManager = RestrictionsManager.getInstance(this)

        // Check if device owner is active
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "Device Owner not active. Some features may not work.", Toast.LENGTH_LONG).show()
        }

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
            text = "Device Restrictions"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }
        mainLayout.addView(titleText)

        // Hardware Restrictions Section
        addSectionHeader(mainLayout, "Hardware Restrictions")

        addRestrictionToggle(mainLayout, "Camera", "disable_camera")
        addRestrictionToggle(mainLayout, "Microphone", "disable_microphone")
        addRestrictionToggle(mainLayout, "Bluetooth", "disable_bluetooth")
        addRestrictionToggle(mainLayout, "WiFi", "disable_wifi")
        addRestrictionToggle(mainLayout, "Location Services", "disable_location")
        addRestrictionToggle(mainLayout, "Screen Capture", "disable_screen_capture")

        // UI Restrictions Section
        addSectionHeader(mainLayout, "User Interface Restrictions")

        addRestrictionToggle(mainLayout, "Status Bar", "disable_status_bar")
        addRestrictionToggle(mainLayout, "Keyguard (Lock Screen)", "disable_keyguard")
        addRestrictionToggle(mainLayout, "Safe Boot", "disable_safe_boot")

        // Network Restrictions Section
        addSectionHeader(mainLayout, "Network & Connectivity")

        addRestrictionToggle(mainLayout, "Mobile Data Roaming", "disable_data_roaming")
        addRestrictionToggle(mainLayout, "USB File Transfer", "disable_usb_file_transfer")
        addRestrictionToggle(mainLayout, "NFC", "disable_nfc")

        // Security Restrictions Section
        addSectionHeader(mainLayout, "Security & Privacy")

        addRestrictionToggle(mainLayout, "Factory Reset", "disable_factory_reset")
        addRestrictionToggle(mainLayout, "Developer Options", "disable_developer_options")
        addRestrictionToggle(mainLayout, "Unknown Sources", "disable_unknown_sources")
        addRestrictionToggle(mainLayout, "Verify Apps", "disable_verify_apps")

        // App Restrictions Section
        addSectionHeader(mainLayout, "Application Restrictions")

        addRestrictionToggle(mainLayout, "Play Store", "disable_play_store")
        addRestrictionToggle(mainLayout, "Google Apps", "disable_google_apps")
        addRestrictionToggle(mainLayout, "Voice Assistants", "disable_voice_assistants")
        addRestrictionToggle(mainLayout, "Printing", "disable_printing")

        // Action Buttons
        addSectionHeader(mainLayout, "Actions")

        val lockDeviceButton = Button(this).apply {
            text = "Lock Device Now"
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                lockDevice()
            }
        }
        mainLayout.addView(lockDeviceButton)

        val rebootButton = Button(this).apply {
            text = "Reboot Device"
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                rebootDevice()
            }
        }
        mainLayout.addView(rebootButton)

        // Back button
        val backButton = Button(this).apply {
            text = "Back to Settings"
            setPadding(16, 16, 16, 16)
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

    private fun addRestrictionToggle(parent: LinearLayout, label: String, restrictionKey: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val toggle = Switch(this).apply {
            isChecked = restrictionsManager.isRestrictionEnabled(restrictionKey)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    restrictionsManager.enableRestriction(restrictionKey)
                } else {
                    restrictionsManager.disableRestriction(restrictionKey)
                }
            }
        }

        container.addView(labelView)
        container.addView(toggle)
        parent.addView(container)
    }

    private fun lockDevice() {
        try {
            devicePolicyManager.lockNow()
            Toast.makeText(this, "Device locked", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("DeviceRestrictions", "Failed to lock device", e)
            Toast.makeText(this, "Failed to lock device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun rebootDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                devicePolicyManager.reboot(adminComponent)
                Toast.makeText(this, "Device rebooting...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Reboot not available on this Android version", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("DeviceRestrictions", "Failed to reboot device", e)
            Toast.makeText(this, "Failed to reboot device", Toast.LENGTH_SHORT).show()
        }
    }
}

