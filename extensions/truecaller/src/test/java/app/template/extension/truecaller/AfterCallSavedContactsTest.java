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

    @Test
    public void failureIsNotSaved() {
        FakeContacts contacts = new FakeContacts().saved("+4512345678");
        contacts.failure = new SecurityException("denied");
        assertFalse(AfterCallSavedContacts.isSaved(true, contacts, "+4512345678", null));
        assertFalse(AfterCallSavedContacts.isSaved(true, null, "+4512345678", null));
    }
}
