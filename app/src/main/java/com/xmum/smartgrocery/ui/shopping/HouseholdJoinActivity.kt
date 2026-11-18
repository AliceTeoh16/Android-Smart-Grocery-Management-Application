package com.xmum.smartgrocery.ui.shopping

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.ui.main.MainActivity
import kotlinx.coroutines.launch

/** Invitation links confirm their server and household before granting membership. */
class HouseholdJoinActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.data?.getQueryParameter("code").orEmpty()
        if (!code.matches(Regex("[0-9]{6}"))) { finish(); return }
        val runtime = com.xmum.smartgrocery.data.sharing.SharingRuntime.forDatabase((application as SmartGroceryApp).database)
        val server = intent.data?.getQueryParameter("server")
        if (server != null && runtime != null && server != runtime.serverUrl.value) {
            val valid = runCatching { com.xmum.smartgrocery.data.sharing.SharingApi.normalizeUrl(server) }.getOrNull()
            if (valid == null) { Toast.makeText(this, "Invalid sharing server address.", Toast.LENGTH_LONG).show(); finish(); return }
            AlertDialog.Builder(this).setTitle("Use invitation's sharing server?")
                .setMessage("This invitation uses $valid. Connect your account to this server in Share & Connect, then open the invitation again.")
                .setPositiveButton("Use server") { _, _ -> runtime.configureServer(valid); openApp() }
                .setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        lifecycleScope.launch {
            val manager = (application as SmartGroceryApp).sessionManager
            if (manager.currentUserId() == null) {
                AlertDialog.Builder(this@HouseholdJoinActivity).setTitle("Sign in to join")
                    .setMessage("Sign in or create an account, then open this invitation link again.")
                    .setPositiveButton("Continue") { _, _ -> openApp() }.setOnCancelListener { finish() }.show()
            } else {
                AlertDialog.Builder(this@HouseholdJoinActivity).setTitle("Join household")
                    .setMessage("Join the shared household with invite code $code?")
                    .setPositiveButton("Join") { _, _ -> lifecycleScope.launch {
                        runCatching { manager.joinHousehold(code) }.fold(
                            onSuccess = { openApp() },
                            onFailure = { Toast.makeText(this@HouseholdJoinActivity, it.message, Toast.LENGTH_LONG).show(); finish() })
                    } }.setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            }
        }
    }

    private fun openApp() { startActivity(Intent(this, MainActivity::class.java)); finish() }
}
