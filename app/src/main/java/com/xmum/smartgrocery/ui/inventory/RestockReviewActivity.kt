package com.xmum.smartgrocery.ui.inventory

import com.xmum.smartgrocery.data.session.withInventoryDestination
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.dao.DataScope
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.ui.pantrypal.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Opening this screen or dismissing a notification never adds a shopping item. */
class RestockReviewActivity : AppCompatActivity() {
    private var items by mutableStateOf<List<GroceryItem>>(emptyList())
    private var name by mutableStateOf("Loading…")
    private var error by mutableStateOf<String?>(null)
    private var saving by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private lateinit var target: DataScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actor = intent.getStringExtra("user") ?: run { finish(); return }
        target = DataScope(actor, intent.getStringExtra("household"))
        val app = application as SmartGroceryApp
        lifecycleScope.launch {
            app.database.groceryDao().observeAccessibleItems().collect { available ->
                if (app.sessionManager.currentUserId() != target.userId) {
                    items = emptyList(); error = "Account changed. Reopen low-stock review."; return@collect
                }
                val home = target.householdId?.let { id -> app.sessionManager.households().firstOrNull { it.householdId == id } }
                if (target.householdId != null && home == null) {
                    items = emptyList(); error = "You no longer belong to this household."; return@collect
                }
                name = home?.name ?: "Personal (private)"
                items = available.filter { it.householdId == target.householdId &&
                    (it.householdId != null || it.userId == target.userId) && it.quantity <= it.threshold }
                loaded = true
            }
        }
        setContentView(ComposeView(this).apply {
            setContent {
                PantryPalTheme {
                    Surface(color = PantryColors.Cream) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                            .verticalScroll(rememberScrollState()).padding(24.dp)) {
                            FeatureHeader("Review restock", { if (!saving) finish() }, null)
                            Text(name, color = PantryColors.Green)
                            Text("These items are low in stock. Choose what to add to your shopping list. Nothing is added until you approve.",
                                Modifier.padding(vertical = 16.dp), color = PantryColors.Muted)
                            val choices = remember { mutableStateMapOf<Int, Int>() }
                            items.forEach { item -> key(item.id) {
                                FeatureCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = choices.containsKey(item.id), enabled = !saving,
                                            onCheckedChange = { checked -> if (checked) choices[item.id] =
                                                (item.threshold.toLong() + 1 - item.quantity).coerceIn(1, 9999).toInt()
                                                else choices.remove(item.id) })
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name)
                                            Text("${item.quantity} left · low-stock threshold ${item.threshold}", color = PantryColors.Muted)
                                        }
                                    }
                                    choices[item.id]?.let { quantity ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Restock quantity", Modifier.weight(1f))
                                            TextButton(onClick = { choices[item.id] = quantity - 1 }, enabled = !saving && quantity > 1) { Text("−") }
                                            Text(quantity.toString())
                                            TextButton(onClick = { choices[item.id] = quantity + 1 }, enabled = !saving && quantity < 9999) { Text("+") }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            } }
                            if (loaded && items.isEmpty()) Text("No low-stock items in this context.")
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            val selected = choices.filterKeys { id -> items.any { it.id == id } }
                            Button(onClick = { approve(selected) }, enabled = !saving && selected.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PantryColors.Orange)) {
                                Text(if (saving) "Adding…" else "Add selected to shopping list")
                            }
                            TextButton(onClick = { finish() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                        }
                    }
                }
            }
        })
    }

    private fun approve(quantities: Map<Int, Int>) {
        if (saving) return
        saving = true
        lifecycleScope.launch {
            try {
                val app = application as SmartGroceryApp
                val added = app.sessionManager.withInventoryDestination(target) {
                    app.database.shoppingListDao().approveRestocks(quantities)
                }
                Toast.makeText(this@RestockReviewActivity,
                    if (added > 0) "$added item(s) added to $name shopping list"
                    else "Already planned or no longer low in stock. Nothing added.", Toast.LENGTH_LONG).show()
                finish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not save. Please try again." }
            finally { saving = false }
        }
    }

    companion object {
        fun intent(context: Context, target: DataScope) = Intent(context, RestockReviewActivity::class.java)
            .putExtra("user", target.userId).putExtra("household", target.householdId)
            .setData(Uri.Builder().scheme("smartgrocery").authority("restock")
                .appendPath(target.userId).appendPath(target.key).build())

        fun open(activity: AppCompatActivity) {
            activity.lifecycleScope.launch {
                val session = (activity.application as SmartGroceryApp).sessionManager.currentSession()
                val actor = session?.userId ?: return@launch
                activity.startActivity(intent(activity, DataScope(actor, session.householdId)))
            }
        }
    }
}
