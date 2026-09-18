package com.brits.buyandsell

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.app.AlertDialog
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.ContentValues
import android.content.ContentResolver
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.widget.TextView
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.util.Locale

class GoogleSyncRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val clientId = intent.getLongExtra("client_id", -1L)
        if (clientId <= 0L) return
        val launch = Intent(context, MainActivity::class.java).apply {
            action = "com.brits.buyandsell.RETRY_GOOGLE_SYNC"
            putExtra("client_id", clientId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(launch)
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var firstNameField: EditText
    private lateinit var surnameField: EditText
    private lateinit var mainPhoneField: EditText
    private lateinit var additionalPhoneField: EditText
        private lateinit var resultsContainer: LinearLayout
    private lateinit var resultsScroll: ScrollView
    private lateinit var listToggleButton: Button
    private lateinit var contactReferenceToggleButton: Button
    private lateinit var statusView: TextView
    private lateinit var matchingContactsLabel: TextView
    private lateinit var clearButton: Button
    private lateinit var undoButton: Button
    private lateinit var capturePhoneSection: LinearLayout
    private lateinit var captureSaveButton: Button
    private lateinit var additionalPhoneLabelSpinner: Spinner
    private var customMainPhoneLabel: String = ""
    private var customAdditionalPhoneLabel: String = ""
    // External phone/Google contact changes detected during this app session.
    private val ignoredExternalPhoneKeys = mutableSetOf<String>()
    private lateinit var mainPhoneLabelButton: Button
    private var updatingAdditionalPhoneLabelSpinner = false
    private var lastAdditionalPhoneLabelIndex = 0
    private val captureAdditionalPhones = mutableListOf<EditablePhone>()

    private val handler = Handler(Looper.getMainLooper())
    private var allContacts: List<PhoneContact> = emptyList()
    private var selectedContact: PhoneContact? = null
    private var expandedContactId: Long? = null
    private var contactAreaHidden = false
    private var selectedReferenceHidden = false
    private var suppressSearchRefresh = false
    private var cachedContactMatches: List<PhoneContact> = emptyList()
    private var pendingSelectedContactId: Long? = null
    private var undoSnapshot: CaptureSnapshot? = null
    private var contactsPermissionRequested = false
    private var writeContactsPermissionRequested = false
    private var captureDraftRestored = false
    private lateinit var clientDb: ClientDb
    private var googleAccessToken: String? = null
    private var editUndoStack = mutableListOf<ClientEditState>()
    private var editWorkingName = ""
    private var editWorkingSurname = ""
    private var editWorkingPhones = mutableListOf<EditablePhone>()
    // Immutable state captured when Edit opens. CANCEL restores this state and stays on Edit.
    private var editInitialName = ""
    private var editInitialSurname = ""
    private var editInitialPhones: List<EditablePhone> = emptyList()
    private var editOriginalContact: PhoneContact? = null
    // Active Edit fields are retained so history snapshots capture the latest text even
    // when the user changes focus directly from one field to another.
    private var activeEditNameField: EditText? = null
    private var activeEditSurnameField: EditText? = null
    private var pendingGoogleAuthorizationCallback: ((String) -> Unit)? = null
    private var pendingRetryClientId: Long? = null

    private val contactPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                loadPhoneContacts()
            } else {
                showStatus("Phone Contacts permission is needed to find existing clients.")
            }
        }

    private val authorizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                try {
                    val authResult = Identity.getAuthorizationClient(this)
                        .getAuthorizationResultFromIntent(result.data)
                    if (authResult.accessToken != null) {
                        val token = authResult.accessToken!!
                        googleAccessToken = token
                        showStatus("Google Contacts access granted.")
                        pendingGoogleAuthorizationCallback?.let { callback ->
                            pendingGoogleAuthorizationCallback = null
                            callback(token)
                        }
                    } else {
                        pendingGoogleAuthorizationCallback = null
                        showStatus("Google authorization completed without an access token.")
                    }
                } catch (e: Exception) {
                    showStatus("Google authorization error: ${e.message}")
                }
            }
        }

    private val searchRunnable = Runnable { loadPhoneContacts() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clientDb = ClientDb(this)
        buildCaptureScreen()
        restoreInstanceState(savedInstanceState)
        requestPhoneContactsPermission()
        if (intent?.action == "com.brits.buyandsell.RETRY_GOOGLE_SYNC") {
            val retryId = intent?.getLongExtra("client_id", -1L) ?: -1L
            if (retryId > 0L) {
                pendingRetryClientId = retryId
                window.decorView.post { retryPendingGoogleSyncIfNeeded() }
            }
        }
        // V12.04 one-time cleanup: discard any unfinished draft left by the
        // previously faulty V12.01 test builds. Genuine V12.04 drafts are
        // subsequently restored normally.
        clearLegacyV1202CaptureDraftOnce()
        restoreCaptureDraftIfPresent()
        // Google Contacts authorization is deliberately requested on demand when a
        // Google operation is actually needed. Access tokens are short-lived, so
        // authorizing only at app startup can leave SAVE / UPDATE holding an expired token.
    }

    override fun onDestroy() {
        clientDb.close()
        handler.removeCallbacks(searchRunnable)
        super.onDestroy()
    }

    private fun buildCaptureScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 28)
        }

        val outer = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        val title = TextView(this).apply {
            text = "CLIENT CAPTURE — V12.07"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            maxLines = 2
            includeFontPadding = true
        }
        root.addView(title, matchWrap())

        addSpace(root, 18)
        root.addView(label("Client name"))
        firstNameField = field("First name")
        val nameRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(firstNameField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(nameRow, matchWrap())

        surnameField = field("Surname (optional)")
        root.addView(surnameField, matchWrap())

        addSpace(root, 6)
        val controlRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        listToggleButton = makeButton("HIDE LIST")
        listToggleButton.visibility = View.GONE
        listToggleButton.setOnClickListener {
            // The button label represents the action available to the user.
            // In selected-contact mode, contactAreaHidden is false while the
            // button intentionally says RETRIEVE LIST, so using contactAreaHidden
            // alone caused the first tap to hide the list instead of retrieving it.
            val retrieveRequested = listToggleButton.text.toString() == "RETRIEVE LIST"
            if (retrieveRequested) {
                contactAreaHidden = false
                listToggleButton.text = "HIDE LIST"
                clearButton.visibility = View.VISIBLE
                if (selectedContact != null) {
                    // A selected client always remains in existing-client mode.
                    // Retrieving the list must never expose new-number Capture fields.
                    setExistingClientCaptureMode(true)
                    renderResultsFromCacheWithSelectedContact()
                } else {
                    matchingContactsLabel.visibility = View.VISIBLE
                    resultsScroll.visibility = View.VISIBLE
                }
            } else {
                contactAreaHidden = true
                listToggleButton.text = "RETRIEVE LIST"
                resultsScroll.visibility = View.GONE
                matchingContactsLabel.visibility = View.GONE
                // Hiding the matching list must not change the selected-client mode.
                // If a client is selected, Capture phone fields and SAVE stay hidden;
                // number maintenance belongs in Edit. Only an unselected draft may show them.
                setExistingClientCaptureMode(selectedContact != null)
            }
        }
        controlRow.addView(listToggleButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        contactReferenceToggleButton = makeButton("RETRIEVE CONTACT")
        contactReferenceToggleButton.visibility = View.GONE
        contactReferenceToggleButton.setOnClickListener {
            selectedReferenceHidden = false
            contactReferenceToggleButton.visibility = View.GONE
            if (selectedContact != null && !contactAreaHidden) renderSelectedOnlyResult()
        }
        controlRow.addView(contactReferenceToggleButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controlRow, matchWrap())

        addSpace(root, 8)
        matchingContactsLabel = label("Matching phone Contacts")
        matchingContactsLabel.visibility = View.GONE
        root.addView(matchingContactsLabel)
        resultsScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isFillViewport = false
            visibility = View.GONE
            // Do not trap the outer page scroll when the user's finger starts on a card.
            isNestedScrollingEnabled = false
        }
        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        resultsScroll.addView(resultsContainer)
        root.addView(resultsScroll)

        capturePhoneSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(capturePhoneSection, matchWrap())
        addSpace(capturePhoneSection, 8)
        capturePhoneSection.addView(label("Telephone"))
        val mainRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        mainPhoneField = phoneField("At least 1 digit")
        mainPhoneLabelButton = makeButton("New")
        mainPhoneLabelButton.setOnClickListener {
            choosePhoneLabel(if (customMainPhoneLabel.isNotBlank()) customMainPhoneLabel else "New") { chosen ->
                customMainPhoneLabel = if (chosen !in listOf("Custom", "Cell", "Work", "Home", "New")) chosen else ""
                mainPhoneLabelButton.text = chosen
                saveCaptureDraft()
            }
        }
        val mainDeleteButton = Button(this).apply {
            text = "🗑"; minWidth = 0
            setOnClickListener { mainPhoneField.setText(""); saveCaptureDraft() }
        }
        mainRow.addView(mainPhoneField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.48f))
        mainRow.addView(mainPhoneLabelButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
        mainRow.addView(mainDeleteButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.10f))
        capturePhoneSection.addView(mainRow, matchWrap())

        val additionalRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        additionalPhoneField = phoneField("At least 1 digit")
        additionalRow.addView(additionalPhoneField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.54f))
        additionalPhoneLabelSpinner = Spinner(this)
        updatingAdditionalPhoneLabelSpinner = true
        additionalPhoneLabelSpinner.adapter = buildAdditionalPhoneLabelAdapter()
        additionalPhoneLabelSpinner.setSelection(0)
        additionalPhoneLabelSpinner.post { updatingAdditionalPhoneLabelSpinner = false }
        additionalPhoneLabelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit

            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingAdditionalPhoneLabelSpinner) return
                val value = parent?.getItemAtPosition(position)?.toString().orEmpty()
                if (value.equals("Custom", true)) {
                    parent?.post { showCustomAdditionalPhoneLabelDialog() }
                } else {
                    lastAdditionalPhoneLabelIndex = position
                    saveCaptureDraft()
                }
            }
        }
        additionalRow.addView(additionalPhoneLabelSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.36f))
        val additionalDeleteButton = Button(this).apply {
            text = "🗑"; minWidth = 0
            setOnClickListener { additionalPhoneField.setText(""); saveCaptureDraft() }
        }
        additionalRow.addView(additionalDeleteButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.10f))
        capturePhoneSection.addView(additionalRow, matchWrap())
        val captureExtrasContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        capturePhoneSection.addView(captureExtrasContainer, matchWrap())
        fun renderCaptureExtras() {
            captureExtrasContainer.removeAllViews()
            captureAdditionalPhones.forEachIndexed { index, ep ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val number = phoneField("At least 1 digit")
                number.setText(ep.number)
                number.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) { ep.number = s?.toString().orEmpty(); saveCaptureDraft() }
                    override fun afterTextChanged(e: Editable?) = Unit
                })
                val labelButton = makeButton(ep.label.ifBlank { "New" })
                labelButton.setOnClickListener {
                    choosePhoneLabel(ep.label) { newLabel -> ep.label = newLabel; labelButton.text = newLabel; saveCaptureDraft() }
                }
                val delete = Button(this).apply {
                    text = "🗑"; minWidth = 0
                    setOnClickListener { captureAdditionalPhones.removeAt(index); renderCaptureExtras(); saveCaptureDraft() }
                }
                row.addView(number, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
                row.addView(labelButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.32f))
                row.addView(delete, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.10f))
                captureExtrasContainer.addView(row, matchWrap())
            }
            val add = makeButton("+ NEW NUMBER")
            add.setOnClickListener { captureAdditionalPhones.add(EditablePhone("", "New")); renderCaptureExtras(); saveCaptureDraft() }
            captureExtrasContainer.addView(add, matchWrap())
        }
        renderCaptureExtras()

        addSpace(root, 14)
        clearButton = makeButton("CLEAR")
        clearButton.visibility = View.VISIBLE
        root.addView(clearButton, matchWrap())
        clearButton.setOnClickListener { clearSelectedContact() }

        undoButton = makeButton("↶ UNDO")
        undoButton.visibility = View.GONE
        root.addView(undoButton, matchWrap())
        undoButton.setOnClickListener { undoClear() }

        addSpace(root, 10)
        captureSaveButton = makeButton("SAVE / UPDATE")
        root.addView(captureSaveButton, matchWrap())
        captureSaveButton.setOnClickListener { saveOrUpdate() }

        addSpace(root, 14)
        statusView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            text = "Searching phone Contacts automatically..."
        }
        root.addView(statusView, matchWrap())

        setContentView(outer)
        installSearchWatchers()
    }

    private fun buildAdditionalPhoneLabelAdapter(): ArrayAdapter<String> {
        val labels = mutableListOf("New", "Custom", "Cell", "Work", "Home")
        if (customAdditionalPhoneLabel.isNotBlank() && customAdditionalPhoneLabel !in labels) labels.add(customAdditionalPhoneLabel)
        return ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private var customLabelDialogOpen = false

    private fun showCustomAdditionalPhoneLabelDialog() {
        if (customLabelDialogOpen) return
        customLabelDialogOpen = true

        val input = EditText(this).apply {
            hint = "e.g. WIFE"
            setSingleLine(true)
            setText(customAdditionalPhoneLabel)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Create custom field")
            .setMessage("What would you like to call this number?")
            .setView(container)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CREATE", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                customLabelDialogOpen = false
                dialog.dismiss()
                restoreAdditionalPhoneLabelSpinnerSelection()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = input.text.toString().trim()
                if (label.isBlank()) {
                    showStatus("Please enter a custom label.")
                    return@setOnClickListener
                }
                customAdditionalPhoneLabel = label
                customLabelDialogOpen = false
                dialog.dismiss()
                updatingAdditionalPhoneLabelSpinner = true
                additionalPhoneLabelSpinner.adapter = buildAdditionalPhoneLabelAdapter()
                val customIndex = additionalPhoneLabelSpinner.count - 1
                additionalPhoneLabelSpinner.setSelection(customIndex)
                lastAdditionalPhoneLabelIndex = customIndex
                additionalPhoneLabelSpinner.post { updatingAdditionalPhoneLabelSpinner = false }
                saveCaptureDraft()
                showStatus("Custom label set to: $label")
            }
        }
        dialog.setOnCancelListener {
            customLabelDialogOpen = false
            restoreAdditionalPhoneLabelSpinnerSelection()
        }
        dialog.show()
    }

    private fun restoreAdditionalPhoneLabelSpinnerSelection() {
        updatingAdditionalPhoneLabelSpinner = true
        additionalPhoneLabelSpinner.adapter = buildAdditionalPhoneLabelAdapter()
        val desired = if (customAdditionalPhoneLabel.isNotBlank()) customAdditionalPhoneLabel else "New"
        val labels = (0 until additionalPhoneLabelSpinner.count).map { additionalPhoneLabelSpinner.getItemAtPosition(it).toString() }
        val index = labels.indexOfFirst { it.equals(desired, true) }.takeIf { it >= 0 } ?: 0
        additionalPhoneLabelSpinner.setSelection(index)
        lastAdditionalPhoneLabelIndex = index
        additionalPhoneLabelSpinner.post { updatingAdditionalPhoneLabelSpinner = false }
    }

    private fun installSearchWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                saveCaptureDraft()
                if (suppressSearchRefresh) return
                if (undoSnapshot != null && (firstNameField.text.toString().isNotBlank() || surnameField.text.toString().isNotBlank())) {
                    undoSnapshot = null
                    undoButton.visibility = View.GONE
                }
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, 250)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        firstNameField.addTextChangedListener(watcher)
        surnameField.addTextChangedListener(watcher)
        mainPhoneField.addTextChangedListener(watcher)
        additionalPhoneField.addTextChangedListener(watcher)
    }

    private fun saveCaptureDraft() {
        if (!::firstNameField.isInitialized) return
        val prefs = getSharedPreferences("capture_draft", MODE_PRIVATE)
        val hasContent = firstNameField.text.toString().isNotBlank() || surnameField.text.toString().isNotBlank() ||
            mainPhoneField.text.toString().isNotBlank() || additionalPhoneField.text.toString().isNotBlank() ||
            captureAdditionalPhones.isNotEmpty() || selectedContact != null
        if (!hasContent) {
            prefs.edit().clear().apply()
            return
        }
        prefs.edit().apply {
            putString("name", firstNameField.text.toString())
            putString("surname", surnameField.text.toString())
            putString("main", mainPhoneField.text.toString())
            putString("mainLabel", mainPhoneLabelButton.text.toString())
            putString("additional", additionalPhoneField.text.toString())
            putString("additionalLabel", customAdditionalPhoneLabel)
            putInt("additionalLabelIndex", lastAdditionalPhoneLabelIndex)
            putString("extraPhones", JSONArray().apply { captureAdditionalPhones.forEach { put(JSONObject().apply { put("number", it.number); put("label", it.label) }) } }.toString())
            apply()
        }
    }

    private fun clearCaptureDraft() {
        getSharedPreferences("capture_draft", MODE_PRIVATE).edit().clear().apply()
    }

    private fun clearLegacyV1202CaptureDraftOnce() {
        val migrationPrefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
        if (migrationPrefs.getBoolean("v1203_startup_cleanup_applied", false)) return
        getSharedPreferences("capture_draft", MODE_PRIVATE).edit().clear().apply()
        migrationPrefs.edit().putBoolean("v1203_startup_cleanup_applied", true).apply()
    }

    private fun restoreCaptureDraftIfPresent() {
        if (captureDraftRestored) return
        captureDraftRestored = true
        val prefs = getSharedPreferences("capture_draft", MODE_PRIVATE)
        val name = prefs.getString("name", "").orEmpty()
        val surname = prefs.getString("surname", "").orEmpty()
        val main = prefs.getString("main", "").orEmpty()
        val additional = prefs.getString("additional", "").orEmpty()
        val extraJson = prefs.getString("extraPhones", "[]").orEmpty()
        if (name.isBlank() && surname.isBlank() && main.isBlank() && additional.isBlank() && extraJson == "[]") return
        suppressSearchRefresh = true
        firstNameField.setText(name)
        surnameField.setText(surname)
        mainPhoneField.setText(main)
        customMainPhoneLabel = prefs.getString("mainLabel", "New").orEmpty().let { if (it !in listOf("New", "Custom", "Cell", "Work", "Home")) it else "" }
        mainPhoneLabelButton.text = prefs.getString("mainLabel", "New").orEmpty().ifBlank { "New" }
        additionalPhoneField.setText(additional)
        captureAdditionalPhones.clear()
        try {
            val a = JSONArray(extraJson)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                captureAdditionalPhones.add(EditablePhone(o.optString("number"), o.optString("label").ifBlank { "New" }))
            }
        } catch (_: Exception) { }
        customAdditionalPhoneLabel = prefs.getString("additionalLabel", "").orEmpty()
        lastAdditionalPhoneLabelIndex = prefs.getInt("additionalLabelIndex", 0)
        updatingAdditionalPhoneLabelSpinner = true
        additionalPhoneLabelSpinner.adapter = buildAdditionalPhoneLabelAdapter()
        additionalPhoneLabelSpinner.setSelection(lastAdditionalPhoneLabelIndex.coerceIn(0, 3))
        additionalPhoneLabelSpinner.post { updatingAdditionalPhoneLabelSpinner = false }
        suppressSearchRefresh = false
        clearButton.visibility = View.VISIBLE
        showStatus("Unfinished Capture restored. Press SAVE / UPDATE to keep it, or CLEAR to discard it.")
    }

    private fun requestPhoneContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            loadPhoneContacts()
        } else if (!contactsPermissionRequested) {
            contactsPermissionRequested = true
            contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun loadPhoneContacts() {
        Thread {
            val list = mutableListOf<PhoneContact>()
            val resolver = contentResolver
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            )
            val map = linkedMapOf<Long, MutableList<PhoneEntry>>()
            val names = mutableMapOf<Long, String>()
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    names[id] = cursor.getString(nameIndex) ?: ""
                    val number = cursor.getString(numberIndex)?.trim().orEmpty()
                    if (number.isNotEmpty()) {
                        val type = cursor.getInt(typeIndex)
                        val rawLabel = cursor.getString(labelIndex).orEmpty()
                        val humanLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, type, rawLabel).toString()
                        map.getOrPut(id) { mutableListOf() }.add(PhoneEntry(number, humanLabel))
                    }
                }
            }

            val structured = mutableMapOf<Long, Pair<String, String>>()
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME),
                ContactsContract.Data.MIMETYPE + "=?",
                arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE), null
            )?.use { cursor ->
                val idI = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                val givenI = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)
                val familyI = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idI)
                    val given = cursor.getString(givenI).orEmpty().trim()
                    val family = cursor.getString(familyI).orEmpty().trim()
                    if (given.isNotBlank() || family.isNotBlank()) structured[id] = given to family
                }
            }

            val notes = mutableMapOf<Long, String>()
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Note.NOTE),
                ContactsContract.Data.MIMETYPE + "=?",
                arrayOf(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE), null
            )?.use { cursor ->
                val idI = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
                val noteI = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Note.NOTE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idI)
                    val note = cursor.getString(noteI).orEmpty().trim()
                    if (note.isNotBlank()) notes[id] = note
                }
            }

            names.forEach { (id, name) ->
                val entries = map[id].orEmpty().distinctBy { normalizePhone(it.number) to it.label }
                val pair = structured[id] ?: ("" to "")
                list.add(PhoneContact(id, name, pair.first, pair.second, notes[id].orEmpty(), entries))
            }
            allContacts = list
            runOnUiThread {
                if (pendingSelectedContactId != null) {
                    val restored = allContacts.firstOrNull { it.id == pendingSelectedContactId }
                    if (restored != null) {
                        selectedContact = restored
                        expandedContactId = restored.id
                        contactReferenceToggleButton.visibility = if (selectedReferenceHidden) View.VISIBLE else View.GONE
                        clearButton.visibility = View.VISIBLE
                        if (selectedReferenceHidden) { resultsScroll.visibility = View.GONE; matchingContactsLabel.visibility = View.GONE }
                        else if (!contactAreaHidden) renderSelectedOnlyResult()
                        listToggleButton.text = if (contactAreaHidden) "RETRIEVE LIST" else "RETRIEVE LIST"
                        pendingSelectedContactId = null
                        return@runOnUiThread
                    }
                }
                refreshContactResults()
            }
        }.start()
    }

    private fun refreshContactResults() {
        if (isFinishing) return
        val first = firstNameField.text.toString().trim()
        val surname = surnameField.text.toString().trim()
        val query = "$first $surname".trim()
        if (query.isBlank() || allContacts.isEmpty()) {
            resultsContainer.removeAllViews()
            resultsScroll.visibility = View.GONE
            matchingContactsLabel.visibility = View.GONE
            listToggleButton.visibility = View.GONE
            if (selectedContact == null) clearButton.visibility = View.GONE
            return
        }

        val q = normalize(query)
        val firstNorm = normalize(first)
        val surnameNorm = normalize(surname)
        val matches = allContacts.asSequence()
            .filter { contact ->
                if (selectedContact != null) contact.id == selectedContact!!.id
                else {
                    val name = normalize(contact.displayName)
                    name.contains(q) || (firstNorm.isNotBlank() && name.contains(firstNorm))
                }
            }
            .sortedWith(compareByDescending<PhoneContact> { exactScore(it, firstNorm, surnameNorm) }
                .thenBy { normalize(it.displayName) })
            .take(10)
            .toList()

        if (selectedContact == null) cachedContactMatches = matches
        resultsContainer.removeAllViews()
        if (matches.isEmpty()) {
            resultsScroll.visibility = View.GONE
            matchingContactsLabel.visibility = View.GONE
            listToggleButton.visibility = View.GONE
            if (selectedContact == null) clearButton.visibility = View.GONE
            return
        }
        listToggleButton.visibility = View.VISIBLE
        clearButton.visibility = View.VISIBLE
        matchingContactsLabel.visibility = if (contactAreaHidden || selectedReferenceHidden) View.GONE else View.VISIBLE
        resultsScroll.visibility = if (contactAreaHidden || selectedReferenceHidden) View.GONE else View.VISIBLE
        matches.forEach { addContactRow(it, it.id == expandedContactId) }
        if (selectedContact == null) {
            listToggleButton.text = if (contactAreaHidden) "RETRIEVE LIST" else "HIDE LIST"
            if (!contactAreaHidden) showStatus("${matches.size} matching Contacts available — select one deliberately.")
        }
    }

    private fun renderSelectedOnlyResult() {
        resultsContainer.removeAllViews()
        val contact = selectedContact ?: return
        if (selectedReferenceHidden || contactAreaHidden) {
            resultsScroll.visibility = View.GONE
            matchingContactsLabel.visibility = View.GONE
            return
        }
        expandedContactId = contact.id
        resultsScroll.visibility = View.VISIBLE
        matchingContactsLabel.visibility = View.VISIBLE
        addContactRow(contact, true)
        listToggleButton.text = "RETRIEVE LIST"
        listToggleButton.visibility = View.VISIBLE
    }

    private fun renderResultsFromCacheWithSelectedContact() {
        resultsContainer.removeAllViews()
        if (selectedReferenceHidden || contactAreaHidden) {
            resultsScroll.visibility = View.GONE
            matchingContactsLabel.visibility = View.GONE
            return
        }
        val selected = selectedContact
        val items = cachedContactMatches.ifEmpty { selected?.let { listOf(it) } ?: emptyList() }
        if (items.isEmpty()) { resultsScroll.visibility = View.GONE; matchingContactsLabel.visibility = View.GONE; listToggleButton.visibility = View.GONE; return }
        resultsScroll.visibility = View.VISIBLE
        matchingContactsLabel.visibility = View.VISIBLE
        listToggleButton.visibility = View.VISIBLE
        items.forEach { addContactRow(it, it.id == selected?.id || it.id == expandedContactId) }
        listToggleButton.text = "HIDE LIST"
    }

    private fun exactScore(c: PhoneContact, first: String, surname: String): Int {
        val name = normalize(c.displayName)
        if (first.isNotBlank() && surname.isNotBlank()) {
            val exact = normalize("$first $surname")
            if (name == exact) return 1000
            if (name.startsWith(exact)) return 900
        }
        if (first.isNotBlank() && name == first) return 800
        if (first.isNotBlank() && name.startsWith(first)) return 700
        if (first.isNotBlank() && name.contains(first)) return 500
        return 0
    }

    private fun contactForDisplay(contact: PhoneContact): PhoneContact {
        val local = clientDb.getClientByPhoneContactId(contact.id.toString()) ?: return contact
        val localPhones = clientDb.getPhonesJsonByPhoneContactId(contact.id.toString())
        val parsedPhones = try {
            val a = JSONArray(localPhones)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                PhoneEntry(o.optString("number"), o.optString("label").ifBlank { "New" })
            }
        } catch (_: Exception) { emptyList() }
        val phones = if (parsedPhones.isNotEmpty() || localPhones == "[]") {
            if (parsedPhones.isNotEmpty()) parsedPhones else buildList {
                if (local.mainPhone.isNotBlank()) add(PhoneEntry(local.mainPhone, "New"))
                if (local.additionalPhone.isNotBlank()) add(PhoneEntry(local.additionalPhone, local.additionalPhoneLabel.ifBlank { "Cell phone" }))
                if (local.whatsapp.isNotBlank() && local.whatsapp != local.mainPhone && local.whatsapp != local.additionalPhone) add(PhoneEntry(local.whatsapp, "WhatsApp"))
            }
        } else parsedPhones
        return contact.copy(
            displayName = if (local.surname.isBlank()) local.identifyingName else "${local.identifyingName} ${local.surname}",
            givenName = local.identifyingName,
            familyName = local.surname,
            phones = phones,
            note = contact.note
        )
    }

    private fun detectExternalPhoneChanges(contact: PhoneContact) {
        val local = clientDb.getClientByPhoneContactId(contact.id.toString()) ?: return
        val localPhones = parsePhonesJson(clientDb.getPhonesJson(local.id))
        val localNumbers = localPhones.map { normalizePhone(it.number) }.filter { it.isNotBlank() }.toSet()
        val candidates = contact.phones.filter { entry ->
            val key = normalizePhone(entry.number)
            key.isNotBlank() && !localNumbers.contains(key) && !ignoredExternalPhoneKeys.contains("${local.id}:$key")
        }
        if (candidates.isEmpty()) return
        showExternalPhoneChangeDialog(local, contact, candidates.first())
    }

    private fun showExternalPhoneChangeDialog(local: ClientRecord, contact: PhoneContact, detected: PhoneEntry) {
        val key = "${local.id}:${normalizePhone(detected.number)}"
        val message = "${detected.label.ifBlank { "New number" }}  ${detected.number}\n\nThis number exists in Phone/Google Contacts but is not in the Buy & Sell database.\n\nChoose what Buy & Sell should do."
        val options = arrayOf("ADD TO B&S", "ADD AS NEW NUMBER", "REPLACE CURRENT", "IGNORE")
        val dialogBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(TextView(this@MainActivity).apply { text = message; textSize = 16f })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("NEW NUMBER DETECTED")
            .setView(dialogBody)
            .setNegativeButton("CANCEL", null)
            .create()
        fun actionButton(text: String, action: () -> Unit) {
            dialogBody.addView(Button(this).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener { dialog.dismiss(); action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        actionButton("ADD TO B&S") { applyExternalPhoneDecision(local, contact, detected, detected.label.ifBlank { "New" }, null) }
        actionButton("ADD AS NEW NUMBER") { applyExternalPhoneDecision(local, contact, detected, "New", null) }
        actionButton("REPLACE CURRENT") { showReplaceExternalPhoneDialog(local, contact, detected) }
        actionButton("IGNORE") { ignoredExternalPhoneKeys.add(key) }
        dialog.show()
    }

    private fun showReplaceExternalPhoneDialog(local: ClientRecord, contact: PhoneContact, detected: PhoneEntry) {
        val current = parsePhonesJson(clientDb.getPhonesJson(local.id))
            .filter { it.number.isNotBlank() }
        if (current.isEmpty()) {
            applyExternalPhoneDecision(local, contact, detected, detected.label.ifBlank { "New" }, null)
            return
        }
        val labels = current.mapIndexed { index, phone ->
            "${index + 1}. ${phone.number} — ${phone.label}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("REPLACE WHICH NUMBER?")
            .setItems(labels) { _, which ->
                applyExternalPhoneDecision(local, contact, detected, detected.label.ifBlank { "New" }, which)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun applyExternalPhoneDecision(local: ClientRecord, contact: PhoneContact, detected: PhoneEntry, label: String, replaceIndex: Int?) {
        val phones = parsePhonesJson(clientDb.getPhonesJson(local.id)).map { EditablePhone(it.number, it.label) }.toMutableList()
        if (replaceIndex != null && replaceIndex in phones.indices) {
            phones[replaceIndex] = EditablePhone(detected.number.trim(), label)
        } else {
            phones.add(EditablePhone(detected.number.trim(), label))
        }
        val json = JSONArray().apply { phones.forEach { put(JSONObject().apply { put("number", it.number.trim()); put("label", it.label.ifBlank { "New" }) }) } }.toString()
        if (!clientDb.updatePhonesJsonOnly(local.id, json)) {
            showStatus("External number was detected, but Buy & Sell could not update its local database.")
            return
        }
        showStatus("BUY & SELL UPDATED — synchronising the selected contact with Google Contacts…")
        requestGoogleContactsAccess { token ->
            syncEditedClientToGoogle(local.id, contact, local.identifyingName, local.surname, phones, token, 1)
        }
        window.decorView.post {
            refreshContactResults()
            showStatus("BUY & SELL UPDATED — ${detected.number} is now in the client database.")
        }
    }

    private fun addContactRow(contact: PhoneContact, expanded: Boolean) {
        val displayContact = contactForDisplay(contact)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val radio = android.widget.RadioButton(this).apply {
            isChecked = selectedContact?.id == contact.id
            setOnClickListener {
                if (selectedContact?.id == contact.id) unselectContactAndRestoreResults()
                else selectContact(contact)
            }
        }
        top.addView(radio, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        val name = TextView(this).apply {
            text = displayContact.displayName
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val arrow = Button(this).apply {
            text = if (expandedContactId == contact.id) "▲" else "▼"
            minWidth = 0
            setOnClickListener {
                expandedContactId = if (expandedContactId == contact.id) null else contact.id
                refreshContactResults()
            }
        }
        top.addView(arrow, LinearLayout.LayoutParams(dp(52), dp(48)))
        card.addView(top)

        displayContact.phones.take(3).forEachIndexed { index, entry ->
            val phone = TextView(this).apply {
                text = if (index == 0) "${entry.number} — ${entry.label}" else "${index + 1}. ${entry.number} — ${entry.label}"
                textSize = 15f
                setPadding(dp(52), 0, dp(8), 2)
            }
            card.addView(phone, matchWrap())
        }

        if (expanded) {
            if (displayContact.phones.size > 3) {
                card.addView(TextView(this).apply {
                    text = "Other numbers from this contact:"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(52), dp(8), dp(8), dp(2))
                }, matchWrap())
                displayContact.phones.drop(3).forEachIndexed { index, entry ->
                    card.addView(TextView(this).apply {
                        text = "${index + 4}. ${entry.number} — ${entry.label}"
                        textSize = 15f
                        setPadding(dp(52), 0, dp(8), 2)
                    }, matchWrap())
                }
            }
            if (displayContact.givenName.isNotBlank() || displayContact.familyName.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "Structured name: ${displayContact.givenName.ifBlank { "—" }}${if (displayContact.familyName.isNotBlank()) " | Surname: ${displayContact.familyName}" else ""}"
                    textSize = 13f
                    setPadding(dp(52), dp(6), dp(8), dp(2))
                }, matchWrap())
            }
            if (displayContact.note.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "Phone Contact Notes (read-only):\n${displayContact.note}"
                    textSize = 13f
                    setPadding(dp(52), dp(6), dp(8), dp(6))
                }, matchWrap())
            }
            val editButton = makeButton("EDIT")
            editButton.setOnClickListener { showClientEditScreen(contact) }
            card.addView(editButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(52), dp(10), dp(8), dp(4))
            })
            card.addView(TextView(this).apply {
                text = "ⓘ  Client Edit opens the full contact maintenance screen."
                textSize = 13f
                setPadding(dp(52), dp(4), dp(8), dp(8))
            }, matchWrap())
        }
        resultsContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,dp(6)) })
    }


    private data class EditablePhone(var number: String, var label: String)
    private data class ClientEditState(val name: String, val surname: String, val phones: List<EditablePhone>)

    private fun snapshotEditState() {
        // Commit the current visible text before recording history. This prevents a
        // focus change (for example surname edit followed by number deletion) from
        // causing the first Undo to restore multiple logical changes together.
        activeEditNameField?.let { editWorkingName = it.text.toString().trim() }
        activeEditSurnameField?.let { editWorkingSurname = it.text.toString().trim() }
        editUndoStack.add(ClientEditState(editWorkingName, editWorkingSurname, editWorkingPhones.map { EditablePhone(it.number, it.label) }))
        if (editUndoStack.size > 5) editUndoStack.removeAt(0)
        updateEditUndoButton()
    }

    private fun updateEditUndoButton() {
        // The actual button is held by the edit screen closure via the view tag.
        val undo = window.decorView.findViewWithTag<Button>("V12_EDIT_UNDO")
        undo?.isEnabled = editUndoStack.isNotEmpty()
        undo?.alpha = if (editUndoStack.isNotEmpty()) 1f else 0.45f
    }

    private fun keepFocusedFieldVisible(scroll: ScrollView, view: View) {
        view.post {
            val bottom = view.bottom + dp(24)
            val visibleBottom = scroll.height
            if (bottom > visibleBottom) scroll.smoothScrollBy(0, bottom - visibleBottom)
        }
    }

    private fun showClientEditScreen(contact: PhoneContact) {
        editOriginalContact = contact
        val currentLocal = clientDb.getClientByPhoneContactId(contact.id.toString())
        val currentDisplay = contactForDisplay(contact)
        editWorkingName = currentLocal?.identifyingName ?: currentDisplay.givenName.ifBlank { currentDisplay.displayName }
        editWorkingSurname = currentLocal?.surname ?: currentDisplay.familyName
        editWorkingPhones = currentDisplay.phones.map { EditablePhone(it.number, it.label) }.toMutableList()
        if (editWorkingPhones.isEmpty()) editWorkingPhones.add(EditablePhone("", "New"))
        editInitialName = editWorkingName
        editInitialSurname = editWorkingSurname
        editInitialPhones = editWorkingPhones.map { EditablePhone(it.number, it.label) }
        editUndoStack.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), 0)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val title = TextView(this).apply {
            text = "CLIENT EDIT — V12.07"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            maxLines = 2
            includeFontPadding = true
        }
        content.addView(title, matchWrap())
        addSpace(content, 12)

        content.addView(label("Name"))
        val nameField = field("Name").apply { setText(editWorkingName) }
        content.addView(nameField, matchWrap())
        content.addView(label("Surname"))
        val surnameFieldEdit = field("Surname").apply { setText(editWorkingSurname) }
        activeEditNameField = nameField
        activeEditSurnameField = surnameFieldEdit
        content.addView(surnameFieldEdit, matchWrap())
        addSpace(content, 8)

        val phoneContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(phoneContainer, matchWrap())

        var renderingPhones = false
        fun renderPhones() {
            renderingPhones = true
            phoneContainer.removeAllViews()
            editWorkingPhones.forEachIndexed { index, ep ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val number = phoneField("At least 1 digit")
                var initializingNumber = true
                var numberHistoryRecorded = false
                var numberBefore = ep.number
                number.setText(ep.number)
                number.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
                        if (initializingNumber || renderingPhones) return
                        val value = s?.toString()?.trim().orEmpty()
                        if (!numberHistoryRecorded && value != numberBefore) { snapshotEditState(); numberHistoryRecorded = true }
                        ep.number = value
                    }
                    override fun afterTextChanged(e: Editable?) = Unit
                })
                number.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) { numberBefore = ep.number; numberHistoryRecorded = false }
                    else if (!renderingPhones) ep.number = number.text.toString().trim()
                    keepFocusedFieldVisible(scroll, number)
                }
                initializingNumber = false
                row.addView(number, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
                val labelButton = makeButton(if (ep.label.isBlank()) "New" else ep.label)
                labelButton.setOnClickListener {
                    val before = ep.label
                    choosePhoneLabel(ep.label) { newLabel ->
                        if (newLabel != before) {
                            snapshotEditState()
                            ep.label = newLabel
                            renderPhones()
                        }
                    }
                }
                row.addView(labelButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.32f))
                val delete = Button(this).apply {
                    text = "🗑"
                    minWidth = 0
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("DELETE THIS NUMBER?")
                            .setMessage("Are you sure you want to delete this number?")
                            .setNegativeButton("NO", null)
                            .setPositiveButton("YES") { _, _ ->
                                snapshotEditState()
                                editWorkingPhones.removeAt(index)
                                renderPhones()
                            }.show()
                    }
                }
                row.addView(delete, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.10f))
                phoneContainer.addView(row, matchWrap())
                addSpace(phoneContainer, 4)
            }
            val add = makeButton("+ NEW NUMBER")
            add.setOnClickListener {
                snapshotEditState()
                editWorkingPhones.add(EditablePhone("", "New"))
                renderPhones()
            }
            phoneContainer.addView(add, matchWrap())
            renderingPhones = false
        }
        renderPhones()

        var initializingName = true
        var nameHistoryRecorded = false
        var nameBefore = editWorkingName
        nameField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
                if (initializingName) return
                val now = s?.toString()?.trim().orEmpty()
                if (!nameHistoryRecorded && now != nameBefore) { snapshotEditState(); nameHistoryRecorded = true }
                editWorkingName = now
            }
            override fun afterTextChanged(e: Editable?) = Unit
        })
        nameField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) { nameBefore = editWorkingName; nameHistoryRecorded = false }
            keepFocusedFieldVisible(scroll, nameField)
        }
        initializingName = false

        var initializingSurname = true
        var surnameHistoryRecorded = false
        var surnameBefore = editWorkingSurname
        surnameFieldEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
                if (initializingSurname) return
                val now = s?.toString()?.trim().orEmpty()
                if (!surnameHistoryRecorded && now != surnameBefore) { snapshotEditState(); surnameHistoryRecorded = true }
                editWorkingSurname = now
            }
            override fun afterTextChanged(e: Editable?) = Unit
        })
        surnameFieldEdit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) { surnameBefore = editWorkingSurname; surnameHistoryRecorded = false }
            keepFocusedFieldVisible(scroll, surnameFieldEdit)
        }

        val undo = makeButton("UNDO").apply {
            tag = "V12_EDIT_UNDO"
            isEnabled = false
            alpha = 0.45f
            setOnClickListener {
                if (editUndoStack.isNotEmpty()) {
                    val state = editUndoStack.removeAt(editUndoStack.lastIndex)
                    editWorkingName = state.name
                    editWorkingSurname = state.surname
                    editWorkingPhones = state.phones.map { EditablePhone(it.number, it.label) }.toMutableList()
                    nameField.setText(editWorkingName)
                    surnameFieldEdit.setText(editWorkingSurname)
                    renderPhones()
                    updateEditUndoButton()
                }
            }
        }
        content.addView(undo, matchWrap())
        updateEditUndoButton()

        // EDIT ACTION BAR: fixed below the scrolling form.
        val actionRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            minimumHeight = dp(72)
        }
        val returnButton = makeButton("RETURN")
        val cancel = makeButton("CANCEL")
        val save = makeButton("SAVE")
        actionRow.addView(returnButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))

        root.setOnApplyWindowInsetsListener { view, insets ->
            val navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            view.setPadding(dp(18), dp(14), dp(18), navBottom)
            insets
        }
        root.requestApplyInsets()

        fun restoreEditInitialState() {
            editWorkingName = editInitialName
            editWorkingSurname = editInitialSurname
            editWorkingPhones = editInitialPhones.map { EditablePhone(it.number, it.label) }.toMutableList()
            if (editWorkingPhones.isEmpty()) editWorkingPhones.add(EditablePhone("", "New"))
            nameField.setText(editWorkingName)
            surnameFieldEdit.setText(editWorkingSurname)
            renderPhones()
            editUndoStack.clear()
            updateEditUndoButton()
        }

        fun editHasUnsavedChanges(): Boolean {
            val currentPhones = editWorkingPhones.map { EditablePhone(it.number.trim(), it.label.trim().ifBlank { "New" }) }
            val initialPhones = editInitialPhones.map { EditablePhone(it.number.trim(), it.label.trim().ifBlank { "New" }) }
            return editWorkingName.trim() != editInitialName.trim() ||
                editWorkingSurname.trim() != editInitialSurname.trim() || currentPhones != initialPhones
        }

        // CANCEL = SUPER UNDO. It restores the state from the moment Edit opened and
        // deliberately remains on the Edit page. It never navigates and never deletes.
        cancel.setOnClickListener {
            if (editHasUnsavedChanges()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("UNDO ALL EDITS?")
                    .setMessage("This will undo ALL changes made during this Edit session. The saved client will not be deleted.")
                    .setNegativeButton("NO / STAY", null)
                    .setPositiveButton("YES / UNDO") { _, _ -> restoreEditInitialState() }
                    .show()
            } else {
                restoreEditInitialState()
            }
        }

        // RETURN = navigation only. Unsaved edits must be explicitly discarded.
        returnButton.setOnClickListener {
            if (!editHasUnsavedChanges()) {
                buildCaptureScreen()
                restoreSelectionAfterEdit(contact)
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("UNSAVED EDITS")
                    .setMessage("Leaving this page before saving will lose all edits, Are you sure?")
                    .setNegativeButton("NO / STAY", null)
                    .setPositiveButton("YES / LEAVE") { _, _ ->
                        buildCaptureScreen()
                        restoreSelectionAfterEdit(contact)
                    }
                    .show()
            }
        }

        // SAVE has a separate path from CANCEL and RETURN: save, verify, rebuild the
        // Main Capture screen, then restore the complete saved client information.
        save.setOnClickListener {
            if (!save.isEnabled) return@setOnClickListener
            save.isEnabled = false
            editWorkingName = nameField.text.toString().trim()
            editWorkingSurname = surnameFieldEdit.text.toString().trim()
            if (editWorkingName.isBlank()) {
                AlertDialog.Builder(this)
                    .setMessage("Please enter the client name.")
                    .setPositiveButton("OK", null)
                    .show()
                save.isEnabled = true
                return@setOnClickListener
            }

            val finalPhones = editWorkingPhones
                .map { EditablePhone(it.number.trim(), it.label.ifBlank { "New" }.trim()) }
            editWorkingPhones = finalPhones.toMutableList()

            saveClientEditToContactsAndDatabase(
                contact = contact,
                name = editWorkingName,
                surname = editWorkingSurname,
                phones = finalPhones,
                onComplete = { updatedContact ->
                    val freshLocal = clientDb.getClientByPhoneContactId(contact.id.toString())
                    val freshPhones = parsePhonesJson(clientDb.getPhonesJson(freshLocal?.id ?: -1L))
                    val freshContact = updatedContact.copy(
                        displayName = if (freshLocal?.surname.isNullOrBlank()) freshLocal?.identifyingName ?: updatedContact.displayName else "${freshLocal!!.identifyingName} ${freshLocal.surname}",
                        givenName = freshLocal?.identifyingName ?: updatedContact.givenName,
                        familyName = freshLocal?.surname ?: updatedContact.familyName,
                        phones = freshPhones
                    )
                    buildCaptureScreen()
                    restoreSelectionAfterEdit(freshContact)
                    showStatus("SUCCESS — Client Edit saved. Full client information restored.")
                },
                onFailure = { message ->
                    save.isEnabled = true
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("SAVE FAILED")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                    showStatus(message)
                }
            )
        }

        setContentView(root)
    }

    private fun saveClientEditToContactsAndDatabase(
        contact: PhoneContact,
        name: String,
        surname: String,
        phones: List<EditablePhone>,
        onComplete: (PhoneContact) -> Unit,
        onFailure: (String) -> Unit
    ) {
        try {
            val phoneJson = JSONArray().apply {
                phones.forEach { phone ->
                    put(JSONObject().apply {
                        put("number", phone.number.trim())
                        put("label", phone.label.trim().ifBlank { "New" })
                    })
                }
            }.toString()

            // Use the same authoritative upsert path as Capture. This guarantees that
            // EDIT can save even if the phone-contact row is missing from the local DB;
            // an existing row is updated, otherwise a row is created.
            val saveResult = clientDb.saveOrUpdateClient(
                identifyingName = name,
                surname = surname,
                mainPhone = phones.firstOrNull()?.number.orEmpty(),
                additionalPhone = phones.drop(1).firstOrNull()?.number.orEmpty(),
                additionalPhoneLabel = phones.drop(1).firstOrNull()?.label.orEmpty().ifBlank { "Cell phone" },
                whatsapp = "",
                phoneContactId = contact.id.toString(),
                createdAt = System.currentTimeMillis(),
                phonesJsonOverride = phoneJson
            )

            val verified = clientDb.getClient(saveResult.id)
            val actualPhonesJson = clientDb.getPhonesJson(saveResult.id)
            val expectedMain = phones.firstOrNull()?.number.orEmpty()
            if (verified == null || verified.identifyingName != name ||
                verified.surname != surname || verified.mainPhone != expectedMain ||
                actualPhonesJson != phoneJson) {
                onFailure("CLIENT EDIT SAVE FAILED — saved data could not be verified.")
                return
            }

            clearCaptureDraft()
            val immediatePhones = phones.map { PhoneEntry(it.number.trim(), it.label.trim().ifBlank { "New" }) }
            val immediateContact = PhoneContact(
                id = contact.id,
                displayName = if (surname.isBlank()) name else "$name $surname",
                givenName = name,
                familyName = surname,
                note = contact.note,
                phones = immediatePhones
            )

            // Local Buy & Sell save is complete before Google authorization/sync is
            // attempted. Google must never be able to make SAVE appear to do nothing.
            onComplete(immediateContact)
            showStatus("BUY & SELL SAVED — synchronising Google Contacts…")
            requestGoogleContactsAccess { token ->
                syncEditedClientToGoogle(
                    clientId = saveResult.id,
                    contact = contact,
                    name = name,
                    surname = surname,
                    phones = phones,
                    accessToken = token,
                    attempt = 1
                )
            }
        } catch (t: Throwable) {
            onFailure("CLIENT EDIT SAVE FAILED — ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun syncEditedClientToGoogle(
        clientId: Long,
        contact: PhoneContact,
        name: String,
        surname: String,
        phones: List<EditablePhone>,
        accessToken: String,
        attempt: Int
    ) {
        Thread {
            try {
                val resourceName = clientDb.getGoogleResourceName(clientId)?.takeIf { it.isNotBlank() }
                    ?: resolveGoogleContactFromAndroidContact(contact.id, accessToken)?.resourceName
                    ?: throw IllegalStateException("Google Contact resourceName could not be resolved safely.")
                clientDb.setGoogleResourceName(clientId, resourceName)
                val current = fetchGooglePerson(resourceName, accessToken)
                val source = findContactSource(current) ?: throw IllegalStateException("Google Contact did not return a writable CONTACT source.")
                val desiredPhones = JSONArray().apply {
                    phones.filter { it.number.isNotBlank() }.forEach { phone ->
                        put(JSONObject().apply {
                            put("value", phone.number.trim())
                            val label = phone.label.trim()
                            put("type", googlePhoneType(label))
                        })
                    }
                }
                val body = JSONObject().apply {
                    put("resourceName", resourceName)
                    put("etag", current.optString("etag"))
                    put("metadata", JSONObject().apply {
                        put("sources", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "CONTACT")
                                put("id", source.first)
                                put("etag", source.second)
                            })
                        })
                    })
                    put("names", JSONArray().apply {
                        put(JSONObject().apply {
                            put("givenName", name)
                            if (surname.isNotBlank()) put("familyName", surname)
                        })
                    })
                    put("phoneNumbers", desiredPhones)
                }
                val connection = (URL("https://people.googleapis.com/v1/$resourceName:updateContact?updatePersonFields=names,phoneNumbers&personFields=names,phoneNumbers,metadata").openConnection() as HttpURLConnection).apply {
                    requestMethod = "PATCH"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                }
                val responseCode: Int
                val responseText: String
                try {
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    responseCode = connection.responseCode
                    val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } finally { connection.disconnect() }
                if (responseCode !in 200..299) throw IllegalStateException("Google Contacts update failed (HTTP $responseCode): $responseText")
                val updated = JSONObject(responseText)
                clientDb.setGoogleResourceName(clientId, updated.optString("resourceName").ifBlank { resourceName })
                clearFailedGoogleSync(clientId)
                applyAndroidGoogleRawContactUpdate(contact.id, name, surname, phones)
                runOnUiThread { showStatus("SUCCESS — Buy & Sell and Google Contacts are synchronized.") }
            } catch (e: Exception) {
                recordGoogleSyncFailure(clientId, contact.id, name, surname, phones, attempt, e.message ?: "Unknown Google error")
            }
        }.start()
    }

    private fun findContactSource(person: JSONObject): Pair<String, String>? {
        val sources = person.optJSONObject("metadata")?.optJSONArray("sources") ?: return null
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            if (source.optString("type") == "CONTACT") {
                val id = source.optString("id").trim()
                val etag = source.optString("etag").trim()
                if (id.isNotBlank() && etag.isNotBlank()) return id to etag
            }
        }
        return null
    }

    private fun applyAndroidGoogleRawContactUpdate(contactId: Long, name: String, surname: String, phones: List<EditablePhone>) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        Thread {
            try {
                val rawId = contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts._ID),
                    "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                    arrayOf(contactId.toString(), "com.google"), null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@Thread
                val nameValues = ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, name)
                    put(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, surname)
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, if (surname.isBlank()) name else "$name $surname")
                }
                contentResolver.update(ContactsContract.Data.CONTENT_URI, nameValues,
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
                contentResolver.delete(ContactsContract.Data.CONTENT_URI,
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE))
                phones.filter { it.number.isNotBlank() }.forEach { phone ->
                    val v = ContentValues().apply {
                        put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.number)
                        when (phone.label.lowercase(Locale.getDefault())) {
                            "cell", "cell phone", "mobile" -> put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            "work" -> put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
                            "home" -> put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_HOME)
                            else -> {
                                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
                                put(ContactsContract.CommonDataKinds.Phone.LABEL, phone.label)
                            }
                        }
                    }
                    contentResolver.insert(ContactsContract.Data.CONTENT_URI, v)
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun recordGoogleSyncFailure(clientId: Long, contactId: Long?, name: String, surname: String, phones: List<EditablePhone>, attempt: Int, error: String) {
        val now = System.currentTimeMillis()
        val existing = clientDb.getGoogleSyncFailure(clientId)
        val firstAttempt = existing?.firstAttemptAt ?: now
        val newAttempt = maxOf(existing?.attempt ?: 1, attempt)
        clientDb.upsertGoogleSyncFailure(clientId, contactId, name, surname, JSONArray().apply { phones.forEach { put(JSONObject().apply { put("number", it.number); put("label", it.label) }) } }.toString(), firstAttempt, newAttempt, error)
        val nextDelay = when (newAttempt) { 1 -> 5 * 60 * 1000L; 2 -> 2 * 60 * 60 * 1000L; 3 -> 6 * 60 * 60 * 1000L; else -> -1L }
        if (nextDelay > 0) scheduleGoogleRetry(clientId, nextDelay)
        runOnUiThread { showStatus("BUY & SELL SAVED — Google synchronization failed (attempt $newAttempt).\n\n$error\n\nAutomatic retry scheduled when applicable.") }
    }

    private fun scheduleGoogleRetry(clientId: Long, delayMs: Long) {
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, GoogleSyncRetryReceiver::class.java).putExtra("client_id", clientId)
        val pi = PendingIntent.getBroadcast(this, clientId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
    }

    private fun clearFailedGoogleSync(clientId: Long) { clientDb.deleteGoogleSyncFailure(clientId) }

    private fun retryPendingGoogleSyncIfNeeded() {
        val clientId = pendingRetryClientId ?: return
        pendingRetryClientId = null
        val failure = clientDb.getGoogleSyncFailure(clientId) ?: return
        val phones = try {
            val a = JSONArray(failure.phonesJson)
            List(a.length()) { i -> val o = a.getJSONObject(i); EditablePhone(o.optString("number"), o.optString("label")) }
        } catch (_: Exception) { emptyList() }
        val contactId = failure.contactId ?: return
        val contact = readPhoneContactById(contactId) ?: return
        val attempt = failure.attempt + 1
        requestGoogleContactsAccess { token ->
            syncEditedClientToGoogle(clientId, contact, failure.name, failure.surname, phones, token, attempt)
        }
    }

    private fun readPhoneContactById(contactId: Long): PhoneContact? {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME
        )
        var displayName = ""
        var note = ""
        contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            displayName = c.getString(1).orEmpty()
        }

        // Notes are stored as a separate Data/MIME row in Android Contacts,
        // not as ContactsContract.Contacts.NOTES.
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
            ),
            null
        )?.use { c ->
            if (c.moveToFirst()) note = c.getString(0).orEmpty()
        }
        var given = ""
        var family = ""
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
            null
        )?.use { c -> if (c.moveToFirst()) { given = c.getString(0).orEmpty(); family = c.getString(1).orEmpty() } }
        val phones = mutableListOf<PhoneEntry>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            "${ContactsContract.CommonDataKinds.Phone._ID} ASC"
        )?.use { c ->
            val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val ti = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            val li = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
            while (c.moveToNext()) {
                val number = c.getString(ni).orEmpty()
                val type = c.getInt(ti)
                val custom = if (li >= 0) c.getString(li).orEmpty() else ""
                val label = when (type) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Cell"
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                    ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> custom.ifBlank { "Custom" }
                    else -> custom.ifBlank { "Custom" }
                }
                phones.add(PhoneEntry(number, label))
            }
        }
        return PhoneContact(contactId, displayName, given, family, note, phones)
    }

    private fun choosePhoneLabel(current: String, onChosen: (String) -> Unit) {
        val options = arrayOf("Custom", "Cell", "Work", "Home")
        AlertDialog.Builder(this)
            .setTitle("LABEL")
            .setItems(options) { _, which ->
                if (which == 0) {
                    val input = EditText(this).apply { setText(if (current !in options) current else "") }
                    AlertDialog.Builder(this)
                        .setTitle("CUSTOM LABEL")
                        .setView(input)
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("OK") { _, _ -> onChosen(input.text.toString().trim().ifBlank { "New" }) }
                        .show()
                } else onChosen(options[which])
            }.show()
    }

    private fun parsePhonesJson(json: String): List<PhoneEntry> {
        return try {
            val a = JSONArray(json)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                PhoneEntry(o.optString("number"), o.optString("label").ifBlank { "New" })
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun restoreSelectionAfterEdit(contact: PhoneContact) {
        selectedContact = contact
        expandedContactId = contact.id
        contactAreaHidden = false
        selectedReferenceHidden = false
        suppressSearchRefresh = true
        try {
            val local = clientDb.getClientByPhoneContactId(contact.id.toString())
            firstNameField.setText(local?.identifyingName ?: contact.givenName.ifBlank { contact.displayName })
            surnameField.setText(local?.surname ?: contact.familyName)
            mainPhoneField.setText(local?.mainPhone ?: contact.phones.firstOrNull()?.number.orEmpty())
            additionalPhoneField.setText(local?.additionalPhone.orEmpty())
            setAdditionalPhoneLabel(local?.additionalPhoneLabel ?: "Cell phone")
        } finally { suppressSearchRefresh = false }
        listToggleButton.visibility = View.VISIBLE
        clearButton.visibility = View.VISIBLE
        undoButton.visibility = View.GONE
        setExistingClientCaptureMode(true)
        renderSelectedOnlyResult()
    }

    private fun setExistingClientCaptureMode(existingClientSelected: Boolean) {
        // Existing-client phone maintenance belongs in the client card EDIT screen.
        // RETRIEVE LIST remains available whenever matching contacts exist.
        // CLEAR remains available while a matching list or selected client is present.
        capturePhoneSection.visibility = if (existingClientSelected) View.GONE else View.VISIBLE
        captureSaveButton.visibility = if (existingClientSelected) View.GONE else View.VISIBLE
        listToggleButton.visibility = if (cachedContactMatches.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun selectContact(contact: PhoneContact) {
        cachedContactMatches = cachedContactMatches.ifEmpty {
            allContacts.filter { it.id == contact.id }
        }
        selectedContact = contact
        expandedContactId = contact.id
        selectedReferenceHidden = false
        contactReferenceToggleButton.visibility = View.GONE
        suppressSearchRefresh = true
        try {
            firstNameField.setText(contact.displayName)
            surnameField.setText(contact.familyName)
            val local = clientDb.getClientByPhoneContactId(contact.id.toString())
            if (local != null) {
                firstNameField.setText(local.identifyingName)
                surnameField.setText(local.surname.ifBlank { contact.familyName })
                mainPhoneField.setText(local.mainPhone)
                additionalPhoneField.setText(local.additionalPhone)
                setAdditionalPhoneLabel(local.additionalPhoneLabel)
                setMainPhoneEditable(local.mainPhoneSource != "GOOGLE")
            } else {
                mainPhoneField.setText(contact.phones.firstOrNull()?.number.orEmpty())
                additionalPhoneField.setText("")
                setAdditionalPhoneLabelIndex(0)
                setMainPhoneEditable(false)
            }
        } finally { suppressSearchRefresh = false }
        contactAreaHidden = false
        listToggleButton.text = "RETRIEVE LIST"
        clearButton.visibility = View.VISIBLE
        undoButton.visibility = View.GONE
        undoSnapshot = null
        setExistingClientCaptureMode(true)
        renderSelectedOnlyResult()
        // Compare the freshly retrieved Android/Google contact against the B&S record.
        // External changes are never imported silently.
        window.decorView.post { detectExternalPhoneChanges(contact) }
    }

    private fun unselectContactAndRestoreResults() {
        // Deselect only. Never erase information the user has already captured.
        selectedContact = null
        expandedContactId = null
        selectedReferenceHidden = false
        contactReferenceToggleButton.visibility = View.GONE
        contactAreaHidden = false
        listToggleButton.text = "HIDE LIST"
        listToggleButton.visibility = if (cachedContactMatches.isNotEmpty()) View.VISIBLE else View.GONE
        clearButton.visibility = if (cachedContactMatches.isNotEmpty()) View.VISIBLE else View.GONE
        resultsContainer.removeAllViews()
        if (cachedContactMatches.isNotEmpty()) {
            resultsScroll.visibility = View.VISIBLE
            matchingContactsLabel.visibility = View.VISIBLE
            cachedContactMatches.forEach { addContactRow(it, false) }
        } else {
            resultsScroll.visibility = View.GONE
            matchingContactsLabel.visibility = View.GONE
        }
    }

    private fun clearSelectedContact() {
        val hasCaptureData = firstNameField.text.toString().isNotBlank() || surnameField.text.toString().isNotBlank() ||
            mainPhoneField.text.toString().isNotBlank() || additionalPhoneField.text.toString().isNotBlank() ||
            captureAdditionalPhones.isNotEmpty() || selectedContact != null
        if (!hasCaptureData) {
            clearCaptureDraft()
            showStatus("Capture is already clear.")
            return
        }
        val selectedId = selectedContact?.id ?: -1L
        undoSnapshot = CaptureSnapshot(
            identifyingName = firstNameField.text.toString(),
            surname = surnameField.text.toString(),
            mainPhone = mainPhoneField.text.toString(),
            additionalPhone = additionalPhoneField.text.toString(),
            whatsapp = "",
            additionalPhoneLabelIndex = additionalPhoneLabelSpinner.selectedItemPosition,
            customAdditionalPhoneLabel = customAdditionalPhoneLabel,
            selectedContactId = selectedId,
            expandedContactId = expandedContactId,
            contactAreaHidden = contactAreaHidden,
            selectedReferenceHidden = selectedReferenceHidden,
            additionalPhones = captureAdditionalPhones.map { EditablePhone(it.number, it.label) }
        )
        suppressSearchRefresh = true
        try {
            selectedContact = null
            expandedContactId = null
            selectedReferenceHidden = false
            contactReferenceToggleButton.visibility = View.GONE
            contactAreaHidden = false
            firstNameField.setText("")
            surnameField.setText("")
            mainPhoneField.setText("")
            customMainPhoneLabel = ""
            mainPhoneLabelButton.text = "New"
            setMainPhoneEditable(true)
            additionalPhoneField.setText("")
            captureAdditionalPhones.clear()
            customAdditionalPhoneLabel = ""
            setAdditionalPhoneLabelIndex(0)
        } finally { suppressSearchRefresh = false }
        resultsContainer.removeAllViews()
        resultsScroll.visibility = View.GONE
        matchingContactsLabel.visibility = View.GONE
        listToggleButton.visibility = View.GONE
        clearButton.visibility = View.GONE
        undoButton.visibility = View.VISIBLE
        // CLEAR starts a genuinely clean/new capture state; restore the phone
        // fields and SAVE / UPDATE controls so unmatched names cannot strand
        // the user without a way to continue.
        setExistingClientCaptureMode(false)
        undoButton.requestFocus()
    }

    private fun undoClear() {
        val snapshot = undoSnapshot ?: return
        val restoredContact = allContacts.firstOrNull { it.id == snapshot.selectedContactId }
        suppressSearchRefresh = true
        try {
            customAdditionalPhoneLabel = snapshot.customAdditionalPhoneLabel
            additionalPhoneLabelSpinner.adapter = buildAdditionalPhoneLabelAdapter()
            firstNameField.setText(snapshot.identifyingName)
            surnameField.setText(snapshot.surname)
            mainPhoneField.setText(snapshot.mainPhone)
            additionalPhoneField.setText(snapshot.additionalPhone)
            captureAdditionalPhones.clear()
            captureAdditionalPhones.addAll(snapshot.additionalPhones.map { EditablePhone(it.number, it.label) })
            setAdditionalPhoneLabelIndex(snapshot.additionalPhoneLabelIndex)
            selectedContact = restoredContact
            expandedContactId = restoredContact?.let { snapshot.expandedContactId ?: it.id }
            contactAreaHidden = snapshot.contactAreaHidden
            selectedReferenceHidden = snapshot.selectedReferenceHidden
            setMainPhoneEditable(restoredContact == null || snapshot.mainPhone.isBlank() || clientDb.getClientByPhoneContactId(restoredContact.id.toString())?.mainPhoneSource != "GOOGLE")
        } finally { suppressSearchRefresh = false }
        clearButton.visibility = View.VISIBLE
        undoButton.visibility = View.GONE
        undoSnapshot = null
        listToggleButton.visibility = if (restoredContact != null) View.VISIBLE else View.GONE
        listToggleButton.text = "RETRIEVE LIST"
        if (restoredContact == null) {
            resultsContainer.removeAllViews()
            resultsScroll.visibility = View.GONE
            matchingContactsLabel.visibility = View.GONE
        } else if (selectedReferenceHidden || contactAreaHidden) {
            resultsScroll.visibility = View.GONE
            matchingContactsLabel.visibility = View.GONE
        } else {
            renderSelectedOnlyResult()
        }
    }

    private fun onSaveInstanceStateCompat(outState: Bundle) {
        outState.putString("capture_identifying_name", firstNameField.text.toString())
        outState.putString("capture_surname", surnameField.text.toString())
        outState.putString("capture_main_phone", mainPhoneField.text.toString())
        outState.putString("capture_additional_phone", additionalPhoneField.text.toString())
        outState.putInt("capture_additional_phone_label", additionalPhoneLabelSpinner.selectedItemPosition)
        outState.putString("capture_custom_additional_phone_label", customAdditionalPhoneLabel)
        outState.putLong("capture_selected_contact_id", selectedContact?.id ?: -1L)
        outState.putLong("capture_expanded_contact_id", expandedContactId ?: -1L)
        outState.putBoolean("capture_contact_area_hidden", contactAreaHidden)
        outState.putBoolean("capture_selected_reference_hidden", selectedReferenceHidden)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        onSaveInstanceStateCompat(outState)
        super.onSaveInstanceState(outState)
    }

    private fun restoreInstanceState(state: Bundle?) {
        if (state == null) return
        suppressSearchRefresh = true
        try {
            firstNameField.setText(state.getString("capture_identifying_name", ""))
            surnameField.setText(state.getString("capture_surname", ""))
            mainPhoneField.setText(state.getString("capture_main_phone", ""))
            additionalPhoneField.setText(state.getString("capture_additional_phone", ""))
            customAdditionalPhoneLabel = state.getString("capture_custom_additional_phone_label", "")
            additionalPhoneLabelSpinner.adapter = buildAdditionalPhoneLabelAdapter()
            setAdditionalPhoneLabelIndex(state.getInt("capture_additional_phone_label", 0))
            contactAreaHidden = state.getBoolean("capture_contact_area_hidden", false)
            selectedReferenceHidden = state.getBoolean("capture_selected_reference_hidden", false)
            expandedContactId = state.getLong("capture_expanded_contact_id", -1L).takeIf { it >= 0L }
            pendingSelectedContactId = state.getLong("capture_selected_contact_id", -1L).takeIf { it >= 0L }
            listToggleButton.text = if (pendingSelectedContactId != null || contactAreaHidden) "RETRIEVE LIST" else "HIDE LIST"
            listToggleButton.visibility = if (pendingSelectedContactId != null || contactAreaHidden) View.VISIBLE else View.GONE
            contactReferenceToggleButton.visibility = if (pendingSelectedContactId != null && selectedReferenceHidden) View.VISIBLE else View.GONE
        } finally { suppressSearchRefresh = false }
    }

    private fun loadExistingClient(client: ClientRecord) {
        suppressSearchRefresh = true
        try {
            firstNameField.setText(client.identifyingName)
            surnameField.setText(client.surname)
            mainPhoneField.setText(client.mainPhone)
            additionalPhoneField.setText(client.additionalPhone)
            setAdditionalPhoneLabel(client.additionalPhoneLabel)
            setMainPhoneEditable(client.mainPhoneSource != "GOOGLE")
        } finally {
            suppressSearchRefresh = false
        }
        showStatus("Existing client loaded. Review or edit the information before saving.")
    }

    private fun updateExistingClient(clientId: Long) {
        val updated = clientDb.updateClientById(
            clientId,
            firstNameField.text.toString().trim(),
            surnameField.text.toString().trim(),
            mainPhoneField.text.toString().trim(),
            additionalPhoneField.text.toString().trim(),
            additionalPhoneLabelSpinner.selectedItem?.toString().orEmpty(),
            ""
        )
        showStatus(if (updated) "Existing client updated locally." else "ERROR — Existing client could not be updated.")
    }

    private fun hasInvalidCapturePhone(): Boolean {
        val rows = mutableListOf<EditablePhone>()
        rows.add(EditablePhone(mainPhoneField.text.toString().trim(), mainPhoneLabelButton.text.toString().ifBlank { "New" }))
        rows.add(EditablePhone(additionalPhoneField.text.toString().trim(), additionalPhoneLabelSpinner.selectedItem?.toString().orEmpty().ifBlank { "New" }))
        rows.addAll(captureAdditionalPhones)
        // Empty rows with the untouched default label remain valid local draft rows.
        // A deliberately labelled telephone row must contain at least one digit.
        return rows.any { it.number.filter(Char::isDigit).isEmpty() && it.label.isNotBlank() && !it.label.equals("New", true) && !it.label.equals("Custom", true) }
    }

    private fun saveOrUpdate() {
        val identifyingName = firstNameField.text.toString().trim()
        val surname = surnameField.text.toString().trim()
        val mainPhone = mainPhoneField.text.toString().trim()
        val additionalPhone = additionalPhoneField.text.toString().trim()
        val additionalPhoneLabel = additionalPhoneLabelSpinner.selectedItem?.toString().orEmpty()
        val mainPhoneLabel = mainPhoneLabelButton.text.toString().ifBlank { "New" }
        val whatsapp = ""

        if (identifyingName.isBlank()) {
            showStatus("Please enter the client name before saving.")
            firstNameField.requestFocus()
            return
        }
        if (hasInvalidCapturePhone()) {
            showStatus("Please enter at least 1 digit in every labelled telephone field before saving.")
            return
        }
        val selected = selectedContact
        if (selected == null) {
            val duplicate = clientDb.findPossibleDuplicate(identifyingName, mainPhone)
            if (duplicate != null) {
                AlertDialog.Builder(this)
                    .setTitle("NUMBER ALREADY EXISTS")
                    .setMessage("This number is already associated with an existing client:\n\n${duplicate.identifyingName}\n${duplicate.mainPhone.ifBlank { "No main telephone" }}\n\nChoose an action.")
                    .setNegativeButton("IGNORE THIS NUMBER", null)
                    .setPositiveButton("ADD NUMBER TO THIS CLIENT") { _, _ -> loadExistingClient(duplicate) }
                    .show()
                return
            }
        }
        val now = System.currentTimeMillis()
        val existingId = selected?.id?.toString()
        // Main Capture SAVE is additive when an existing client is selected.
        // Never rebuild the selected client's phone list from only the currently
        // visible Capture fields, because those fields may be hidden/collapsed.
        // Preserve every stored local row and append only genuinely new numbers.
        val captureEntries = mutableListOf<PhoneEntry>()
        captureEntries.add(PhoneEntry(mainPhone, mainPhoneLabel.ifBlank { "New" }))
        captureEntries.add(PhoneEntry(additionalPhone, additionalPhoneLabel.ifBlank { "New" }))
        captureEntries.addAll(captureAdditionalPhones.map { PhoneEntry(it.number.trim(), it.label.ifBlank { "New" }) })
        val mergedCaptureEntries = if (selected != null) {
            val existingEntries = parsePhonesJson(clientDb.getPhonesJson(selected.id))
            val merged = existingEntries.toMutableList()
            val known = existingEntries.map { normalizePhone(it.number) }.filter { it.isNotBlank() }.toMutableSet()
            captureEntries.filter { it.number.isNotBlank() }.forEach { entry ->
                val key = normalizePhone(entry.number)
                if (key.isNotBlank() && known.add(key)) merged.add(entry)
            }
            // Keep existing blank labelled rows locally; add no blank capture rows.
            merged
        } else {
            captureEntries
        }
        val capturePhonesJson = JSONArray().apply {
            mergedCaptureEntries.forEach { entry ->
                put(JSONObject().apply {
                    put("number", entry.number.trim())
                    put("label", entry.label.ifBlank { "New" })
                })
            }
        }.toString()
        val savedId = clientDb.saveOrUpdateClient(
            identifyingName = identifyingName,
            surname = surname,
            mainPhone = mainPhone,
            additionalPhone = additionalPhone,
            additionalPhoneLabel = additionalPhoneLabel,
            whatsapp = whatsapp,
            phoneContactId = existingId,
            createdAt = now,
            phonesJsonOverride = capturePhonesJson
        )

        // Immediate local read-back. This verifies the exact values written to SQLite
        // before any Google operation is attempted.
        val local = clientDb.getClient(savedId.id)
        if (local == null) {
            showStatus("ERROR — Client was saved but local database read-back failed.")
            return
        }
        if (local.identifyingName != identifyingName ||
            local.surname != surname ||
            local.mainPhone != mainPhone ||
            local.additionalPhone != additionalPhone ||
            local.additionalPhoneLabel != additionalPhoneLabel ||
            local.whatsapp != whatsapp ||
            clientDb.getPhonesJson(savedId.id) != capturePhonesJson
        ) {
            showStatus("ERROR — Local database read-back does not match the values just saved.\n\nNo Google update was attempted.")
            return
        }

        clearCaptureDraft()
        val action = if (savedId.wasUpdate) "updated" else "saved"
        val contactText = selected?.displayName ?: identifyingName
        val localReadBack = buildString {
            append("LOCAL DATABASE VERIFIED\n")
            append("Client ID: ${savedId.id}\n")
            append("Main: ${local.mainPhone.ifBlank { "—" }}\n")
            append("Additional: ${local.additionalPhone.ifBlank { "—" }}\n")
            append("WhatsApp: ${local.whatsapp.ifBlank { "—" }}")
        }

        if (!savedId.wasUpdate && selected == null) {
            showStatus("SUCCESS — Client saved locally.\n\n$localReadBack\n\nGetting current Google Contacts authorization…")
            requestGoogleContactsAccess { token ->
                showStatus("SUCCESS — Client saved locally.\n\n$localReadBack\n\nCreating Google Contact…")
                createGoogleContact(
                    accessToken = token,
                    localClientId = savedId.id,
                    identifyingName = identifyingName,
                    surname = surname,
                    mainPhone = mainPhone,
                    additionalPhone = additionalPhone,
                    additionalPhoneLabel = additionalPhoneLabel,
                    whatsapp = whatsapp,
                    contactText = contactText,
                    phones = parsePhonesJson(capturePhonesJson)
                )
            }
            return
        }

        if (selected != null) {
            showStatus("SUCCESS — Client $action locally.\n\n$localReadBack\n\nGetting current Google Contacts authorization…")
            requestGoogleContactsAccess { token ->
                showStatus("SUCCESS — Client $action locally.\n\n$localReadBack\n\nFinding the linked Google Contact…")
                updateExistingGoogleContact(
                    accessToken = token,
                    localClientId = savedId.id,
                    selectedPhoneContactId = selected.id,
                    identifyingName = identifyingName,
                    additionalPhone = additionalPhone,
                    additionalPhoneLabel = additionalPhoneLabel,
                    whatsapp = whatsapp,
                    contactText = contactText,
                    localReadBack = localReadBack,
                    phones = parsePhonesJson(capturePhonesJson)
                )
            }
            return
        }

        showStatus("SUCCESS — Client $action.\n\n$contactText\nClient record ID: ${savedId.id}\n\n$localReadBack")
    }

    private fun createGoogleContact(
        accessToken: String,
        localClientId: Long,
        identifyingName: String,
        surname: String,
        mainPhone: String,
        additionalPhone: String,
        additionalPhoneLabel: String,
        whatsapp: String,
        contactText: String,
        phones: List<PhoneEntry>,
        allowAuthRetry: Boolean = true
    ) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val names = JSONArray().apply {
                    val name = JSONObject().apply {
                        put("givenName", identifyingName)
                        if (surname.isNotBlank()) put("familyName", surname)
                    }
                    put(name)
                }

                val phoneNumbers = JSONArray().apply {
                    phones.filter { it.number.isNotBlank() }.forEach { phone ->
                        put(JSONObject().apply {
                            put("value", phone.number.trim())
                            put("type", googlePhoneType(phone.label))
                        })
                    }
                }

                val body = JSONObject().apply {
                    put("names", names)
                    put("phoneNumbers", phoneNumbers)
                }

                connection = (URL("https://people.googleapis.com/v1/people:createContact?personFields=names,phoneNumbers,metadata").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                }

                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && allowAuthRetry) {
                    googleAccessToken = null
                    runOnUiThread {
                        showStatus("Google rejected the access token (HTTP 401). Getting a fresh authorization and retrying once…")
                        requestGoogleContactsAccess { freshToken ->
                            createGoogleContact(
                                accessToken = freshToken,
                                localClientId = localClientId,
                                identifyingName = identifyingName,
                                surname = surname,
                                mainPhone = mainPhone,
                                additionalPhone = additionalPhone,
                                additionalPhoneLabel = additionalPhoneLabel,
                                whatsapp = whatsapp,
                                contactText = contactText,
                                phones = phones,
                                allowAuthRetry = false
                            )
                        }
                    }
                    return@Thread
                }
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Google Contacts create failed (HTTP $responseCode): $responseText")
                }

                val response = JSONObject(responseText)
                val resourceName = response.optString("resourceName").trim()
                if (resourceName.isBlank()) {
                    throw IllegalStateException("Google created the contact but returned no resourceName.")
                }

                clientDb.setGoogleResourceName(localClientId, resourceName)
                runOnUiThread {
                    showStatus("SUCCESS — Client saved locally AND created in Google Contacts.\n\n$contactText\nGoogle resource: $resourceName")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showStatus("CLIENT SAVED LOCALLY — Google Contact creation failed.\n\n$contactText\n${e.message ?: "Unknown Google error"}")
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private data class GoogleContactLink(
        val resourceName: String,
        val sourceId: String,
        val sourceEtag: String
    )

    private fun updateExistingGoogleContact(
        accessToken: String,
        localClientId: Long,
        selectedPhoneContactId: Long,
        identifyingName: String,
        additionalPhone: String,
        additionalPhoneLabel: String,
        whatsapp: String,
        contactText: String,
        localReadBack: String,
        phones: List<PhoneEntry>,
        allowAuthRetry: Boolean = true
    ) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                // If this Buy & Sell record already has a Google resourceName, use it directly.
                // Otherwise bridge the Android Google raw-contact SOURCE_ID to the People API's
                // CONTACT source id. This avoids guessing by name or telephone number.
                val storedResource = clientDb.getGoogleResourceName(localClientId)
                val link = if (!storedResource.isNullOrBlank()) {
                    val fresh = fetchGoogleContact(storedResource, accessToken)
                    GoogleContactLink(
                        resourceName = storedResource,
                        sourceId = fresh.sourceId,
                        sourceEtag = fresh.sourceEtag
                    )
                } else {
                    resolveGoogleContactFromAndroidContact(selectedPhoneContactId, accessToken)
                        ?: throw IllegalStateException("The selected phone contact is not currently linked to a Google Contact that this app can safely identify. No Google Contact was changed.")
                }

                clientDb.setGoogleResourceName(localClientId, link.resourceName)

                val current = fetchGooglePerson(link.resourceName, accessToken)
                // B&S is sending its current desired phone list. Google updateContact
                // replaces phoneNumbers when that field is in the update mask. This means
                // a number deliberately deleted in B&S is also removed from Google instead
                // of being silently preserved by a merge.
                val mergedNumbers = JSONArray().apply {
                    phones.filter { it.number.isNotBlank() }.forEach { phone ->
                        put(JSONObject().apply {
                            put("value", phone.number.trim())
                            put("type", googlePhoneType(phone.label))
                        })
                    }
                }

                val body = JSONObject().apply {
                    put("resourceName", link.resourceName)
                    put("etag", current.optString("etag"))
                    put("metadata", JSONObject().apply {
                        put("sources", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "CONTACT")
                                put("id", link.sourceId)
                                put("etag", link.sourceEtag)
                            })
                        })
                    })
                    put("phoneNumbers", mergedNumbers)
                }

                connection = (URL("https://people.googleapis.com/v1/${link.resourceName}:updateContact?updatePersonFields=phoneNumbers&personFields=names,phoneNumbers,metadata").openConnection() as HttpURLConnection).apply {
                    requestMethod = "PATCH"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                }

                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED && allowAuthRetry) {
                    googleAccessToken = null
                    runOnUiThread {
                        showStatus("Google rejected the access token (HTTP 401). Getting a fresh authorization and retrying once…")
                        requestGoogleContactsAccess { freshToken ->
                            updateExistingGoogleContact(
                                accessToken = freshToken,
                                localClientId = localClientId,
                                selectedPhoneContactId = selectedPhoneContactId,
                                identifyingName = identifyingName,
                                additionalPhone = additionalPhone,
                                additionalPhoneLabel = additionalPhoneLabel,
                                whatsapp = whatsapp,
                                contactText = contactText,
                                localReadBack = localReadBack,
                                phones = phones,
                                allowAuthRetry = false
                            )
                        }
                    }
                    return@Thread
                }
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Google Contacts update failed (HTTP $responseCode): $responseText")
                }

                val updated = JSONObject(responseText)
                val updatedResource = updated.optString("resourceName").trim().ifBlank { link.resourceName }
                clientDb.setGoogleResourceName(localClientId, updatedResource)

                // Read the local row back once more after the Google operation so the final
                // status proves both local persistence and successful external mutation.
                val verified = clientDb.getClient(localClientId)
                val verifyText = if (verified != null) {
                    "LOCAL DATABASE VERIFIED AGAIN\nAdditional: ${verified.additionalPhone.ifBlank { "—" }}\nWhatsApp: ${verified.whatsapp.ifBlank { "—" }}"
                } else {
                    "LOCAL DATABASE READ-BACK FAILED AFTER GOOGLE UPDATE"
                }

                runOnUiThread {
                    showStatus("SUCCESS — Client updated locally AND Google Contact updated.\n\n$contactText\nGoogle resource: $updatedResource\n\n$localReadBack\n\n$verifyText\n\nThe existing Google Contact was updated; no duplicate Google Contact was created.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showStatus("CLIENT SAVED LOCALLY — Google Contact was NOT changed.\n\n$contactText\n\n$localReadBack\n\n${e.message ?: "Unknown Google update error"}")
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun resolveGoogleContactFromAndroidContact(
        selectedPhoneContactId: Long,
        accessToken: String
    ): GoogleContactLink? {
        val rawProjection = arrayOf(
            ContactsContract.RawContacts.SOURCE_ID,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.ACCOUNT_NAME
        )
        val sourceIds = mutableListOf<Pair<String, String>>()
        contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            rawProjection,
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.SOURCE_ID} IS NOT NULL",
            arrayOf(selectedPhoneContactId.toString(), "com.google"),
            null
        )?.use { cursor ->
            val sourceIndex = cursor.getColumnIndex(ContactsContract.RawContacts.SOURCE_ID)
            val accountIndex = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(sourceIndex).orEmpty().trim()
                val accountName = cursor.getString(accountIndex).orEmpty().trim()
                if (sourceId.isNotBlank()) sourceIds.add(sourceId to accountName)
            }
        }

        if (sourceIds.isEmpty()) return null

        var pageToken: String? = null
        do {
            val urlBuilder = StringBuilder("https://people.googleapis.com/v1/people/me/connections?pageSize=1000&personFields=metadata,names,phoneNumbers")
            if (!pageToken.isNullOrBlank()) urlBuilder.append("&pageToken=").append(java.net.URLEncoder.encode(pageToken, "UTF-8"))

            val connection = (URL(urlBuilder.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }

            try {
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Google Contacts lookup failed (HTTP $responseCode): $responseText")
                }

                val response = JSONObject(responseText)
                val people = response.optJSONArray("connections")
                if (people != null) {
                    for (i in 0 until people.length()) {
                        val person = people.optJSONObject(i) ?: continue
                        val metadata = person.optJSONObject("metadata") ?: continue
                        val sources = metadata.optJSONArray("sources") ?: continue
                        for (j in 0 until sources.length()) {
                            val source = sources.optJSONObject(j) ?: continue
                            if (source.optString("type") != "CONTACT") continue
                            val sourceId = source.optString("id").trim()
                            val match = sourceIds.firstOrNull { it.first == sourceId }
                            if (match != null) {
                                val resourceName = person.optString("resourceName").trim()
                                val sourceEtag = source.optString("etag").trim()
                                if (resourceName.isNotBlank() && sourceEtag.isNotBlank()) {
                                    return GoogleContactLink(resourceName, sourceId, sourceEtag)
                                }
                            }
                        }
                    }
                }
                pageToken = response.optString("nextPageToken").trim().ifBlank { null }
            } finally {
                connection.disconnect()
            }
        } while (!pageToken.isNullOrBlank())

        return null
    }

    private fun fetchGoogleContact(resourceName: String, accessToken: String): GoogleContactLink {
        val person = fetchGooglePerson(resourceName, accessToken)
        val metadata = person.optJSONObject("metadata") ?: throw IllegalStateException("Google Contact metadata was not returned.")
        val sources = metadata.optJSONArray("sources") ?: throw IllegalStateException("Google Contact source metadata was not returned.")
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            if (source.optString("type") == "CONTACT") {
                val id = source.optString("id").trim()
                val etag = source.optString("etag").trim()
                if (id.isNotBlank() && etag.isNotBlank()) return GoogleContactLink(resourceName, id, etag)
            }
        }
        throw IllegalStateException("Google Contact has no writable CONTACT source.")
    }

    private fun fetchGooglePerson(resourceName: String, accessToken: String): JSONObject {
        val connection = (URL("https://people.googleapis.com/v1/$resourceName?personFields=names,phoneNumbers,metadata").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IllegalStateException("Google Contact read failed (HTTP $responseCode): $responseText")
            }
            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizePhone(value: String): String = value.filter { it.isDigit() }

    private fun requestGoogleContactsAccess(onAuthorized: ((String) -> Unit)? = null) {
        if (onAuthorized != null) {
            pendingGoogleAuthorizationCallback = onAuthorized
        }

        val scope = Scope("https://www.googleapis.com/auth/contacts")
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(scope)).build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution() && result.pendingIntent != null) {
                    authorizationLauncher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                } else if (result.accessToken != null) {
                    val token = result.accessToken!!
                    googleAccessToken = token
                    showStatus("Google Contacts access granted.")
                    pendingGoogleAuthorizationCallback?.let { callback ->
                        pendingGoogleAuthorizationCallback = null
                        callback(token)
                    }
                } else {
                    pendingGoogleAuthorizationCallback = null
                    showStatus("Google authorization completed without an access token.")
                }
            }
            .addOnFailureListener {
                pendingGoogleAuthorizationCallback = null
                showStatus("Google authorization failed: ${it.message}")
            }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.getDefault()).replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
    private fun field(hint: String) = EditText(this).apply { this.hint = hint; textSize = 17f; setSingleLine(true) }
    private fun phoneField(hint: String) = object : EditText(this) {
        override fun onTextContextMenuItem(id: Int): Boolean {
            if (id == android.R.id.paste) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                if (pasted.isNotBlank()) {
                    val editable = text ?: return true
                    val start = selectionStart.coerceIn(0, editable.length)
                    val end = selectionEnd.coerceIn(start, editable.length)
                    // Paste is handled only by the focused telephone field. It cannot
                    // replace or write to another telephone field.
                    editable.replace(start, end, pasted)
                    return true
                }
            }
            return super.onTextContextMenuItem(id)
        }
    }.apply {
        this.hint = hint
        textSize = 17f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_PHONE
    }

    // Protect the original Google-sourced main number while keeping Buy & Sell-owned
    // numbers editable. The field remains visible/readable when protected.
    private fun setMainPhoneEditable(editable: Boolean) {
        mainPhoneField.isEnabled = editable
        mainPhoneField.isFocusable = editable
        mainPhoneField.isFocusableInTouchMode = editable
        mainPhoneField.isClickable = editable
        mainPhoneField.isLongClickable = editable
    }

    private fun makeButton(text: String) = Button(this).apply { this.text = text; textSize = 15f }
    private fun addSpace(root: LinearLayout, px: Int) { root.addView(View(this), LinearLayout.LayoutParams(1, dp(px))) }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun showStatus(message: String) { runOnUiThread { statusView.text = message } }

    private data class SaveResult(val id: Long, val wasUpdate: Boolean)

    private data class ClientRecord(
        val id: Long,
        val identifyingName: String,
        val surname: String,
        val mainPhone: String,
        val additionalPhone: String,
        val additionalPhoneLabel: String,
        val whatsapp: String,
        val phoneContactId: String?,
        val googleResourceName: String?,
        val mainPhoneSource: String
    )

    private class ClientDb(context: android.content.Context) : SQLiteOpenHelper(context, "buyandsell.db", null, 9) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE clients (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    identifying_name TEXT NOT NULL,
                    surname TEXT NOT NULL DEFAULT '',
                    main_phone TEXT NOT NULL DEFAULT '',
                    additional_phone TEXT NOT NULL DEFAULT '',
                    additional_phone_label TEXT NOT NULL DEFAULT 'Cell phone',
                    whatsapp TEXT NOT NULL DEFAULT '',
                    phone_contact_id TEXT,
                    google_resource_name TEXT,
                    main_phone_source TEXT NOT NULL DEFAULT 'LOCAL',
                    phones_json TEXT NOT NULL DEFAULT '[]',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_clients_phone_contact_id ON clients(phone_contact_id)")
            db.execSQL("CREATE TABLE google_sync_failures (client_id INTEGER PRIMARY KEY, contact_id INTEGER, name TEXT NOT NULL, surname TEXT NOT NULL, phones_json TEXT NOT NULL, first_attempt_at INTEGER NOT NULL, attempt INTEGER NOT NULL, error TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE external_phone_ignores (client_id INTEGER NOT NULL, phone_number TEXT NOT NULL, PRIMARY KEY(client_id, phone_number))")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE clients ADD COLUMN google_resource_name TEXT")
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE clients ADD COLUMN main_phone_source TEXT NOT NULL DEFAULT 'LOCAL'")
                db.execSQL("UPDATE clients SET main_phone_source = 'GOOGLE' WHERE phone_contact_id IS NOT NULL")
            }
            if (oldVersion < 5) {
                db.execSQL("ALTER TABLE clients ADD COLUMN additional_phone_label TEXT NOT NULL DEFAULT 'Cell phone'")
            }
            if (oldVersion < 6) {
                db.execSQL("ALTER TABLE clients ADD COLUMN phones_json TEXT NOT NULL DEFAULT '[]'")
            }
            if (oldVersion < 7) {
                db.execSQL("CREATE TABLE google_sync_failures (client_id INTEGER PRIMARY KEY, contact_id INTEGER, name TEXT NOT NULL, surname TEXT NOT NULL, phones_json TEXT NOT NULL, first_attempt_at INTEGER NOT NULL, attempt INTEGER NOT NULL, error TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            }
            if (oldVersion < 8) {
                // Google create/link can fail before a Google/Android contact id exists.
                // Keep the local failure queue valid in that case.
                db.execSQL("CREATE TABLE google_sync_failures_v8 (client_id INTEGER PRIMARY KEY, contact_id INTEGER, name TEXT NOT NULL, surname TEXT NOT NULL, phones_json TEXT NOT NULL, first_attempt_at INTEGER NOT NULL, attempt INTEGER NOT NULL, error TEXT NOT NULL, updated_at INTEGER NOT NULL)")
                db.execSQL("INSERT INTO google_sync_failures_v8 SELECT client_id, contact_id, name, surname, phones_json, first_attempt_at, attempt, error, updated_at FROM google_sync_failures")
                db.execSQL("DROP TABLE google_sync_failures")
                db.execSQL("ALTER TABLE google_sync_failures_v8 RENAME TO google_sync_failures")
            }
            if (oldVersion < 9) {
                db.execSQL("CREATE TABLE IF NOT EXISTS external_phone_ignores (client_id INTEGER NOT NULL, phone_number TEXT NOT NULL, PRIMARY KEY(client_id, phone_number))")
            }
        }

        fun setGoogleResourceName(clientId: Long, resourceName: String) {
            val values = ContentValues().apply {
                put("google_resource_name", resourceName)
                put("updated_at", System.currentTimeMillis())
            }
            writableDatabase.update("clients", values, "id = ?", arrayOf(clientId.toString()))
        }

        fun saveOrUpdateClient(
            identifyingName: String,
            surname: String,
            mainPhone: String,
            additionalPhone: String,
            additionalPhoneLabel: String,
            whatsapp: String,
            phoneContactId: String?,
            createdAt: Long,
            phonesJsonOverride: String? = null
        ): SaveResult {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("identifying_name", identifyingName)
                put("surname", surname)
                put("main_phone", mainPhone)
                put("additional_phone", additionalPhone)
                put("additional_phone_label", additionalPhoneLabel)
                put("whatsapp", whatsapp)
                put("phones_json", phonesJsonOverride ?: JSONArray().apply {
                    put(JSONObject().apply { put("number", mainPhone); put("label", "New") })
                    put(JSONObject().apply { put("number", additionalPhone); put("label", additionalPhoneLabel.ifBlank { "New" }) })
                    if (whatsapp.isNotBlank() && whatsapp != mainPhone && whatsapp != additionalPhone) put(JSONObject().apply { put("number", whatsapp); put("label", "WhatsApp") })
                }.toString())
                if (phoneContactId == null) putNull("phone_contact_id") else put("phone_contact_id", phoneContactId)
                put("updated_at", System.currentTimeMillis())
            }

            if (!phoneContactId.isNullOrBlank()) {
                db.query("clients", arrayOf("id"), "phone_contact_id = ?", arrayOf(phoneContactId), null, null, "id ASC", "1").use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(0)
                        db.update("clients", values, "id = ?", arrayOf(id.toString()))
                        return SaveResult(id, true)
                    }
                }
            }

            values.put("main_phone_source", if (phoneContactId.isNullOrBlank()) "LOCAL" else "GOOGLE")
            values.put("created_at", createdAt)
            val id = db.insertOrThrow("clients", null, values)
            return SaveResult(id, false)
        }
        fun findPossibleDuplicate(identifyingName: String, mainPhone: String): ClientRecord? {
            val db = readableDatabase
            val normalizedPhone = mainPhone.trim()
            if (normalizedPhone.isNotBlank()) {
                db.query("clients", arrayOf("id"), "main_phone = ? OR additional_phone = ? OR whatsapp = ?", arrayOf(normalizedPhone, normalizedPhone, normalizedPhone), null, null, "id ASC", "1").use { cursor ->
                    if (cursor.moveToFirst()) return getClient(cursor.getLong(0))
                }
            }
            if (identifyingName.isNotBlank()) {
                db.query("clients", arrayOf("id"), "LOWER(identifying_name) = LOWER(?)", arrayOf(identifyingName.trim()), null, null, "id ASC", "1").use { cursor ->
                    if (cursor.moveToFirst()) return getClient(cursor.getLong(0))
                }
            }
            return null
        }

        fun updateClientById(id: Long, identifyingName: String, surname: String, mainPhone: String, additionalPhone: String, additionalPhoneLabel: String, whatsapp: String): Boolean {
            val values = ContentValues().apply {
                put("identifying_name", identifyingName)
                put("surname", surname)
                put("main_phone", mainPhone)
                put("additional_phone", additionalPhone)
                put("additional_phone_label", additionalPhoneLabel)
                put("whatsapp", whatsapp)
                put("updated_at", System.currentTimeMillis())
            }
            return writableDatabase.update("clients", values, "id = ?", arrayOf(id.toString())) == 1
        }

        fun updateClientFromEdit(
            id: Long,
            identifyingName: String,
            surname: String,
            phonesJson: String,
            mainPhone: String,
            additionalPhone: String,
            additionalPhoneLabel: String
        ): Boolean {
            val values = ContentValues().apply {
                put("identifying_name", identifyingName)
                put("surname", surname)
                put("main_phone", mainPhone)
                put("additional_phone", additionalPhone)
                put("additional_phone_label", additionalPhoneLabel)
                put("phones_json", phonesJson)
                put("updated_at", System.currentTimeMillis())
            }
            return writableDatabase.update("clients", values, "id = ?", arrayOf(id.toString())) == 1
        }

        data class GoogleSyncFailure(val clientId: Long, val contactId: Long?, val name: String, val surname: String, val phonesJson: String, val firstAttemptAt: Long, val attempt: Int, val error: String)

        fun getGoogleSyncFailure(clientId: Long): GoogleSyncFailure? {
            readableDatabase.query("google_sync_failures", arrayOf("client_id","contact_id","name","surname","phones_json","first_attempt_at","attempt","error"), "client_id = ?", arrayOf(clientId.toString()), null, null, null, "1").use { c ->
                if (!c.moveToFirst()) return null
                return GoogleSyncFailure(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getString(4).orEmpty(), c.getLong(5), c.getInt(6), c.getString(7).orEmpty())
            }
        }

        fun upsertGoogleSyncFailure(clientId: Long, contactId: Long?, name: String, surname: String, phonesJson: String, firstAttemptAt: Long, attempt: Int, error: String) {
            val v = ContentValues().apply { put("client_id", clientId); if (contactId == null) putNull("contact_id") else put("contact_id", contactId); put("name", name); put("surname", surname); put("phones_json", phonesJson); put("first_attempt_at", firstAttemptAt); put("attempt", attempt); put("error", error); put("updated_at", System.currentTimeMillis()) }
            writableDatabase.insertWithOnConflict("google_sync_failures", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }

        fun deleteGoogleSyncFailure(clientId: Long) { writableDatabase.delete("google_sync_failures", "client_id = ?", arrayOf(clientId.toString())) }

        fun updatePhonesJsonOnly(id: Long, phonesJson: String): Boolean {
            val values = ContentValues().apply {
                put("phones_json", phonesJson)
                put("updated_at", System.currentTimeMillis())
            }
            return writableDatabase.update("clients", values, "id = ?", arrayOf(id.toString())) == 1
        }

        fun getPhonesJson(id: Long): String {
            readableDatabase.query("clients", arrayOf("phones_json"), "id = ?", arrayOf(id.toString()), null, null, null, "1").use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return "[]"
                return cursor.getString(0).orEmpty().ifBlank { "[]" }
            }
        }

        fun getClient(id: Long): ClientRecord? {
            readableDatabase.query(
                "clients",
                arrayOf("id", "identifying_name", "surname", "main_phone", "additional_phone", "additional_phone_label", "whatsapp", "phone_contact_id", "google_resource_name", "main_phone_source"),
                "id = ?",
                arrayOf(id.toString()),
                null,
                null,
                null,
                "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                return ClientRecord(
                    id = cursor.getLong(0),
                    identifyingName = cursor.getString(1).orEmpty(),
                    surname = cursor.getString(2).orEmpty(),
                    mainPhone = cursor.getString(3).orEmpty(),
                    additionalPhone = cursor.getString(4).orEmpty(),
                    additionalPhoneLabel = cursor.getString(5).orEmpty().ifBlank { "Cell phone" },
                    whatsapp = cursor.getString(6).orEmpty(),
                    phoneContactId = if (cursor.isNull(7)) null else cursor.getString(7),
                    googleResourceName = if (cursor.isNull(8)) null else cursor.getString(8),
                    mainPhoneSource = if (cursor.isNull(9)) "LOCAL" else cursor.getString(9).orEmpty().ifBlank { "LOCAL" }
                )
            }
        }

        fun getPhonesJsonByPhoneContactId(phoneContactId: String): String {
            readableDatabase.query(
                "clients", arrayOf("phones_json"), "phone_contact_id = ?", arrayOf(phoneContactId),
                null, null, "id ASC", "1"
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return "[]"
                return cursor.getString(0).orEmpty().ifBlank { "[]" }
            }
        }

        fun getClientByPhoneContactId(phoneContactId: String): ClientRecord? {
            readableDatabase.query(
                "clients",
                arrayOf("id", "identifying_name", "surname", "main_phone", "additional_phone", "additional_phone_label", "whatsapp", "phone_contact_id", "google_resource_name", "main_phone_source"),
                "phone_contact_id = ?",
                arrayOf(phoneContactId),
                null, null, "id ASC", "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                return ClientRecord(
                    id = cursor.getLong(0),
                    identifyingName = cursor.getString(1).orEmpty(),
                    surname = cursor.getString(2).orEmpty(),
                    mainPhone = cursor.getString(3).orEmpty(),
                    additionalPhone = cursor.getString(4).orEmpty(),
                    additionalPhoneLabel = cursor.getString(5).orEmpty().ifBlank { "Cell phone" },
                    whatsapp = cursor.getString(6).orEmpty(),
                    phoneContactId = if (cursor.isNull(7)) null else cursor.getString(7),
                    googleResourceName = if (cursor.isNull(8)) null else cursor.getString(8),
                    mainPhoneSource = if (cursor.isNull(9)) "LOCAL" else cursor.getString(9).orEmpty().ifBlank { "LOCAL" }
                )
            }
        }

        fun getGoogleResourceName(id: Long): String? = getClient(id)?.googleResourceName
    }

    private data class CaptureSnapshot(
        val identifyingName: String,
        val surname: String,
        val mainPhone: String,
        val additionalPhone: String,
        val whatsapp: String,
        val additionalPhoneLabelIndex: Int,
        val customAdditionalPhoneLabel: String,
        val selectedContactId: Long,
        val expandedContactId: Long?,
        val contactAreaHidden: Boolean,
        val selectedReferenceHidden: Boolean,
        val additionalPhones: List<EditablePhone> = emptyList()
    )

    private fun setAdditionalPhoneLabel(label: String) {
        val normalized = label.trim()
        customAdditionalPhoneLabel = if (normalized.isNotBlank() && normalized !in listOf("New", "Custom", "Cell", "Work", "Home")) normalized else ""
        setAdditionalPhoneLabelIndex(labelIndexFor(normalized))
    }

    private fun setAdditionalPhoneLabelIndex(index: Int) {
        updatingAdditionalPhoneLabelSpinner = true
        if (additionalPhoneLabelSpinner.adapter == null) additionalPhoneLabelSpinner.adapter = buildAdditionalPhoneLabelAdapter()
        val safe = if (additionalPhoneLabelSpinner.count > 0) index.coerceIn(0, additionalPhoneLabelSpinner.count - 1) else 0
        additionalPhoneLabelSpinner.setSelection(safe)
        lastAdditionalPhoneLabelIndex = safe
        updatingAdditionalPhoneLabelSpinner = false
    }

    private fun labelIndexFor(label: String): Int {
        val value = label.trim()
        val labels = (0 until additionalPhoneLabelSpinner.count).map { additionalPhoneLabelSpinner.getItemAtPosition(it).toString() }
        return labels.indexOfFirst { it.equals(value, true) }.takeIf { it >= 0 } ?: 0
    }

    private fun googlePhoneType(label: String): String = when (label.trim().lowercase(Locale.getDefault())) {
        "home" -> "home"
        "work" -> "work"
        "cell", "cell phone", "mobile" -> "mobile"
        "pager" -> "pager"
        "workmobile" -> "workMobile"
        "workpager" -> "workPager"
        "homefax" -> "homeFax"
        "workfax" -> "workFax"
        "otherfax" -> "otherFax"
        "custom", "new", "" -> "custom"
        // Google People API accepts a custom string for PhoneNumber.type. Sending the
        // actual human label here is required; sending only "custom" loses labels such
        // as Sister, Husband, Airport, etc.
        else -> label.trim()
    }

    private data class PhoneEntry(val number: String, val label: String)
    private data class PhoneContact(val id: Long, val displayName: String, val givenName: String, val familyName: String, val note: String, val phones: List<PhoneEntry>)
}
