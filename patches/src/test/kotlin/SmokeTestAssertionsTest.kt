import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22b
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** These tests check the verifier itself, using deliberately incorrect instruction sequences. */
class SmokeTestAssertionsTest {
    private fun method(smali: String, returnType: String = "I"): MutableMethod =
        MutableMethod(
            ImmutableMethod(
                "Lprobe/ReturnValue;", "value", emptyList(), returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    4, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null,
                ),
            ),
        ).apply { addInstructions(0, smali) }

    @Test
    fun `integer assertion accepts each narrow constant encoding`() {
        for (constant in listOf("const/4 v2, 0x1", "const/16 v2, 0x1", "const v2, 0x1")) {
            assertReturnsInt(method("$constant\nreturn v2"), 1, constant)
        }
        assertReturnsInt(method("const/high16 v2, 0x10000\nreturn v2"), 65536, "high16")
    }

    @Test
    fun `integer assertion rejects arithmetic with the same literal`() {
        assertThrows<AssertionError> {
            assertReturnsInt(method("add-int/lit8 v0, v1, 0x1\nreturn v0"), 1, "arithmetic")
        }
    }

    @Test
    fun `integer assertion rejects returning a different register`() {
        assertThrows<AssertionError> {
            assertReturnsInt(method("const/4 v0, 0x1\nreturn v1"), 1, "wrong register")
        }
    }

    private fun assertCall(smali: String) = assertReturnsMethodCall(
        method(smali, "Ljava/lang/Object;"), "Lprobe/Factory;", "create", "factory",
    )

    @Test
    fun `call assertion accepts the captured invocation result`() {
        assertCall("invoke-static {}, Lprobe/Factory;->create()Ljava/lang/Object;\nmove-result-object v2\nreturn-object v2")
    }

    @Test
    fun `call assertion rejects a discarded invocation result`() {
        assertThrows<AssertionError> {
            assertCall("invoke-static {}, Lprobe/Factory;->create()Ljava/lang/Object;\nconst/4 v2, 0x0\nreturn-object v2")
        }
    }

    @Test
    fun `call assertion rejects returning a different register`() {
        assertThrows<AssertionError> {
            assertCall("invoke-static {}, Lprobe/Factory;->create()Ljava/lang/Object;\nmove-result-object v2\nreturn-object v1")
        }
    }

    @Test
    fun `call assertion rejects a method reference that is never invoked`() {
        assertThrows<AssertionError> {
            assertCall("const-method-type v0, ()Ljava/lang/Object;\nmove-result-object v2\nreturn-object v2")
        }
    }

    /** A stock snapshot holding `Lprobe/ReturnValue;->value()` with [instructions]. */
    private fun registerStock(
        instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>,
        returnType: String = "I",
    ) {
        val stockMethod = ImmutableMethod(
            "Lprobe/ReturnValue;", "value", emptyList(), returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(4, instructions, null, null),
        )
        StockMethods.register(
            listOf(
                ImmutableClassDef(
                    "Lprobe/ReturnValue;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
                    null, null, null, null, listOf(stockMethod),
                ),
            ),
        )
    }

    @Test
    fun `negative control fails when the stock method already looked forced`() {
        // The stock method is `const/4 v2, 0x1; return v2` (plus the fixture's trailing return-void)
        // and so is the "patched" one.
        registerStock(
            listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 2, 1),
                ImmutableInstruction11x(Opcode.RETURN, 2),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
        try {
            assertThrows<AssertionError> {
                assertReturnsInt(method("const/4 v2, 0x1\nreturn v2"), 1, "vacuous")
            }
            // An explicit opt-out still lets the shape check run.
            assertReturnsInt(method("const/4 v2, 0x1\nreturn v2"), 1, "opted out", requireChanged = false)
        } finally {
            StockMethods.clear()
        }
    }

    // --- assertMaxAdViewHidden -------------------------------------------------------------

    private val viewType = "Lcom/applovin/mediation/ads/MaxAdView;"
    private fun visibilityRef(owner: String) = ImmutableMethodReference(owner, "setVisibility", listOf("I"), "V")
    private fun invoke(opcode: Opcode, owner: String, a: Int, b: Int) =
        ImmutableInstruction35c(opcode, 2, a, b, 0, 0, 0, visibilityRef(owner))

    /** setVisibility(I): v0 = this, v1 = p1. */
    private fun setVisibility(vararg insns: Instruction) = ImmutableMethod(
        viewType, "setVisibility", listOf(ImmutableMethodParameter("I", null, null)), "V",
        AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(2, insns.toList(), null, null),
    )

    /** <init>(Context): v1 = this, v2 = p1, v0 is a local. */
    private fun constructor(vararg insns: Instruction) = ImmutableMethod(
        viewType, "<init>", listOf(ImmutableMethodParameter("Landroid/content/Context;", null, null)), "V",
        AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
        ImmutableMethodImplementation(3, insns.toList(), null, null),
    )

    private fun view(setVisibility: ImmutableMethod, constructor: ImmutableMethod) = ImmutableClassDef(
        viewType, AccessFlags.PUBLIC.value, "Landroid/view/ViewGroup;", null, null, null, null,
        listOf(setVisibility, constructor),
    )

    private val goodSetVisibility get() = setVisibility(
        ImmutableInstruction21s(Opcode.CONST_16, 1, 8),
        invoke(Opcode.INVOKE_SUPER, "Landroid/view/ViewGroup;", 0, 1),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )
    private val goodConstructor get() = constructor(
        ImmutableInstruction21s(Opcode.CONST_16, 2, 8),
        invoke(Opcode.INVOKE_VIRTUAL, viewType, 1, 2),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

    @Test
    fun `banner assertion accepts the real shape`() {
        assertMaxAdViewHidden(view(goodSetVisibility, goodConstructor))
    }

    @Test
    fun `banner assertion rejects a setVisibility that produces 8 by arithmetic`() {
        assertThrows<AssertionError> {
            assertMaxAdViewHidden(
                view(
                    setVisibility(
                        ImmutableInstruction22b(Opcode.ADD_INT_LIT8, 1, 0, 8),
                        invoke(Opcode.INVOKE_SUPER, "Landroid/view/ViewGroup;", 0, 1),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    goodConstructor,
                ),
            )
        }
    }

    @Test
    fun `banner assertion rejects a constructor that passes a different register than the one holding 8`() {
        assertThrows<AssertionError> {
            assertMaxAdViewHidden(
                view(
                    goodSetVisibility,
                    constructor(
                        ImmutableInstruction21s(Opcode.CONST_16, 0, 8),
                        invoke(Opcode.INVOKE_VIRTUAL, viewType, 1, 2),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                ),
            )
        }
    }

    @Test
    fun `banner assertion rejects a setVisibility that overwrites the 8 before the call`() {
        assertThrows<AssertionError> {
            assertMaxAdViewHidden(
                view(
                    setVisibility(
                        ImmutableInstruction21s(Opcode.CONST_16, 1, 8),
                        ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                        invoke(Opcode.INVOKE_SUPER, "Landroid/view/ViewGroup;", 0, 1),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    goodConstructor,
                ),
            )
        }
    }

    @Test
    fun `negative control accepts a method that differs from stock`() {
        registerStock(listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)))
        try {
            assertReturnsInt(method("const/4 v2, 0x1\nreturn v2"), 1, "changed")
        } finally {
            StockMethods.clear()
        }
    }

    @Test
    fun `negative control fails for void when the stock method already returned at once`() {
        // fixture: the inserted `return-void` plus the fixture's trailing one
        registerStock(
            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID), ImmutableInstruction10x(Opcode.RETURN_VOID)),
            returnType = "V",
        )
        try {
            assertThrows<AssertionError> {
                assertReturnsEarlyVoid(method("return-void", "V"), "vacuous void")
            }
        } finally {
            StockMethods.clear()
        }
    }
}

