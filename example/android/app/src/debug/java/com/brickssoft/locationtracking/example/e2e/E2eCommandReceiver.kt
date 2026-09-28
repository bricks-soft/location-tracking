package com.brickssoft.locationtracking.example.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log

/**
 * Debug-only command receiver of the plugin example app (docs/e2e/architecture.md §6). The e2e kit sends
 *
 * ```
 * adb shell am broadcast -a com.brickssoft.locationtracking.example.E2E \
 *   -n com.brickssoft.locationtracking.example/.e2e.E2eCommandReceiver --include-stopped-packages \
 *   --es id <id> --es cmd <cmd> --es json64 <base64 of the JSON arguments>
 * ```
 *
 * and reads exactly one logcat line with tag `LT-E2E` for each request:
 * - `{"id":"…","cmd":"…","ok":true,"result":<JSON>}`;
 * - `{"id":"…","cmd":"…","ok":true,"resultFile":"files/e2e/<id>.json"}` when the line would be longer than 3000
 *   bytes (the file holds the full response; read it with `run-as <appId> cat`);
 * - `{"id":"…","cmd":"…","ok":false,"code":"<ErrorCode | BAD_COMMAND | TIMEOUT | INTERNAL>","message":"…"}`.
 *
 * The receiver calls `goAsync()`, waits at most 25 s for the command (8 s for a `--receiver-foreground`
 * broadcast, which Android allows only 10 s) and always finishes the broadcast. The manifest entry requires
 * `android.permission.DUMP` from the sender: `adb shell` (the shell user holds it) and root can send, other apps
 * cannot. This class exists only in the debug source set; release builds contain neither the class nor its manifest
 * entry.
 */
class E2eCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext ?: context
        val id = stringExtra(intent, E2eProtocol.EXTRA_ID)
        val cmd = stringExtra(intent, E2eProtocol.EXTRA_CMD)
        val exchange = E2eExchange(
            filesDir = app.filesDir,
            id = id,
            cmd = cmd,
            log = { line -> Log.i(E2eProtocol.LOG_TAG, line) },
            finish = { pending.finish() },
        )
        try {
            val request = E2eProtocol.parseRequest(
                id = id,
                cmd = cmd,
                json64 = stringExtra(intent, E2eProtocol.EXTRA_JSON64),
                json = stringExtra(intent, E2eProtocol.EXTRA_JSON),
                knownCommands = E2eCommandRunner.commands,
                decodeBase64 = { text -> Base64.decode(text, Base64.DEFAULT) },
            )
            exchange.armTimeout(E2eProtocol.timeoutMs(foreground = intent.flags and Intent.FLAG_RECEIVER_FOREGROUND != 0))
            E2eCommandRunner.run(app, request.cmd, request.args, exchange::complete)
        } catch (e: Throwable) {
            exchange.complete(E2eProtocol.failureOf(e))
        }
    }

    /** A string extra, or null when it is missing, not a string, or the extras cannot be read. */
    private fun stringExtra(intent: Intent, name: String): String? = try {
        intent.getStringExtra(name)
    } catch (e: RuntimeException) {
        null
    }
}
