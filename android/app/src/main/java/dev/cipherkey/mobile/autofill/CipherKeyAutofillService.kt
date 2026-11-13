package dev.cipherkey.mobile.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import dev.cipherkey.mobile.R

/**
 * Proveedor de autocompletado. Nunca devuelve datos directamente: responde con una AUTENTICACIÓN que abre
 * [AutofillAuthActivity] (desbloqueo biométrico o maestra, luego elección del registro). Así ningún secreto sale
 * mientras la bóveda está bloqueada y este servicio no necesita acceder a las claves. No se escribe nada en logs.
 */
class CipherKeyAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) { callback.onSuccess(null); return }
        val parsed = parse(structure)
        // No se autocompleta sobre la propia app ni sin un campo de contraseña (evita rellenar formularios ajenos)
        if (parsed == null || parsed.passwordId == null || parsed.target.packageName == packageName) { callback.onSuccess(null); return }

        val intent = Intent(this, AutofillAuthActivity::class.java)
            .putExtra(AutofillAuthActivity.EXTRA_PACKAGE, parsed.target.packageName)
            .putExtra(AutofillAuthActivity.EXTRA_DOMAIN, parsed.target.webDomain)
            .putExtra(AutofillAuthActivity.EXTRA_SCHEME, parsed.target.webScheme)
            .putExtra(AutofillAuthActivity.EXTRA_USER_ID, parsed.usernameId)
            .putExtra(AutofillAuthActivity.EXTRA_PASS_ID, parsed.passwordId)
        // MUTABLE: el sistema añade el resultado de la autenticación al intent
        val pi = PendingIntent.getActivity(this, 1001, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        val pres = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.af_title, getString(R.string.app_name))
            setTextViewText(R.id.af_subtitle, getString(R.string.autofill_tap))
        }
        // Autenticación a nivel de Dataset: tras desbloquear y elegir, el sistema rellena directamente (sin segundo desplegable)
        val ds = Dataset.Builder(pres)
        parsed.usernameId?.let { ds.setValue(it, null) }
        ds.setValue(parsed.passwordId, null)
        ds.setAuthentication(pi.intentSender)
        callback.onSuccess(FillResponse.Builder().addDataset(ds.build()).build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    private class Parsed(val target: FillTarget, val usernameId: AutofillId?, val passwordId: AutofillId?)

    private fun parse(structure: AssistStructure): Parsed? {
        val pkg = structure.activityComponent?.packageName ?: return null
        var domain: String? = null
        var scheme: String? = null
        var userId: AutofillId? = null
        var lastUser: AutofillId? = null
        var passId: AutofillId? = null

        fun visit(n: AssistStructure.ViewNode) {
            if (domain == null && !n.webDomain.isNullOrEmpty()) {
                domain = n.webDomain
                if (Build.VERSION.SDK_INT >= 28) scheme = n.webScheme
            }
            val id = n.autofillId
            if (id != null && n.autofillType == android.view.View.AUTOFILL_TYPE_TEXT) {
                val kind = FieldClassifier.classify(n.autofillHints?.toList(), n.inputType, n.htmlInfo?.attributes?.firstOrNull { it.first == "type" }?.second, n.idEntry)
                when (kind) {
                    FieldKind.PASSWORD -> if (passId == null) { passId = id; userId = lastUser }
                    FieldKind.USERNAME -> if (passId == null) lastUser = id
                    FieldKind.OTHER -> {}
                }
            }
            for (i in 0 until n.childCount) visit(n.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
        return Parsed(FillTarget(pkg, domain, scheme), userId, passId)
    }
}
