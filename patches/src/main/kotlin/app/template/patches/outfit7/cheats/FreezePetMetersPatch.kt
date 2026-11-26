package app.template.patches.outfit7.cheats

import app.morphe.patcher.patch.rawResourcePatch
import app.template.patches.shared.Constants.COMPATIBILITY_MY_TALKING_TOM

/**
 * Site in `libil2cpp.so` for 26.5.4.9664 (arm64): MainGameLogic.RecalculateMeters (RVA 0x23bccf8,
 * file offset 0x23b8cf8), the bool method that applies the elapsed time to the pet's meters. Its
 * early exits already return false (`mov w0, wzr`), so the first two instructions (`str d8,
 * [sp, #-0x30]!; str x30, [sp, #8]`, before any stack use) become `mov w0, wzr; ret`: the meters
 * are frozen. Its four callees (0x23bda30, 0x23bdcf8, 0x23bddcc, 0x23bdfac) are only called from
 * it, and they also do the time-based recovery and the automatic waking, so the freeze covers
 * those too: no recovery over time while asleep or on the toilet, Tom does not wake by himself,
 * away time is ignored and the must-eat/must-sleep events never fire (what the player sees is
 * INFERRED; the meter names food/fun/sleep/toilet are inferred from the four meter fields
 * 0xac/0xb0/0xb4/0xb8, the structure is verified). SetLastTimeCalculation is never advanced, so a
 * later unpatched build applies the whole frozen gap at once.
 *
 * The 16-byte prologue is shared by 135 functions, so the window runs on through the class-init
 * guard to the first field access (15 instructions). The page-relative `adrp`, load/store offsets,
 * the `tbnz` distance and the `bl` distance are masked (they move with every build); the 15th
 * instruction (`adrp x0` here, `mov w8, #1` in the sibling function that shares the prologue)
 * is what makes the match unique, and the hit must also sit at file offset 0x23b8cf8. Verified by disassembly only, runtime NOT_RUN.
 */
internal val PET_METERS_SITE = arm64Site(
    label = "MainGameLogic.RecalculateMeters",
    insns = listOf(
        insn("e80f1dfc"), // str d8, [sp, #-0x30]!
        insn("fe0700f9"), // str x30, [sp, #8]
        insn("f65701a9"), // stp x22, x21, [sp, #0x10]
        insn("f44f02a9"), // stp x20, x19, [sp, #0x20]
        insn("b6570090", MASK_ADRP), // adrp x22, <class statics page>
        insn("d5490090", MASK_ADRP), // adrp x21, <metadata page>
        insn("f303012a"), // mov w19, w1
        insn("c86a5639", MASK_LDST_UIMM), // ldrb w8, [x22, #<init flag>]
        insn("b56e41f9", MASK_LDST_UIMM), // ldr x21, [x21, #<metadata slot>]
        insn("f40300aa"), // mov x20, x0
        insn("28010037", 0xFFF8001FL), // tbnz w8, #0, <already initialised>
        insn("c0490090", MASK_ADRP), // adrp x0, <metadata page>
        insn("006c41f9", MASK_LDST_UIMM), // ldr x0, [x0, #<metadata slot>]
        insn("fdfeba97", MASK_BL), // bl <class-init helper>
        insn("a04900d0", MASK_ADRP), // adrp x0, <metadata page>
    ),
    patchInsn = 0,
    replacementHex = "e0031f2ac0035fd6", // mov w0, wzr; ret
    windowOffset = 0x23b8cf8,
)

@Suppress("unused")
val freezePetMetersPatch = rawResourcePatch(
    name = "Freeze pet meters",
    description = "Keeps the pet's food, fun, sleep and toilet meters where they are.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_MY_TALKING_TOM)

    execute {
        patchLibil2cpp(get(LIBIL2CPP_ARM64), listOf(PET_METERS_SITE))
    }
}
