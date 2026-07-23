package com.earceo.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.vision.headset.open.VHOManager
import com.vision.headset.open.auth.VHODeviceVerifyPolicy

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var initializeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        renderInitialState()
    }

    override fun onDestroy() {
        VHOManager.release()
        super.onDestroy()
    }

    private fun createContentView(): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)

            addView(TextView(context).apply {
                text = getString(R.string.app_name)
                textSize = 30f
                setTextColor(Color.rgb(25, 32, 46))
            })

            addView(TextView(context).apply {
                text = getString(R.string.tagline)
                textSize = 16f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                setPadding(0, padding / 2, 0, padding)
            })

            statusText = TextView(context).apply {
                textSize = 20f
                setTextColor(Color.rgb(25, 32, 46))
                gravity = Gravity.CENTER
            }
            addView(
                statusText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            detailText = TextView(context).apply {
                textSize = 14f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                setPadding(0, padding / 2, 0, padding)
            }
            addView(
                detailText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            initializeButton = Button(context).apply {
                text = getString(R.string.initialize_sdk)
                setOnClickListener { initializeSdk() }
            }
            addView(initializeButton)
        }
    }

    private fun renderInitialState() {
        if (hasLocalCredentials()) {
            statusText.text = getString(R.string.ready_to_initialize)
            detailText.text = getString(R.string.credentials_loaded)
            initializeButton.isEnabled = true
        } else {
            statusText.text = getString(R.string.configuration_required)
            detailText.text = getString(R.string.configuration_instructions)
            initializeButton.isEnabled = false
        }
    }

    private fun initializeSdk() {
        initializeButton.isEnabled = false
        statusText.text = getString(R.string.initializing)
        detailText.text = getString(R.string.initializing_detail)

        runCatching {
            VHOManager.initialize(
                context = applicationContext,
                appKey = BuildConfig.VIAIM_APP_KEY,
                appSecret = BuildConfig.VIAIM_APP_SECRET,
                deviceVerifyPolicy = VHODeviceVerifyPolicy.Auto,
            ) { success, info, error ->
                runOnUiThread {
                    if (success) {
                        statusText.text = getString(R.string.initialization_succeeded)
                        val services = info?.enabledServiceIds
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString()
                            ?: getString(R.string.no_services_reported)
                        detailText.text = getString(R.string.enabled_services, services)
                    } else {
                        statusText.text = getString(R.string.initialization_failed)
                        detailText.text = error ?: getString(R.string.unknown_error)
                        initializeButton.isEnabled = true
                    }
                }
            }
        }.onFailure { throwable ->
            statusText.text = getString(R.string.initialization_failed)
            detailText.text = throwable.message ?: getString(R.string.unknown_error)
            initializeButton.isEnabled = true
        }
    }

    private fun hasLocalCredentials(): Boolean =
        BuildConfig.VIAIM_APP_KEY.isNotBlank() &&
            BuildConfig.VIAIM_APP_SECRET.isNotBlank()
}
