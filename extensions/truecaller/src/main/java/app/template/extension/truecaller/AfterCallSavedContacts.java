package app.template.extension.truecaller;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

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
    static final String MISSED_FEATURE = "truecaller.hide_after_call_missed_contacts";
    private static final String TAG = "Morphe-Truecaller";
    // HistoryEvent.type values (verified in the caller-ID rules, 26.45.5).
    static final int TYPE_INCOMING = 1;
    static final int TYPE_OUTGOING = 2;
    static final int TYPE_MISSED = 3;

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

    /**
     * Entry hook of the after-call rules (issue #181): true means "do not show the after-call screen".
     * Called with the history event's call type, whether Truecaller's own store calls it a phonebook
     * contact, and its normalized and raw number. Missed calls (type 3) show the screen by design, so
     * they are suppressed only while "Also hide after-call for missed calls from contacts" is on.
     */
    public static boolean shouldSuppress(int type, boolean phonebook, String normalized, String raw) {
        try {
            // Silent while the switch is off: this runs for every call.
            if (!HevalSettings.isEnabled(FEATURE)) return false;
            boolean missedToo = HevalSettings.isEnabled(MISSED_FEATURE);
            return shouldSuppress(true, missedToo, new AndroidContacts(), type, phonebook, normalized, raw);
        } catch (Throwable failure) {
            log("call type=" + type + " failed: " + failure.getClass().getName() + " -> stock");
            return false;
        }
    }

    static boolean shouldSuppress(boolean contactsOn, boolean missedToo, Contacts contacts, int type,
                                  boolean phonebook, String normalized, String raw) {
        if (!contactsOn) return false;
        // Incoming answered, outgoing and missed only; anything else is left to the stock rules.
        if (type != TYPE_INCOMING && type != TYPE_OUTGOING && type != TYPE_MISSED) return false;
        if (type == TYPE_MISSED && !missedToo) {
            log("call type=" + type + " phonebook=" + phonebook + " missed switch off -> stock");
            return false;
        }
        boolean lookup = !phonebook && isSaved(true, contacts, normalized, raw);
        boolean suppress = phonebook || lookup;
        log("call type=" + type + " phonebook=" + phonebook + " lookup=" + lookup + " -> "
                + (suppress ? "suppress" : "stock"));
        return suppress;
    }

    /**
     * Gate of the two full-screen *PbMissed behaviour getters (default-dialer path): true (answer
     * DO_NOTHING) only while the contacts switch and the missed-call switch are both on.
     */
    public static boolean hideMissedBehavior() {
        try {
            boolean hide = hideMissed(HevalSettings.isEnabled(FEATURE), HevalSettings.isEnabled(MISSED_FEATURE));
            if (hide) log("full-screen behaviour for a missed call from a contact -> do nothing");
            return hide;
        } catch (Throwable failure) {
            return false;
        }
    }

    static boolean hideMissed(boolean contactsOn, boolean missedOn) {
        return contactsOn && missedOn;
    }

    /** Never throws and never logs a number. */
    private static void log(String message) {
        try {
            Log.d(TAG, message);
        } catch (Throwable ignored) {
            // Plain JVM tests have no android.util.Log.
        }
    }

    static boolean isSaved(boolean featureOn, Contacts contacts, String normalized, String raw) {
        if (!featureOn || contacts == null) return false;
        if (isBlank(normalized) && isBlank(raw)) return false;
        try {
            if (!contacts.canRead()) {
                log("lookup skipped: READ_CONTACTS not granted");
                return false;
            }
            if (!isBlank(normalized) && contacts.has(normalized)) return true;
            return !isBlank(raw) && !raw.equals(normalized) && contacts.has(raw);
        } catch (RuntimeException failure) {
            log("lookup failed: " + failure.getClass().getName());
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * The last lookup answers, kept for a few seconds: one call end asks the same number up to three
     * times (the rules' two contact tests and the entry hook). Keyed by number, never logged.
     */
    static final class LookupMemo {
        private final long ttlMillis;
        private final int size;
        private final java.util.LinkedHashMap<String, long[]> entries = new java.util.LinkedHashMap<>();

        LookupMemo(long ttlMillis, int size) {
            this.ttlMillis = ttlMillis;
            this.size = size;
        }

        /** The remembered answer, or null when there is none or it is older than the lifetime. */
        synchronized Boolean get(String number, long now) {
            long[] entry = entries.get(number);
            if (entry == null || now < entry[1] || now - entry[1] >= ttlMillis) return null;
            return entry[0] == 1;
        }

        synchronized void put(String number, boolean saved, long now) {
            if (entries.size() >= size && !entries.containsKey(number)) {
                entries.remove(entries.keySet().iterator().next());
            }
            entries.put(number, new long[] {saved ? 1 : 0, now});
        }
    }

    private static final class AndroidContacts implements Contacts {
        private static final LookupMemo MEMO = new LookupMemo(5_000, 8);
        private final Context context = AppContext.currentApplication();

        @Override
        public boolean canRead() {
            return context != null
                    && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public boolean has(String number) {
            long now = System.currentTimeMillis();
            Boolean known = MEMO.get(number, now);
            if (known != null) return known;
            boolean saved = query(number);
            // Only a finished lookup is remembered (a failure throws past this line).
            MEMO.put(number, saved, now);
            return saved;
        }

        private boolean query(String number) {
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
