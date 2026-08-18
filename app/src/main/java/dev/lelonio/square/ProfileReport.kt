package dev.lelonio.square

import android.util.Log
import androidx.profileinstaller.ProfileVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Says, in the log, whether this install is actually compiled.
 *
 * The app ships a baseline profile so the code the bar and the player use is
 * compiled before it runs rather than while it runs. What it cannot do is
 * decide when: Android installs the profile at first launch and compiles it
 * later, with a system job that waits for the phone to be idle and charging.
 * Until that job runs, everything is interpreted and optimised on the fly,
 * which is exactly what "it gets smoother the longer the app is open" is.
 *
 * That makes the difference between a real performance problem and a phone that
 * has not compiled the app yet impossible to tell apart by feel. This removes
 * the guessing: one line at startup saying which of the two it is.
 */
fun reportProfileStatus(scope: CoroutineScope) {
    scope.launch(Dispatchers.IO) {
        runCatching {
            val status = ProfileVerifier.getCompilationStatusAsync().get()
            val state = when (status.profileInstallResultCode) {
                ProfileVerifier.CompilationStatus.RESULT_CODE_NO_PROFILE ->
                    "no profile shipped"
                ProfileVerifier.CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE ->
                    "compiled with the profile"
                ProfileVerifier.CompilationStatus.RESULT_CODE_PROFILE_ENQUEUED_FOR_COMPILATION ->
                    "profile installed, waiting for the system to compile it"
                ProfileVerifier.CompilationStatus.RESULT_CODE_COMPILED_WITH_PROFILE_NON_MATCHING ->
                    "compiled with a profile from another build"
                ProfileVerifier.CompilationStatus.RESULT_CODE_ERROR_PACKAGE_NAME_DOES_NOT_EXIST ->
                    "package not found"
                else -> "unknown (${status.profileInstallResultCode})"
            }
            Log.i(
                TAG,
                "baseline profile: $state" +
                    " (compiled with a profile: ${status.isCompiledWithProfile}," +
                    " waiting to be compiled: ${status.hasProfileEnqueuedForCompilation()})",
            )
        }.onFailure { Log.w(TAG, "could not read the profile status: $it") }
    }
}

private const val TAG = "SquareProfile"
