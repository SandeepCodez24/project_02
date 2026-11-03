package com.example.kharcha.shared

/**
 * Platform capabilities the shared UI can offer. Android supplies SMS tracking;
 * iOS will supply the Shortcuts setup flow (iOS apps cannot read SMS).
 */
data class PlatformHooks(
    val smsTrackingEnabled: Boolean = false,
    val onEnableSmsTracking: (() -> Unit)? = null,
)
