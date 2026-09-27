package com.brickssoft.locationtracking.logging

import androidx.core.content.FileProvider

/**
 * FileProvider for sharing log files (authority `${applicationId}.locationtracking.logs`). A subclass is required
 * because the Capacitor app template already declares `androidx.core.content.FileProvider`.
 */
class LogFileProvider : FileProvider()
