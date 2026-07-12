package com.inscopelabs.abxmcp.boot

import android.content.Context
import android.util.Log

object BootGuard {
    private const val TAG = "ABX_BOOT"
    private const val PREFS_NAME = "abx_boot_guard"

    // Persistence Keys
    private const val KEY_HAS_FAILURE = "has_failure"
    private const val KEY_FAILURE_STAGE = "failure_stage"
    private const val KEY_FAILURE_MESSAGE = "failure_message"
    private const val KEY_FAILURE_STACK = "failure_stack"
    private const val KEY_FAILURE_TIMESTAMP = "failure_timestamp"

    // In-memory cache of failure state
    @Volatile
    private var inMemoryFailure: Failure? = null

    private inline fun <T> safely(defaultValue: T, block: () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            try {
                Log.e(TAG, "Exception swallowed in BootGuard internal operation", t)
            } catch (ignored: Throwable) {}
            defaultValue
        }
    }

    fun stageStart(stage: String) {
        safely(Unit) {
            Log.i(TAG, "START: $stage")
        }
    }

    fun stageSuccess(stage: String) {
        safely(Unit) {
            Log.i(TAG, "SUCCESS: $stage")
        }
    }

    fun recordFailure(context: Context?, stage: String, throwable: Throwable) {
        safely(Unit) {
            Log.e(TAG, "FAILURE at stage '$stage'", throwable)
            val stackTrace = Log.getStackTraceString(throwable)
            val timestamp = System.currentTimeMillis().toString()
            val message = throwable.message ?: throwable.javaClass.simpleName

            val failure = Failure(
                stage = stage,
                message = message,
                stackTrace = stackTrace,
                timestamp = timestamp
            )

            inMemoryFailure = failure

            if (context != null) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(KEY_HAS_FAILURE, true)
                    .putString(KEY_FAILURE_STAGE, stage)
                    .putString(KEY_FAILURE_MESSAGE, message)
                    .putString(KEY_FAILURE_STACK, stackTrace)
                    .putString(KEY_FAILURE_TIMESTAMP, timestamp)
                    .apply()
            }
        }
    }

    fun hasFailure(context: Context?): Boolean {
        return safely(false) {
            if (inMemoryFailure != null) {
                true
            } else if (context != null) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getBoolean(KEY_HAS_FAILURE, false)
            } else {
                false
            }
        }
    }

    fun currentFailure(context: Context?): Failure? {
        return safely(null) {
            inMemoryFailure?.let { return@safely it }

            if (context != null) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_HAS_FAILURE, false)) {
                    val stage = prefs.getString(KEY_FAILURE_STAGE, "") ?: ""
                    val message = prefs.getString(KEY_FAILURE_MESSAGE, "") ?: ""
                    val stackTrace = prefs.getString(KEY_FAILURE_STACK, "") ?: ""
                    val timestamp = prefs.getString(KEY_FAILURE_TIMESTAMP, "") ?: ""
                    val failure = Failure(stage, message, stackTrace, timestamp)
                    inMemoryFailure = failure
                    failure
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    fun clear(context: Context?) {
        safely(Unit) {
            inMemoryFailure = null
            if (context != null) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
            }
        }
    }

    data class Failure(
        val stage: String,
        val message: String,
        val stackTrace: String,
        val timestamp: String
    )
}
