package com.wunderhand.app.features.clients

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.result.contract.ActivityResultContract
import com.wunderhand.core.PickedContact

/**
 * One number, handed over by the person holding the phone.
 *
 * The system's own picker grants a read of the row that was picked and nothing
 * else — which is why the app asks for no contacts permission and has nothing
 * to declare about contacts: it cannot see the address book, only the one line
 * somebody chose to give it.
 *
 * It is a *number* that is picked rather than a contact, on purpose. Android's
 * grant for a picked contact covers the contact's own row — a name — and not
 * its numbers or addresses; reading those takes the permission to read every
 * contact on the phone (tried: a SecurityException). A picked number comes with
 * the name and the number, which is what a shop knows a client by — and it is
 * the number *they* chose, not one the app guessed from a label. The email and
 * the date of birth are typed, as they would be anyway for most people in a
 * phone's contacts.
 *
 * Nothing is kept: what is read fills a form that is still theirs to check.
 */
object ContactReader {
    /** "Choose a contact", listing each person's numbers. */
    class PickNumber : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_PICK).setType(Phone.CONTENT_TYPE)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
    }

    fun read(context: Context, number: Uri): PickedContact? = runCatching {
        context.contentResolver.query(number, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE), null, null, null)?.use { row ->
            if (!row.moveToFirst()) return@use null
            PickedContact(
                // The address book's name for them, whole: it is not the app's to split into given and family.
                givenName = row.getString(0).orEmpty(),
                phones = listOf(PickedContact.Labelled(label(row.getInt(2)), row.getString(1).orEmpty())),
            )
        }
    }.getOrNull()

    private fun label(type: Int) = when (type) {
        Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> "mobile"
        Phone.TYPE_WORK -> "work"
        Phone.TYPE_HOME -> "home"
        else -> null
    }
}
