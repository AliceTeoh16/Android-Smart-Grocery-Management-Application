package com.xmum.smartgrocery.ui.main

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.xmum.smartgrocery.R
import com.xmum.smartgrocery.SmartGroceryApp
import com.xmum.smartgrocery.data.local.entity.GroceryItem
import com.xmum.smartgrocery.databinding.ActivityMainBinding
import com.xmum.smartgrocery.ui.auth.LocalAuthSession
import com.xmum.smartgrocery.ui.budget.BudgetFragment
import com.xmum.smartgrocery.ui.history.PurchaseHistoryFragment
import com.xmum.smartgrocery.ui.inventory.InventoryFragment
import com.xmum.smartgrocery.ui.inventory.InventoryFilter
import com.xmum.smartgrocery.ui.pantrypal.PantryAction
import com.xmum.smartgrocery.ui.pantrypal.PantryEntry
import com.xmum.smartgrocery.ui.pantrypal.PantryPalFragment
import com.xmum.smartgrocery.ui.quickadd.QuickAddBottomSheetDialog
import com.xmum.smartgrocery.ui.recipes.RecipeFragment
import com.xmum.smartgrocery.ui.shopping.AddShoppingItemDialog
import com.xmum.smartgrocery.ui.shopping.ShoppingListFragment
import com.xmum.smartgrocery.utils.VoiceCommandReview
import com.xmum.smartgrocery.utils.VoiceRecognitionFailure
import com.xmum.smartgrocery.utils.VoiceLanguage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()
    private var activeNavigationItemId: Int = R.id.navigation_home
    private lateinit var legacyBackCallback: OnBackPressedCallback
    private var voiceDialog: BottomSheetDialog? = null
    private var speechRecognizer: SpeechRecognizer? = null
    internal var speechRecognizerFactory: (android.content.Context) -> SpeechRecognizer = { SpeechRecognizer.createSpeechRecognizer(it) }
    internal var useDeviceVoiceRecognition = false
    internal var voiceCaptureFactory: () -> VoiceAudioCapture = { MicrophoneVoiceCapture() }
    private val geminiVoiceTranscription by lazy { GeminiVoiceTranscription() }
    internal var transcribeVoiceClip: suspend (ByteArray, List<String>) -> VoiceTranscription = { audio, names ->
        geminiVoiceTranscription.transcribe(audio, names)
    }
    private var voiceCapture: VoiceAudioCapture? = null
    private var voiceTranscriptionJob: Job? = null
    private var voiceInventoryDestination: com.xmum.smartgrocery.data.local.dao.DataScope? = null
    private var voiceSessionShopping = false
    private var voiceSessionShoppingAssignee = "Household"
    private var voiceSessionLanguageTag = "en-US"
    private val voicePreferences by lazy { VoiceInputPreferences(this) }
    private var voiceItemNames: List<String> = emptyList()

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showVoiceInputSheet()
        } else {
            Log.w(TAG, "Voice permission denied; opening typed command fallback")
            showVoiceFallbackInputSheet("Microphone permission was not granted.")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val app = application as SmartGroceryApp
            com.xmum.smartgrocery.ui.common.RestockMonitor.install(this, app.database)?.refresh()
        }
        if (!granted) {
            Toast.makeText(this, "Notifications are disabled for Smart Grocery.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        activeNavigationItemId = savedInstanceState?.getInt(KEY_NAVIGATION) ?: R.id.navigation_home
        voiceSessionShopping = savedInstanceState?.getBoolean("voice_session_shopping") ?: false
        voiceSessionShoppingAssignee = savedInstanceState?.getString("voice_session_shopping_assignee")
            ?.takeIf { it in com.xmum.smartgrocery.utils.ShoppingVoiceMerge.assignees } ?: "Household"
        voiceSessionLanguageTag = VoiceLanguage.fromTag(savedInstanceState?.getString("voice_session_language")
            ?: voicePreferences.language.tag).tag
        legacyBackCallback = object : OnBackPressedCallback(activeNavigationItemId != R.id.navigation_home) {
            override fun handleOnBackPressed() {
                val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (currentFragment is ShoppingListFragment && currentFragment.popFeatureBackStack()) return
                showPantryFlow(PantryEntry.Home)
            }
        }
        onBackPressedDispatcher.addCallback(this, legacyBackCallback)
        setupBottomNavigation()
        if (savedInstanceState == null) {
            showPantryFlow(if (LocalAuthSession(this).currentProfile().signedIn) PantryEntry.Home else PantryEntry.Welcome)
        } else {
            updateNavigationChrome(activeNavigationItemId != R.id.navigation_home)
        }
        savedInstanceState?.getString("voice_inventory_user")?.let { actor ->
            voiceInventoryDestination = com.xmum.smartgrocery.data.local.dao.DataScope(actor, savedInstanceState.getString("voice_inventory_home"))
        }
        supportFragmentManager.setFragmentResultListener(com.xmum.smartgrocery.ui.quickadd.InventoryDestinationDialog.RESULT, this) { _, result ->
            val actor = result.getString("user") ?: return@setFragmentResultListener
            val target = com.xmum.smartgrocery.data.local.dao.DataScope(actor, result.getString("home"))
            lifecycleScope.launch {
                try {
                    (application as SmartGroceryApp).sessionManager.withInventoryDestination(target) { Unit }
                    voiceInventoryDestination = target
                    beginVoiceInput()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Toast.makeText(this@MainActivity, failure.message, Toast.LENGTH_LONG).show() }
            }
        }
        requestNotificationPermissionIfNeeded()
        observeViewModel()
        observeVoiceVocabulary()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    showPantryFlow(if (LocalAuthSession(this).currentProfile().signedIn) PantryEntry.Home else PantryEntry.Welcome)
                    true
                }
                R.id.navigation_inventory -> {
                    activeNavigationItemId = item.itemId
                    updateNavigationChrome(true)
                    replaceFragment(InventoryFragment())
                    true
                }
                R.id.navigation_recipes -> {
                    activeNavigationItemId = item.itemId
                    updateNavigationChrome(true)
                    replaceFragment(RecipeFragment.newInstance())
                    true
                }
                R.id.navigation_shopping -> {
                    activeNavigationItemId = item.itemId
                    updateNavigationChrome(true)
                    replaceFragment(ShoppingListFragment())
                    true
                }
                R.id.navigation_budget -> {
                    activeNavigationItemId = item.itemId
                    updateNavigationChrome(true)
                    replaceFragment(BudgetFragment())
                    true
                }
                else -> false
            }
        }
        binding.bottomNavigation.menu.findItem(activeNavigationItemId).isChecked = true
    }

    private fun updateNavigationChrome(showLegacyNavigation: Boolean) {
        binding.cardBottomNavContainer.visibility = if (showLegacyNavigation) View.VISIBLE else View.GONE
        legacyBackCallback.isEnabled = showLegacyNavigation
        window.statusBarColor = 0xFFF9F8F3.toInt()
        window.navigationBarColor = window.statusBarColor
    }

    /** Detail destinations fill the page; feature selection and all state owners stay intact. */
    fun setFeatureFullScreen(fullScreen: Boolean) {
        binding.cardBottomNavContainer.visibility = if (fullScreen || activeNavigationItemId == R.id.navigation_home) View.GONE else View.VISIBLE
        window.statusBarColor = 0xFFF9F8F3.toInt()
        window.navigationBarColor = window.statusBarColor
    }

    fun showPantryFlow(entry: PantryEntry = PantryEntry.Home) {
        activeNavigationItemId = R.id.navigation_home
        binding.bottomNavigation.menu.findItem(R.id.navigation_home).isChecked = true
        updateNavigationChrome(false)
        replaceFragment(PantryPalFragment.newInstance(entry))
    }

    /** Bridge the new dashboard to the existing fragments and their unchanged ViewModels. */
    fun openPantryAction(action: PantryAction) {
        val returnNavigationItemId = activeNavigationItemId
        val (navigationId, fragment) = when (action) {
            PantryAction.Inventory -> R.id.navigation_inventory to InventoryFragment()
            PantryAction.Expiring -> R.id.navigation_inventory to InventoryFragment.newInstance(InventoryFilter.EXPIRING_SOON)
            PantryAction.LowStock -> { com.xmum.smartgrocery.ui.inventory.RestockReviewActivity.open(this); return }
            PantryAction.Shopping -> R.id.navigation_shopping to ShoppingListFragment()
            PantryAction.Household -> R.id.navigation_shopping to ShoppingListFragment.newInstance(
                openHousehold = true,
                returnNavigationItemId = returnNavigationItemId.takeIf { it != R.id.navigation_shopping },
            )
            PantryAction.AiChecklist -> R.id.navigation_recipes to RecipeFragment.newInstance()
            PantryAction.Budget -> R.id.navigation_budget to BudgetFragment()
            PantryAction.PurchaseHistory -> R.id.navigation_inventory to PurchaseHistoryFragment()
            PantryAction.Profile, PantryAction.AllActivity -> return
        }
        activeNavigationItemId = navigationId
        binding.bottomNavigation.menu.findItem(navigationId).isChecked = true
        updateNavigationChrome(true)
        replaceFragment(fragment)
    }

    fun restoreNavigation(navigationItemId: Int) {
        when (navigationItemId) {
            R.id.navigation_home -> showPantryFlow(
                if (LocalAuthSession(this).currentProfile().signedIn) PantryEntry.Home else PantryEntry.Welcome
            )
            R.id.navigation_inventory -> {
                activeNavigationItemId = navigationItemId
                binding.bottomNavigation.menu.findItem(navigationItemId).isChecked = true
                updateNavigationChrome(true)
                replaceFragment(InventoryFragment())
            }
            R.id.navigation_recipes -> {
                activeNavigationItemId = navigationItemId
                binding.bottomNavigation.menu.findItem(navigationItemId).isChecked = true
                updateNavigationChrome(true)
                replaceFragment(RecipeFragment.newInstance())
            }
            R.id.navigation_shopping -> {
                activeNavigationItemId = navigationItemId
                binding.bottomNavigation.menu.findItem(navigationItemId).isChecked = true
                updateNavigationChrome(true)
                replaceFragment(ShoppingListFragment())
            }
            R.id.navigation_budget -> {
                activeNavigationItemId = navigationItemId
                binding.bottomNavigation.menu.findItem(navigationItemId).isChecked = true
                updateNavigationChrome(true)
                replaceFragment(BudgetFragment())
            }
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_in_300, R.anim.fade_out_300)
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.voiceResultEvent.collect { event ->
                    when (event) {
                        is VoiceResult.Success -> {
                            Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                        }
                        is VoiceResult.Error -> {
                            Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                        }
                        is VoiceResult.OpenQuickAdd -> {
                            if (activeNavigationItemId == R.id.navigation_shopping) {
                                showShoppingItemBottomSheet(event.itemName, event.quantity)
                            } else {
                                showQuickAddBottomSheet(event.itemName, event.quantity)
                            }
                        }
                        is VoiceResult.OpenRecipes -> {
                            openRecipeRecommendations(event.prompt)
                        }
                    }
                }
            }
        }
    }

    fun triggerVoiceInput() {
        if (voiceDialog?.isShowing == true || supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) != null) return
        if (supportFragmentManager.findFragmentByTag(com.xmum.smartgrocery.ui.quickadd.InventoryDestinationDialog.TAG) != null) return
        voiceSessionShopping = activeNavigationItemId == R.id.navigation_shopping
        voiceSessionShoppingAssignee = "Household"
        voiceInventoryDestination = null
        if (!voiceSessionShopping && activeNavigationItemId != R.id.navigation_recipes) {
            com.xmum.smartgrocery.ui.quickadd.InventoryDestinationDialog()
                .show(supportFragmentManager, com.xmum.smartgrocery.ui.quickadd.InventoryDestinationDialog.TAG)
            return
        }
        beginVoiceInput()
    }

    private fun beginVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            showVoiceInputSheet()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun observeVoiceVocabulary() {
        val app = application as SmartGroceryApp
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.groceryRepository.allGroceryItems, app.shoppingListRepository.shoppingListItems) { inventory, shopping ->
                    inventory.map { it.name } + shopping.map { it.itemName }
                }.collect { voiceItemNames = it }
            }
        }
    }

    private fun showVoiceInputSheet(languageTag: String = voicePreferences.language.tag) {
        voiceSessionLanguageTag = languageTag
        if (useDeviceVoiceRecognition) showDeviceVoiceInputSheet(languageTag) else showGeminiVoiceInputSheet()
    }

    private fun showGeminiVoiceInputSheet() {
        cleanupVoiceRecognition()
        val shopping = voiceSessionShopping
        val names = voiceItemNames.toList()
        val dialog = BottomSheetDialog(this)
        voiceDialog = dialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply { text = "Voice Input"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val status = TextView(this).apply {
            text = "Opening microphone…"; textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val mic = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic_24)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }
        val level = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            contentDescription = "Microphone input level"
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF315B40.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE6EFDE.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)).apply {
                topMargin = dp(16); bottomMargin = dp(8)
            }
        }
        var audioSeen = false
        val explanation = TextView(this).apply {
            text = "Auto language detection\nEnglish · Bahasa Melayu · 中文\n\nSpeak and check that the green level bar moves, then tap Done speaking (20 seconds max).\nAudio is sent to Gemini for transcription. You review every command before anything changes."
            textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(12))
        }
        val capture = voiceCaptureFactory()
        voiceCapture = capture
        val done = MaterialButton(this).apply {
            text = "Done speaking"; isEnabled = false
            setOnClickListener { isEnabled = false; capture.finish() }
        }
        val typeInstead = MaterialButton(this).apply {
            text = "Type Instead"
            setOnClickListener { dismissVoiceDialogThenShowFallback("Enter your command, then check the review.") }
        }
        val cancel = MaterialButton(this).apply { text = "Cancel"; setOnClickListener { dialog.dismiss() } }
        listOf(title, status, mic, level, explanation, done, typeInstead, cancel).forEach(content::addView)
        dialog.setOnDismissListener { cleanupVoiceRecognition() }
        showStyledVoiceSheet(dialog, content)
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val wav = capture.record(onReady = {
                    if (voiceDialog === dialog && dialog.isShowing) { status.text = "Listening…"; done.isEnabled = true }
                }, onLevel = { rms, elapsed ->
                    if (voiceDialog === dialog && dialog.isShowing) {
                        audioSeen = audioSeen || rms >= 0.0002f
                        val signal = if (audioSeen) "Microphone signal detected" else "No microphone signal yet"
                        val label = "Listening… ${(20 - elapsed / 1000).coerceAtLeast(0)}s remaining\n$signal"
                        if (status.text.toString() != label) status.text = label
                        level.progress = (rms * 1000).toInt().coerceIn(0, 100)
                        val scale = 1f + (rms * 4).coerceIn(0f, 0.3f)
                        mic.scaleX = scale; mic.scaleY = scale
                    }
                })
                if (voiceDialog !== dialog || !dialog.isShowing) return@launch
                voiceCapture = null
                done.isEnabled = false
                status.text = "Transcribing with Gemini…"
                pulseMic(mic, 1f)
                val result = transcribeVoiceClip(wav, names)
                if (voiceDialog !== dialog || !dialog.isShowing) return@launch
                require(result.transcript.isNotBlank()) { "No clear speech was recognized. Try again or type your command." }
                result.language?.let { voicePreferences.language = it; voiceSessionLanguageTag = it.tag }
                Log.d(TAG, "Gemini transcription ready language=${result.language?.tag ?: "unconfirmed"}")
                voiceTranscriptionJob = null
                dialog.dismiss()
                handleVoiceResults(listOf(result.transcript), shopping, languageUnconfirmed = result.language == null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (voiceDialog === dialog && dialog.isShowing) {
                    Log.w(TAG, "Gemini voice input failed: ${error.javaClass.simpleName}")
                    voiceTranscriptionJob = null
                    dismissVoiceDialogThenShowFallback(error.message?.take(250) ?: "Voice transcription failed. Try again or type your command.")
                }
            }
        }
        voiceTranscriptionJob = job
        job.start()
    }

    private fun showDeviceVoiceInputSheet(languageTag: String = voicePreferences.language.tag) {
        voiceSessionLanguageTag = languageTag
        if (!VoiceRecognitionRequest.supportsAutomaticDetection()) {
            showVoiceFallbackInputSheet(VoiceRecognitionFailure.AUTOMATIC_PLATFORM_UNAVAILABLE)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer is not available on this device/emulator")
            showVoiceFallbackInputSheet("Speech recognition is not available on this device.")
            return
        }

        cleanupVoiceRecognition()

        val dialog = BottomSheetDialog(this)
        voiceDialog = dialog

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(22))
        }

        val title = TextView(this).apply {
            text = "Voice Input"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }

        val status = TextView(this).apply {
            text = "Connecting..."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        }

        val mic = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic_24)
            setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.surface))
            setBackgroundResource(R.drawable.bg_invite_button)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }

        val heardText = TextView(this).apply {
            text = if (voiceSessionShopping) "Buy 2 bottles of milk\n买两瓶牛奶\nTambah dua botol susu"
                else "Add 2 eggs · Used 1 egg\n我买了两个鸡蛋\nTambah dua biji telur"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(12))
        }

        val languageView = TextView(this).apply {
            text = "Auto language detection\nEnglish · Bahasa Melayu · 中文"
            gravity = Gravity.CENTER
            setTextColor(0xFF315B40.toInt())
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        }
        val cancelButton = MaterialButton(this).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }
        val typeInsteadButton = MaterialButton(this).apply {
            text = "Type Instead"
            setOnClickListener {
                dismissVoiceDialogThenShowFallback("User selected text input fallback.")
            }
        }

        content.addView(title)
        content.addView(status)
        content.addView(mic)
        content.addView(heardText)
        content.addView(languageView)
        content.addView(typeInsteadButton)
        content.addView(cancelButton)

        dialog.setOnDismissListener { cleanupVoiceRecognition() }
        showStyledVoiceSheet(dialog, content)
        startInAppVoiceRecognition(languageTag, status, heardText, mic, languageView)
    }

    private fun startInAppVoiceRecognition(
        languageTag: String,
        statusView: TextView,
        heardTextView: TextView,
        micView: ImageView,
        languageView: TextView
    ) {
        voiceSessionLanguageTag = languageTag
        val previousRecognizer = speechRecognizer
        speechRecognizer = null
        previousRecognizer?.cancel()
        previousRecognizer?.destroy()

        val recognizer = speechRecognizerFactory(this)
        speechRecognizer = recognizer
        var detectedLanguage: VoiceLanguage? = null
        var languageDetectionReceived = false
        var readyForSpeech = false
        var speechStarted = false
        var peakRms: Float? = null
        val shopping = voiceSessionShopping

        val intent = VoiceRecognitionRequest.create(VoiceLanguage.fromTag(languageTag), voiceItemNames)

        Log.d(TAG, "Starting automatic SpeechRecognizer bootstrap=$languageTag languages=en,ms,zh")

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (speechRecognizer !== recognizer || voiceDialog?.isShowing != true) return
                readyForSpeech = true
                statusView.text = "Listening..."
                pulseMic(micView, 1.08f)
            }

            override fun onBeginningOfSpeech() {
                if (speechRecognizer !== recognizer || voiceDialog?.isShowing != true) return
                speechStarted = true
                statusView.text = "Capturing..."
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (speechRecognizer !== recognizer || voiceDialog?.isShowing != true) return
                if (rmsdB.isFinite()) peakRms = maxOf(peakRms ?: rmsdB, rmsdB)
                val scale = 1f + (rmsdB.coerceIn(0f, 8f) / 60f)
                micView.scaleX = scale
                micView.scaleY = scale
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (speechRecognizer !== recognizer || voiceDialog?.isShowing != true) return
                statusView.text = "Processing..."
                pulseMic(micView, 1f)
            }

            override fun onError(error: Int) {
                if (speechRecognizer !== recognizer || voiceDialog?.isShowing != true) return
                val errorName = speechErrorName(error)
                // Log service/audio activity without recording audio or the user's words.
                Log.w(TAG, "SpeechRecognizer failed error=$error name=$errorName ready=$readyForSpeech speechStarted=$speechStarted peakRms=$peakRms")
                statusView.text = "Voice unavailable"
                dismissVoiceDialogThenShowFallback(VoiceRecognitionFailure.automaticMessage(error),
                    showVoiceSetup = VoiceRecognitionFailure.needsSetup(error))
            }

            override fun onLanguageDetection(results: Bundle) {
                if (speechRecognizer !== recognizer || voiceDialog?.isShowing != true || Build.VERSION.SDK_INT < 34) return
                languageDetectionReceived = true
                val switchResult = results.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED)
                if (switchResult == SpeechRecognizer.LANGUAGE_SWITCH_RESULT_FAILED ||
                    switchResult == SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL) {
                    dismissVoiceDialogThenShowFallback(VoiceRecognitionFailure.AUTOMATIC_MODELS_UNAVAILABLE, showVoiceSetup = true)
                    return
                }
                val language = VoiceLanguage.detected(results.getString(SpeechRecognizer.DETECTED_LANGUAGE))
                val confidence = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
                    SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN)
                // Do not label a previous confident detection as the current language after a low-confidence change.
                detectedLanguage = language.takeIf { confidence >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT }
                languageView.text = detectedLanguage?.let { "Detected: ${it.label}" }
                    ?: "Detecting language…\nEnglish · Bahasa Melayu · 中文"
            }

            override fun onResults(results: Bundle?) {
                if (speechRecognizer !== recognizer || voiceDialog?.isShowing != true) return
                val spokenTexts = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                    .filter { it.isNotBlank() }
                if (spokenTexts.isEmpty()) {
                    Log.w(TAG, "SpeechRecognizer returned empty results")
                    dismissVoiceDialogThenShowFallback("No speech result was detected.")
                    return
                }
                val language = detectedLanguage
                Log.d(TAG, "SpeechRecognizer returned results count=${spokenTexts.size} languageCallback=$languageDetectionReceived languageConfirmed=${language != null}")
                if (language == null) {
                    if (!languageDetectionReceived) {
                        // Some services return usable speech but omit the optional language callback.
                        // Review the transcript without claiming detection or changing the remembered language.
                        voiceDialog?.dismiss()
                        handleVoiceResults(spokenTexts, shopping, languageUnconfirmed = true)
                        return
                    }
                    dismissVoiceDialogThenShowFallback(VoiceRecognitionFailure.AUTOMATIC_NOT_REPORTED,
                        showVoiceSetup = true, prefilledCommand = spokenTexts.first())
                    return
                }
                voicePreferences.language = language
                voiceSessionLanguageTag = language.tag
                voiceDialog?.dismiss()
                handleVoiceResults(spokenTexts, shopping)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (speechRecognizer !== recognizer || voiceDialog?.isShowing != true) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    heardTextView.text = partial
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        try {
            recognizer.startListening(intent)
        } catch (exception: Exception) {
            Log.e(TAG, "SpeechRecognizer startListening failed", exception)
            dismissVoiceDialogThenShowFallback("Voice could not start on this device.")
        }
    }

    private fun handleVoiceResults(spokenTexts: List<String>, shopping: Boolean = voiceSessionShopping, languageUnconfirmed: Boolean = false) {
        if (supportFragmentManager.isStateSaved ||
            supportFragmentManager.findFragmentByTag(VoiceCommandConfirmationDialog.TAG) != null) return
        val review = VoiceCommandReview.prepare(spokenTexts, shopping)
        VoiceCommandConfirmationDialog.newInstance(review.transcript, shopping, languageUnconfirmed, voiceSessionShoppingAssignee)
            .show(supportFragmentManager, VoiceCommandConfirmationDialog.TAG)
    }

    internal fun confirmVoiceCommand(transcript: String, shopping: Boolean, newInventoryItems: Map<Int, GroceryItem> = emptyMap(),
        shoppingAssignee: String = "Household") {
        val review = VoiceCommandReview.prepare(listOf(transcript), shopping)
        if (!review.canConfirm) return
        // Dispatch exactly the interpretation reviewed by the user, to its original destination.
        if (shopping) {
            viewModel.processShoppingVoiceCommandOptions(listOf(review.transcript), shoppingAssignee)
        } else {
            viewModel.processConfirmedInventoryVoiceCommand(review.transcript, newInventoryItems, voiceInventoryDestination)
        }
    }

    internal fun editVoiceCommand(transcript: String, shopping: Boolean, shoppingAssignee: String = "Household") {
        voiceSessionShopping = shopping
        voiceSessionShoppingAssignee = shoppingAssignee
        showVoiceFallbackInputSheet("Check the item names and quantities, then review again.", transcript)
    }

    private fun cleanupVoiceRecognition() {
        voiceTranscriptionJob?.cancel()
        voiceTranscriptionJob = null
        voiceCapture?.cancel()
        voiceCapture = null
        val recognizer = speechRecognizer
        speechRecognizer = null
        voiceDialog = null
        recognizer?.cancel()
        recognizer?.destroy()
    }

    private fun pulseMic(view: View, targetScale: Float) {
        ObjectAnimator.ofFloat(view, View.SCALE_X, view.scaleX, targetScale).apply {
            duration = 180L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(view, View.SCALE_Y, view.scaleY, targetScale).apply {
            duration = 180L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun showVoiceFallbackToast() {
        Toast.makeText(this, "You can type your command instead.", Toast.LENGTH_SHORT).show()
    }

    private fun dismissVoiceDialogThenShowFallback(reason: String, showVoiceSetup: Boolean = false, prefilledCommand: String = "") {
        val activeDialog = voiceDialog
        if (activeDialog?.isShowing == true) {
            activeDialog.setOnDismissListener {
                cleanupVoiceRecognition()
                showVoiceFallbackInputSheet(reason, prefilledCommand, showVoiceSetup = showVoiceSetup)
            }
            activeDialog.dismiss()
        } else {
            cleanupVoiceRecognition()
            showVoiceFallbackInputSheet(reason, prefilledCommand, showVoiceSetup = showVoiceSetup)
        }
    }

    private fun showVoiceFallbackInputSheet(reason: String, prefilledCommand: String = "", showVoiceSetup: Boolean = false) {
        if (prefilledCommand.isBlank()) showVoiceFallbackToast()
        val shopping = voiceSessionShopping

        val dialog = BottomSheetDialog(this)
        voiceDialog = dialog
        dialog.setOnDismissListener { cleanupVoiceRecognition() }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(22))
        }

        val title = TextView(this).apply {
            text = "Type Voice Command"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }

        val message = TextView(this).apply {
            text = "$reason You can type the same command here."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(12))
        }

        val commandInput = EditText(this).apply {
            hint = if (shopping) {
                "Example: buy 2 bottles of milk and 1 chicken wing"
            } else {
                "Example: add 2 eggs or used 1 egg"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            minLines = 2
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(prefilledCommand)
        }

        val submitButton = MaterialButton(this).apply {
            text = "Review command"
            setOnClickListener {
                val command = commandInput.text?.toString()?.trim().orEmpty()
                if (command.isBlank()) {
                    commandInput.error = "Enter a command"
                    return@setOnClickListener
                }
                isEnabled = false
                dialog.dismiss()
                handleVoiceResults(listOf(command), shopping)
            }
        }

        val cancelButton = MaterialButton(this).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }

        content.addView(title)
        content.addView(message)
        content.addView(commandInput)
        content.addView(submitButton)
        if (showVoiceSetup) {
            content.addView(MaterialButton(this).apply {
                text = "Voice settings"
                setOnClickListener {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
                    } catch (exception: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@MainActivity, "Open device Settings to set up your speech recognition language.", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
        if (!useDeviceVoiceRecognition || (VoiceRecognitionRequest.supportsAutomaticDetection() && SpeechRecognizer.isRecognitionAvailable(this))) {
            content.addView(MaterialButton(this).apply {
                text = "Try voice again"
                setOnClickListener {
                    isEnabled = false
                    // Finish the old sheet's cleanup before creating the next recognizer.
                    dialog.setOnDismissListener {
                        cleanupVoiceRecognition()
                        voiceSessionShopping = shopping
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            showVoiceInputSheet(voiceSessionLanguageTag)
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    dialog.dismiss()
                }
            })
        }
        if (!useDeviceVoiceRecognition && VoiceRecognitionRequest.supportsAutomaticDetection() && SpeechRecognizer.isRecognitionAvailable(this)) {
            content.addView(MaterialButton(this).apply {
                text = "Use device speech"
                setOnClickListener {
                    isEnabled = false
                    dialog.setOnDismissListener {
                        cleanupVoiceRecognition()
                        voiceSessionShopping = shopping
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            showDeviceVoiceInputSheet(voiceSessionLanguageTag)
                        } else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    dialog.dismiss()
                }
            })
        }
        content.addView(cancelButton)
        showStyledVoiceSheet(dialog, content)
    }

    private fun showStyledVoiceSheet(dialog: BottomSheetDialog, content: LinearLayout) {
        content.setPadding(dp(24), dp(24), dp(24), dp(100))
        content.setBackgroundResource(R.drawable.pantry_recipe_sheet_bg)
        fun style(view: View) {
            when (view) {
                is MaterialButton -> {
                    val primary = view.text == "Review command" || view.text == "Done speaking"
                    view.isAllCaps = false
                    view.cornerRadius = dp(14)
                    view.minHeight = dp(54)
                    view.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (primary) 0xFFC85A32.toInt() else 0xFFE6EFDE.toInt())
                    view.setTextColor(if (primary) android.graphics.Color.WHITE else 0xFF315B40.toInt())
                }
                is EditText -> {
                    view.setTextColor(0xFF24372B.toInt())
                    view.setHintTextColor(0xFF738075.toInt())
                    view.setBackgroundResource(R.drawable.pantry_share_option_bg)
                }
                is TextView -> view.setTextColor(0xFF315B40.toInt())
                is ImageView -> {
                    view.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFF315B40.toInt())
                    }
                    view.setColorFilter(android.graphics.Color.WHITE)
                }
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) style(view.getChildAt(index))
            }
        }
        style(content)
        val scroll = androidx.core.widget.NestedScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        dialog.setContentView(scroll)
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    private fun speechErrorName(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            else -> "ERROR_UNKNOWN_$error"
        }
    }

    fun showQuickAddBottomSheet(prefilledName: String? = null, prefilledQty: Int = 1) {
        val dialog = QuickAddBottomSheetDialog.newInstance(prefilledName, prefilledQty)
        dialog.show(supportFragmentManager, "QuickAddBottomSheet")
    }

    fun showShoppingItemBottomSheet(prefilledName: String? = null, prefilledQty: Int = 1) {
        val dialog = AddShoppingItemDialog.newInstance(prefilledName, prefilledQty)
        dialog.show(supportFragmentManager, "AddShoppingItemDialog")
    }

    fun showEditItemBottomSheet(item: GroceryItem) {
        val dialog = QuickAddBottomSheetDialog.newEditInstance(item)
        dialog.show(supportFragmentManager, "EditItemBottomSheet")
    }

    fun showAuthBottomSheet() {
        showPantryFlow(PantryEntry.Login)
    }

    fun showProfileSettings() {
        if (supportFragmentManager.findFragmentByTag(com.xmum.smartgrocery.ui.pantrypal.ProfileSettingsDialog.TAG) == null) {
            com.xmum.smartgrocery.ui.pantrypal.ProfileSettingsDialog()
                .show(supportFragmentManager, com.xmum.smartgrocery.ui.pantrypal.ProfileSettingsDialog.TAG)
        }
    }

    private fun openRecipeRecommendations(prompt: String) {
        binding.bottomNavigation.menu.findItem(R.id.navigation_recipes).isChecked = true
        activeNavigationItemId = R.id.navigation_recipes
        updateNavigationChrome(true)
        replaceFragment(RecipeFragment.newInstance(autoGenerate = true, voicePrompt = prompt))
        Toast.makeText(this, "Generating recipes from voice request.", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onStop() {
        if (voiceCapture != null || voiceTranscriptionJob != null) {
            val dialog = voiceDialog
            cleanupVoiceRecognition()
            dialog?.dismiss()
        }
        super.onStop()
    }

    override fun onDestroy() {
        cleanupVoiceRecognition()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        voiceInventoryDestination?.let { outState.putString("voice_inventory_user", it.userId); outState.putString("voice_inventory_home", it.householdId) }
        outState.putInt(KEY_NAVIGATION, activeNavigationItemId)
        outState.putBoolean("voice_session_shopping", voiceSessionShopping)
        outState.putString("voice_session_shopping_assignee", voiceSessionShoppingAssignee)
        outState.putString("voice_session_language", voiceSessionLanguageTag)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val TAG = "SmartGroceryVoice"
        const val KEY_NAVIGATION = "active_navigation_item"
    }
}
