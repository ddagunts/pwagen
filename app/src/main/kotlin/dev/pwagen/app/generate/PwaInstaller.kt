/*
 * Copyright (C) 2026 pwagen contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.pwagen.app.generate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Hands a generated APK to the system package installer.
 *
 * Every install surfaces Android's own confirmation dialog. That is not a
 * limitation to work around: pwagen writes apps to your launcher, and the
 * platform asking you to confirm each one is the correct amount of friction.
 */
class PwaInstaller(private val context: Context) {

    sealed interface Result {
        data object Success : Result

        /** The user dismissed or declined the system installer dialog. */
        data object Cancelled : Result

        data class Failure(val message: String) : Result
    }

    /**
     * Installs [apk], replacing any existing app with the same package name.
     *
     * An in-place upgrade preserves the PWA's cookies, logins and any firewall
     * rules already set against it, which is why package names are derived
     * deterministically rather than randomly.
     *
     * @param onResult invoked on the main thread once the system reports back.
     */
    fun install(apk: File, packageName: String, onResult: (Result) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRequestUpdateOwnership(true)
            }
        }

        registerResultReceiver(packageName, onResult)

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(packageName, 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            session.commit(statusIntentSender(packageName, sessionId))
        }
    }

    private fun statusIntentSender(packageName: String, sessionId: Int) =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(actionFor(packageName)).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

    private fun registerResultReceiver(packageName: String, onResult: (Result) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // The platform wants to show its confirmation dialog.
                        // Launch it and stay registered for the real outcome.
                        val confirmation = IntentCompat.getParcelable(intent)
                        confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        confirmation?.let(receiverContext::startActivity)
                        return
                    }

                    PackageInstaller.STATUS_SUCCESS -> onResult(Result.Success)

                    PackageInstaller.STATUS_FAILURE_ABORTED -> onResult(Result.Cancelled)

                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        onResult(Result.Failure(message ?: "Install failed with status $status"))
                    }
                }
                receiverContext.unregisterReceiver(this)
            }
        }

        ContextCompat.registerNotExportedReceiver(
            context,
            receiver,
            IntentFilter(actionFor(packageName)),
        )
    }

    private fun actionFor(packageName: String) = "$INSTALL_ACTION.$packageName"

    private companion object {
        const val INSTALL_ACTION = "dev.pwagen.app.INSTALL_RESULT"
    }
}

/** Keeps the deprecated/typed-extra branching out of the installer logic. */
private object IntentCompat {
    fun getParcelable(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}

private object ContextCompat {
    fun registerNotExportedReceiver(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }
}
