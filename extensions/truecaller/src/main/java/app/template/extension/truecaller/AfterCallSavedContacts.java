package app.template.extension.truecaller;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import app.template.extension.hevalcontrols.AppContext;
import app.template.extension.hevalcontrols.HevalSettings;

/**
 * "Hide after-call screen for contacts": Truecaller counts a number as saved only when its own contact
 * store has an exact match, so a contact saved without a country code is treated as unknown and still
 * gets the after-call screen (issue #181). While the feature is on, the patched rule checks also ask
 * Android's contacts lookup, the one the dialer uses, whether the number is saved. Off, without the
 * contacts permission or on any error the answer is false and the stock rules decide.
 */
@SuppressWarnings("unused")
public final class AfterCallSavedContacts {
    static final String FEATURE = "truecaller.hide_after_call_contacts";

    /** What the lookup needs from Android; a seam so the logic runs in plain JVM tests. */
    interface Contacts {
        boolean canRead();

        /** Whether a saved contact has this number. */
        boolean has(String number);
    }

    private AfterCallSavedContacts() {
    }

    /** Called by the patch with the history event's normalized and raw number (either may be null). */
    public static boolean isSaved(String normalized, String raw) {
        try {
            if (!HevalSettings.isEnabled(FEATURE)) return false;
            return isSaved(true, new AndroidContacts(), normalized, raw);
        } catch (Throwable failure) {
            return false;
        }
    }

    static boolean isSaved(boolean featureOn, Contacts contacts, String normalized, String raw) {
        if (!featureOn || contacts == null) return false;
        if (isBlank(normalized) && isBlank(raw)) return false;
        try {
            if (!contacts.canRead()) return false;
            if (!isBlank(normalized) && contacts.has(normalized)) return true;
            return !isBlank(raw) && !raw.equals(normalized) && contacts.has(raw);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class AndroidContacts implements Contacts {
        private final Context context = AppContext.currentApplication();

        @Override
        public boolean canRead() {
            return context != null
                    && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public boolean has(String number) {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            Cursor cursor = context.getContentResolver().query(
                    uri, new String[] {ContactsContract.PhoneLookup._ID}, null, null, null);
            if (cursor == null) return false;
            try {
                return cursor.moveToNext();
            } finally {
                cursor.close();
            }
        }
    }
}
