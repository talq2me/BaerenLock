package com.talq2me.baerenlock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class DeviceOwnerSetupActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var setupManager: DeviceOwnerSetupManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize managers
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        setupManager = DeviceOwnerSetupManager.getInstance(this)

        // Check if already device owner
        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "Already configured as Device Owner", Toast.LENGTH_SHORT).show()
            finish()
            return
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
            text = "Device Owner Setup"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }
        mainLayout.addView(titleText)

        // Status text
        val statusText = TextView(this).apply {
            text = "Current status: ${getDeviceOwnerStatus()}"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        mainLayout.addView(statusText)

        // Instructions
        val instructionsText = TextView(this).apply {
            text = """
                To set up BaerenEd as Device Owner, you need to:

                1. Enable Developer Options (if not already enabled)
                2. Enable USB Debugging
                3. Use ADB to run the provisioning command

                You can also try the NFC provisioning method for devices that support it.
            """.trimIndent()
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }
        mainLayout.addView(instructionsText)

        // ADB Command Section
        addSectionHeader(mainLayout, "ADB Provisioning Command")

        val commandText = TextView(this).apply {
            text = "adb shell dpm set-device-owner com.talq2me.baeren/.DeviceAdminReceiver"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 16)
            setBackgroundColor(0xFFE0E0E0.toInt())
            setPadding(16, 16, 16, 16)
        }
        mainLayout.addView(commandText)

        val copyCommandButton = Button(this).apply {
            text = "Copy ADB Command"
            setOnClickListener {
                copyToClipboard("adb shell dpm set-device-owner com.talq2me.baeren/.DeviceAdminReceiver")
                Toast.makeText(this@DeviceOwnerSetupActivity, "Command copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        mainLayout.addView(copyCommandButton)

        // NFC Provisioning Section (for supported devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            addSectionHeader(mainLayout, "NFC Provisioning")

            val nfcButton = Button(this).apply {
                text = "Generate NFC Provisioning File"
                setOnClickListener {
                    generateNfcProvisioningFile()
                }
            }
            mainLayout.addView(nfcButton)
        }

        // Manual Setup Section
        addSectionHeader(mainLayout, "Manual Setup")

        val manualButton = Button(this).apply {
            text = "Open Device Admin Settings"
            setOnClickListener {
                openDeviceAdminSettings()
            }
        }
        mainLayout.addView(manualButton)

        // Check Status Button
        val checkStatusButton = Button(this).apply {
            text = "Check Device Owner Status"
            setOnClickListener {
                checkAndUpdateStatus()
            }
        }
        mainLayout.addView(checkStatusButton)

        // Back button
        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener {
                finish()
            }
        }
        mainLayout.addView(backButton)
    }

    private fun getDeviceOwnerStatus(): String {
        return when {
            devicePolicyManager.isDeviceOwnerApp(packageName) -> "Device Owner Active"
            devicePolicyManager.isAdminActive(adminComponent) -> "Device Admin Active"
            else -> "Not Configured"
        }
    }

    private fun checkAndUpdateStatus() {
        val status = getDeviceOwnerStatus()
        Toast.makeText(this, "Status: $status", Toast.LENGTH_SHORT).show()

        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "Device Owner setup complete!", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("ADB Command", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun generateNfcProvisioningFile() {
        try {
            // In a real implementation, this would generate a proper NFC provisioning file
            // For now, we'll just show instructions
            Toast.makeText(this, "NFC provisioning file generation would be implemented here", Toast.LENGTH_LONG).show()

            // You would typically:
            // 1. Create a DevicePolicyManager.ProvisioningParams object
            // 2. Generate a QR code or NFC data
            // 3. Save it to a file or display it

        } catch (e: Exception) {
            Log.e("DeviceOwnerSetup", "Error generating NFC provisioning", e)
            Toast.makeText(this, "Error generating NFC provisioning file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDeviceAdminSettings() {
        try {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("DeviceOwnerSetup", "Error opening device admin settings", e)
            Toast.makeText(this, "Error opening settings", Toast.LENGTH_SHORT).show()
        }
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

