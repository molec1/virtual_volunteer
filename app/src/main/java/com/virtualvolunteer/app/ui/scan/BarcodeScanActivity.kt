package com.virtualvolunteer.app.ui.scan

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * Thin wrapper around ZXing embedded [CaptureActivity] so we can declare a stable entry in the manifest.
 */
class BarcodeScanActivity : CaptureActivity()
