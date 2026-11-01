package ch.asds.mobile

import android.os.Bundle
import android.view.View
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Additive, fully offline native R1 time entry. The legacy web screens, capture
 * uploads, biometric lock and queues are deliberately retained.
 */
class R1CaptureActivity : AppCompatActivity() {
    private lateinit var screen: ScrollView
    private var authenticated = false
    private var promptActive = false
    private lateinit var store: R1Store
    private lateinit var form: LinearLayout
    private lateinit var amount: TextView
    private lateinit var statusList: LinearLayout
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var billable: CheckBox
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = R1Store(this)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 40)
        }
        scroll.addView(body)
        screen = scroll
        if (AppSettings.biometricEnabled(this)) screen.visibility = View.INVISIBLE
        setContentView(scroll)
        body.addView(title("ASDS Mobile 2.0.1 – R1 Erfassung"))
        body.addView(note("Offline erfassen · serverseitige Freigabe · kein automatisches Überschreiben"))
        form = body
        addField("work_date", "Datum (JJJJ-MM-TT)", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        addField("customer_id", "Kunden-ID", "", InputType.TYPE_CLASS_NUMBER)
        addLookup("Kunde aus Stammdaten wählen", true)
        addField("appointment_id", "Termin-ID (optional)", "", InputType.TYPE_CLASS_NUMBER)
        addLookup("Termin verknüpfen", false)
        addField("start_time", "Von (HH:MM, optional)")
        addField("end_time", "Bis (HH:MM, optional)")
        addField("duration_minutes", "Dauer (Minuten)", "60", InputType.TYPE_CLASS_NUMBER)
        addField("block_code", "Leistungsblock", "BEGLEITUNG")
        addField("description", "Beschreibung")
        billable = CheckBox(this).apply {
            text = "Leistung verrechenbar"
            isChecked = true
            setOnCheckedChangeListener { _, _ -> autosave() }
        }
        body.addView(billable)
        addField("billable_km", "Abrechenbare Kilometer", "0.00",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        addField("km_rate", "Kilometeransatz CHF/km", "0.70",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        amount = note("Kilometerbetrag: CHF 0.00")
        body.addView(amount)
        addButton("Erfassungsvorlage laden") { chooseTemplate() }
        addButton("Als Vorlage speichern") {
            val edit = EditText(this).apply { hint = "Vorlagenname"; setSingleLine(true) }
            AlertDialog.Builder(this).setTitle("Neue Erfassungsvorlage").setView(edit)
                .setPositiveButton("Speichern") { _, _ ->
                    try {
                        store.saveTemplate(edit.text.toString().trim(), readForm())
                        Toast.makeText(this, "Vorlage gespeichert", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(this, "Name fehlt", Toast.LENGTH_SHORT).show() }
                }.setNegativeButton("Abbrechen", null).show()
        }
        addButton("Offline speichern und zur Übertragung vormerken") { saveEntry() }
        addButton("R1-Synchronisation anfordern") {
            WorkerScheduler.retryNow(this)
            Toast.makeText(this, "Synchronisation bei bestehender Verbindung vorgemerkt", Toast.LENGTH_LONG).show()
            refreshQueue()
        }
        body.addView(title("Übertragungsstatus"))
        body.addView(note("Konflikte und fachliche Sperren werden nicht automatisch wiederholt."))
        statusList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(statusList)
        addButton("Status aktualisieren") { refreshQueue() }
        val draft = store.draft()
        loading = true
        restore(draft)
        loading = false
        updateAmount()
        refreshQueue()
        // Refresh server-scoped selectors without blocking the UI; cached selectors work offline.
        Thread { R1Client.refreshLookups(store) }.start()
    }

    override fun onResume() {
        super.onResume()
        if (!AppSettings.biometricEnabled(this)) {
            screen.visibility = View.VISIBLE
        } else if (!authenticated && !promptActive) {
            authenticateR1()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!promptActive) {
            authenticated = false
            screen.visibility = View.INVISIBLE
        }
    }

    private fun authenticateR1() {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            finish()
            return
        }
        promptActive = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticated = true
                    promptActive = false
                    screen.visibility = View.VISIBLE
                }
                override fun onAuthenticationError(code: Int, errString: CharSequence) {
                    promptActive = false
                    finish()
                }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("ASDS R1-Zeiterfassung entsperren")
            .setAllowedAuthenticators(allowed)
            .build())
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text; textSize = 20f; setTypeface(null, Typeface.BOLD); setPadding(0, 18, 0, 12)
    }
    private fun note(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setPadding(0, 6, 0, 12)
    }
    private fun addButton(label: String, onClick: () -> Unit) {
        form.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
    }

    private fun addField(key: String, label: String, initial: String = "",
                         type: Int = InputType.TYPE_CLASS_TEXT) {
        form.addView(note(label))
        val input = EditText(this).apply {
            hint = label
            setSingleLine(key != "description")
            inputType = if (key == "description") InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
            setText(initial)
        }
        fields[key] = input
        form.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { autosave(); updateAmount() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun addLookup(label: String, customers: Boolean) {
        addButton(label) {
            val array = if (customers) store.customers() else store.appointments()
            val labels = mutableListOf<String>()
            val ids = mutableListOf<Int>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.has("deleted_at") && !item.isNull("deleted_at")) continue
                if (customers && item.optInt("active", 1) == 0) continue
                val id = item.optInt(if (customers) "customer_id" else "appointment_id")
                if (id <= 0) continue
                ids.add(id)
                labels.add(id.toString() + " · " + item.optString(if (customers) "name" else "label", ""))
            }
            if (ids.isEmpty()) {
                Toast.makeText(this, "Noch keine berechtigten Stammdaten lokal gespeichert", Toast.LENGTH_LONG).show()
            } else AlertDialog.Builder(this).setTitle(label).setItems(labels.toTypedArray()) { _, which ->
                fields[if (customers) "customer_id" else "appointment_id"]?.setText(ids[which].toString())
                if (!customers) {
                    val app = array.optJSONObject((0 until array.length()).firstOrNull { array.optJSONObject(it)?.optInt("appointment_id") == ids[which] } ?: 0)
                    val soc = app?.optInt("customer_id") ?: 0
                    if (soc > 0) fields["customer_id"]?.setText(soc.toString())
                }
            }.setNegativeButton("Abbrechen", null).show()
        }
    }

    private fun readForm(): JSONObject {
        val out = JSONObject()
        fields.forEach { (key, input) -> out.put(key, input.text.toString().trim()) }
        out.put("billable", if (billable.isChecked) 1 else 0)
        return out
    }

    private fun restore(data: JSONObject) {
        fields.forEach { (key, input) ->
            if (data.has(key)) input.setText(data.optString(key))
        }
        if (data.has("billable")) billable.isChecked = data.optInt("billable", 1) == 1
    }

    private fun autosave() {
        if (!loading && ::billable.isInitialized) store.saveDraft(readForm())
    }

    private fun updateAmount() {
        if (!::amount.isInitialized) return
        amount.text = try {
            "Kilometerbetrag: CHF " + R1Amounts.kmAmount(
                fields["billable_km"]?.text?.toString().orEmpty(),
                fields["km_rate"]?.text?.toString().orEmpty()
            )
        } catch (_: Exception) { "Kilometerbetrag: Eingaben prüfen" }
    }

    private fun chooseTemplate() {
        val options = store.templates()
        val labels = (0 until options.length()).map { options.optJSONObject(it)?.optString("label") ?: "Vorlage" }
        if (labels.isEmpty()) {
            Toast.makeText(this, "Noch keine Vorlagen gespeichert", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Vorlage wählen").setItems(labels.toTypedArray()) { _, which ->
            loading = true
            options.optJSONObject(which)?.optJSONObject("fields")?.let { restore(it) }
            loading = false
            autosave()
            updateAmount()
        }.show()
    }

    private fun saveEntry() {
        try {
            val p = readForm()
            require(p.optString("work_date").matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Datum prüfen" }
            require(p.optInt("customer_id") > 0) { "Kunde wählen" }
            require(p.optInt("duration_minutes") > 0) { "Dauer muss grösser als 0 sein" }
            require(p.optString("block_code").isNotBlank()) { "Leistungsblock fehlt" }
            val payload = JSONObject().put("work_date", p.optString("work_date"))
                .put("customer_id", p.optInt("customer_id"))
                .put("duration_minutes", p.optInt("duration_minutes"))
                .put("block_code", p.optString("block_code"))
                .put("description", p.optString("description"))
                .put("billable", p.optInt("billable"))
                .put("billable_km", R1Amounts.decimal(p.optString("billable_km"), 2))
                .put("km_rate", R1Amounts.decimal(p.optString("km_rate"), 2))
            for (key in listOf("start_time", "end_time")) {
                if (p.optString(key).isNotBlank()) {
                    require(p.optString(key).matches(Regex("\\d{2}:\\d{2}"))) { "Zeitformat HH:MM prüfen" }
                    payload.put(key, p.optString(key))
                }
            }
            val appointmentId = p.optInt("appointment_id")
            if (appointmentId > 0) payload.put("appointment_id", appointmentId)
            val sourceKey = store.enqueueTime(payload)
            store.clearDraft()
            Toast.makeText(this, "Lokal gesichert: " + sourceKey.take(8), Toast.LENGTH_LONG).show()
            loading = true
            fields["description"]?.setText("")
            loading = false
            WorkerScheduler.retryNow(this)
            refreshQueue()
        } catch (error: Exception) {
            AlertDialog.Builder(this).setTitle("Erfassung nicht gespeichert")
                .setMessage(error.message ?: "Eingaben prüfen").setPositiveButton("OK", null).show()
        }
    }

    private fun refreshQueue() {
        statusList.removeAllViews()
        val queue = store.queue()
        if (queue.length() == 0) { statusList.addView(note("Noch keine R1-Übertragungen.")); return }
        for (i in queue.length() - 1 downTo 0) {
            val row = queue.optJSONObject(i) ?: continue
            val key = row.optString("source_key")
            val status = row.optString("status")
            val error = row.optString("last_error")
            statusList.addView(note(key.take(8) + " · " + status +
                (if (row.has("server_id")) " · ERP #" + row.optInt("server_id") else "") +
                (if (error.isNotBlank()) "\n" + error else "")))
            if (status == "conflict" || status == "failed") {
                val copy = JSONObject(row.toString())
                addQueueReviewButton(copy)
            }
        }
    }

    private fun addQueueReviewButton(row: JSONObject) {
        statusList.addView(Button(this).apply {
            text = "Fehler prüfen / als neuen Entwurf übernehmen"
            setOnClickListener {
                AlertDialog.Builder(this@R1CaptureActivity)
                    .setTitle("Manuelle Konfliktprüfung")
                    .setMessage("Originaleintrag bleibt unverändert. Der Inhalt wird als neuer Entwurf geöffnet; eine neue Übertragung erhält später eine neue ID. Vorher ERP-Daten prüfen.")
                    .setPositiveButton("Entwurf öffnen") { _, _ ->
                        loading = true
                        row.optJSONObject("item")?.optJSONObject("payload")?.let { restore(it) }
                        loading = false
                        autosave()
                    }.setNegativeButton("Abbrechen", null).show()
            }
        })
    }
}
