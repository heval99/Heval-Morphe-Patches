package app.template.patches.outfit7.cheats

import app.morphe.patcher.patch.rawResourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_MY_TALKING_TOM

/**
 * Sites in `libil2cpp.so` for 26.5.4.9664 (arm64). They are the non-generic CurrencyAccount methods
 * that every coin, diamond and ticket purchase goes through (only the generic currency code calls
 * them; Debit has two direct callers: the spend function at 0x17a7c30 and the signed adjust at
 * 0x17a7cdc, which calls Debit(-amount) for a negative amount, so a negative adjustment such as a
 * backend correction is skipped too):
 *  - Debit (`balance -= x1; cumDebit += x1`, RVA 0x21ef45c, file offset 0x21eb45c): the first
 *    instruction becomes `ret`, so a purchase subtracts nothing.
 *  - CanDebit (`x1 == 0 ? true : balance >= x1`, RVA 0x21ef478): the leading `cbz x1, +0x14` becomes
 *    `b +0x14`, the existing `mov w0, #1; ret` tail, so the answer is always "can pay".
 * Only the local balance object is touched; in-app purchase receipts, Play billing and reward
 * videos are not. Verified by disassembly only, runtime NOT_RUN.
 */
internal val FREE_PURCHASE_SITES = listOf(
    arm64Site(
        label = "CurrencyAccount.Debit",
        insns = listOf(
            insn("080840f9"), // ldr x8, [x0, #0x10]
            insn("091040f9"), // ldr x9, [x0, #0x20]
            insn("080101cb"), // sub x8, x8, x1
            insn("2901018b"), // add x9, x9, x1
            insn("080800f9"), // str x8, [x0, #0x10]
            insn("091000f9"), // str x9, [x0, #0x20]
        ),
        patchInsn = 0,
        replacementHex = "c0035fd6", // ret
        windowOffset = 0x21eb45c,
    ),
    arm64Site(
        label = "CurrencyAccount.CanDebit",
        insns = listOf(
            insn("a10000b4"), // cbz x1, +0x14
            insn("080840f9"), // ldr x8, [x0, #0x10]
            insn("1f0101eb"), // cmp x8, x1
            insn("e0b79f1a"), // cset w0, ge
            insn("c0035fd6"), // ret
            insn("20008052"), // mov w0, #1
            insn("c0035fd6"), // ret
        ),
        patchInsn = 0,
        replacementHex = "05000014", // b +0x14
        windowOffset = 0x21eb478,
    ),
)

@Suppress("unused")
val freePurchasesPatch = rawResourcePatch(
    name = "Free purchases",
    description = "Makes everything you buy with coins, diamonds and tickets free.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_MY_TALKING_TOM)

    execute {
        patchLibil2cpp(get(LIBIL2CPP_ARM64), FREE_PURCHASE_SITES)
    }
}
