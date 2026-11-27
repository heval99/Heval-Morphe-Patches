package app.template.extension.truecaller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class AfterCallSavedContactsTest {
    private static final class FakeContacts implements AfterCallSavedContacts.Contacts {
        final Set<String> saved = new HashSet<>();
        final List<String> asked = new ArrayList<>();
        boolean permitted = true;
        RuntimeException failure;

        FakeContacts saved(String... numbers) {
            saved.addAll(Arrays.asList(numbers));
            return this;
        }

        @Override
        public boolean canRead() {
            if (failure != null) throw failure;
            return permitted;
        }

        @Override
        public boolean has(String number) {
            asked.add(number);
            if (failure != null) throw failure;
            return saved.contains(number);
        }
    }

    @Test
    public void featureOffNeverAsks() {
        FakeContacts contacts = new FakeContacts().saved("+4512345678");
        assertFalse(AfterCallSavedContacts.isSaved(false, contacts, "+4512345678", "12345678"));
        assertTrue(contacts.asked.isEmpty());
    }

    @Test
    public void blankNumbersAreNotSaved() {
        FakeContacts contacts = new FakeContacts().saved("", " ");
        assertFalse(AfterCallSavedContacts.isSaved(true, contacts, null, null));
        assertFalse(AfterCallSavedContacts.isSaved(true, contacts, "", " "));
        assertTrue(contacts.asked.isEmpty());
    }

    @Test
    public void permissionDeniedIsNotSaved() {
        FakeContacts contacts = new FakeContacts().saved("+4512345678");
        contacts.permitted = false;
        assertFalse(AfterCallSavedContacts.isSaved(true, contacts, "+4512345678", null));
        assertTrue(contacts.asked.isEmpty());
    }

    @Test
    public void normalizedNumberIsTriedFirst() {
        FakeContacts contacts = new FakeContacts().saved("+4512345678", "12345678");
        assertTrue(AfterCallSavedContacts.isSaved(true, contacts, "+4512345678", "12345678"));
        assertEquals(Arrays.asList("+4512345678"), contacts.asked);
    }

    @Test
    public void rawNumberIsTheFallback() {
        FakeContacts contacts = new FakeContacts().saved("12345678");
        assertTrue(AfterCallSavedContacts.isSaved(true, contacts, "+4512345678", "12345678"));
        assertEquals(Arrays.asList("+4512345678", "12345678"), contacts.asked);
        assertTrue(AfterCallSavedContacts.isSaved(true, contacts, null, "12345678"));
    }

    @Test
    public void unknownNumberIsNotSaved() {
        FakeContacts contacts = new FakeContacts().saved("999");
        assertFalse(AfterCallSavedContacts.isSaved(true, contacts, "+4512345678", "12345678"));
    }

    @Test
    public void sameNumberIsAskedOnce() {
        FakeContacts contacts = new FakeContacts();
        assertFalse(AfterCallSavedContacts.isSaved(true, contacts, "12345678", "12345678"));
        assertEquals(Arrays.asList("12345678"), contacts.asked);
    }

    private static boolean suppress(boolean contactsOn, boolean missedToo, FakeContacts contacts, int type, boolean phonebook) {
        return AfterCallSavedContacts.shouldSuppress(contactsOn, missedToo, contacts, type, phonebook, "+4512345678", "12345678");
    }

    @Test
    public void suppressNeedsTheContactsSwitch() {
        FakeContacts contacts = new FakeContacts().saved("+4512345678");
        assertFalse(suppress(false, true, contacts, 1, true));
        assertTrue(contacts.asked.isEmpty());
    }

    @Test
    public void phonebookContactIsSuppressedWithoutALookup() {
        FakeContacts contacts = new FakeContacts();
        assertTrue(suppress(true, false, contacts, 1, true));
        assertTrue(suppress(true, false, contacts, 2, true));
        assertTrue(contacts.asked.isEmpty());
    }

    @Test
    public void androidLookupCatchesContactsTruecallerMissed() {
        FakeContacts contacts = new FakeContacts().saved("12345678");
        assertTrue(suppress(true, false, contacts, 1, false));
        assertFalse(suppress(true, false, new FakeContacts(), 2, false));
    }

    @Test
    public void missedCallsNeedTheirOwnSwitch() {
        FakeContacts contacts = new FakeContacts().saved("+4512345678");
        assertFalse(suppress(true, false, contacts, 3, true));
        assertTrue(contacts.asked.isEmpty());
        assertTrue(suppress(true, true, contacts, 3, true));
        assertTrue(suppress(true, true, contacts, 3, false));
        assertFalse(suppress(true, true, new FakeContacts(), 3, false));
    }

    @Test
    public void otherCallTypesAreLeftToTheStockRules() {
        FakeContacts contacts = new FakeContacts().saved("+4512345678");
        assertFalse(suppress(true, true, contacts, 5, true));
        assertFalse(suppress(true, true, contacts, 0, true));
    }

    @Test
    public void missedFullScreenBehaviourNeedsBothSwitches() {
        assertTrue(AfterCallSavedContacts.hideMissed(true, true));
        assertFalse(AfterCallSavedContacts.hideMissed(true, false));
        assertFalse(AfterCallSavedContacts.hideMissed(false, true));
        assertFalse(AfterCallSavedContacts.hideMissed(false, false));
    }

    @Test
    public void lookupMemoKeepsAnAnswerForItsLifetime() {
        AfterCallSavedContacts.LookupMemo memo = new AfterCallSavedContacts.LookupMemo(5_000, 2);
        assertEquals(null, memo.get("a", 0));
        memo.put("a", true, 1_000);
        assertEquals(Boolean.TRUE, memo.get("a", 5_999));
        assertEquals(null, memo.get("a", 6_000));
        assertEquals(null, memo.get("a", 500));
        memo.put("b", false, 1_000);
        assertEquals(Boolean.FALSE, memo.get("b", 1_001));
    }

    @Test
    public void lookupMemoForgetsTheOldestWhenFull() {
        AfterCallSavedContacts.LookupMemo memo = new AfterCallSavedContacts.LookupMemo(5_000, 2);
        memo.put("a", true, 0);
        memo.put("b", true, 0);
        memo.put("c", false, 0);
        assertEquals(null, memo.get("a", 1));
        assertEquals(Boolean.TRUE, memo.get("b", 1));
        assertEquals(Boolean.FALSE, memo.get("c", 1));
    }

    @Test
    public void failureIsNotSaved() {
        FakeContacts contacts = new FakeContacts().saved("+4512345678");
        contacts.failure = new SecurityException("denied");
        assertFalse(AfterCallSavedContacts.isSaved(true, contacts, "+4512345678", null));
        assertFalse(AfterCallSavedContacts.isSaved(true, null, "+4512345678", null));
    }
}
