package io.embodit.keycapstudio.platform

import android.util.Log

/** One tag for the whole app; QA greps `KeycapStudio`. Lines also go to files/input-log.txt via BoardInputLog. */
object KsLog {
    const val TAG = "KeycapStudio"
    fun i(msg: String) = io.embodit.keycapstudio.input.BoardInputLog.write(TAG, msg)  // logcat + files/input-log.txt
    fun w(msg: String) { Log.w(TAG, msg) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t) }
}
