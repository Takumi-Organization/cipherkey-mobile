package dev.cipherkey.mobile.autofill

import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.os.CancellationSignal

class CipherKeyAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) = callback.onSuccess(null)
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()
}
