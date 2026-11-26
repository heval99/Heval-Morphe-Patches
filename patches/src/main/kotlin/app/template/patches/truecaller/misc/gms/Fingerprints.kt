package app.template.patches.truecaller.misc.gms

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

// The launch activity (TruecallerInitAlias points at it). Its class is declared in the manifest,
// so R8 keeps the name; onCreate(Bundle) is a framework override.
internal object TruecallerInitOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/truecaller/ui/TruecallerInit;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

// The contact editor's "Save to" account list (#187) reads the device's real Google accounts with
// AccountManager.getAccountsByType("com.google") and maps the saved default account's type string to
// Google / Phone / SIM. The GmsCore string rewrite would turn that account type into
// "app.revanced", so no Google account is found and saving a contact can fail. R8 renames the
// classes (uc.B / uc.D in 26.45.5), so both are found by shape. Truecaller's sign-in and backup sites
// (getAccountsByType in other methods) keep the rewrite.
//
// The list builder: no-arg method returning ArrayList that reads "com.google" and calls
// getAccountsByType (the sign-in and backup callers return Z or Object).
internal object ContactEditorGoogleAccountsFingerprint : Fingerprint(
    returnType = "Ljava/util/ArrayList;",
    parameters = listOf(),
    strings = listOf("com.google"),
    filters = listOf(methodCall(definingClass = "Landroid/accounts/AccountManager;", name = "getAccountsByType")),
)

// The default account mapper: compares the saved account string with "phoneAccount", the SIM types
// and "com.google".
// ("phoneAccount" alone is also in a second coroutine, so "com.google" is part of the shape.)
internal object ContactEditorDefaultAccountFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf("phoneAccount", "com.google"),
)

// The contact details loader: one merged coroutine case reads a contact's raw "account_type" /
// "account_name" columns and compares the account type with "com.google" (is this contact stored in
// a Google account). The real contact rows carry the real type, so it must stay too.
internal object ContactAccountTypeReaderFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    strings = listOf("account_type", "account_name", "com.google"),
)
