package app.template.patches.flashscore.misc.font

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_FLASHSCORE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// Compose keeps these two literals in the toString() of its generic families, so the system font is
// found without touching an obfuscated name.
private const val DEFAULT_FAMILY = "FontFamily.Default"

/**
 * Flashscore defines its four text families (livesport, livesport_bold, livesport_extrabold and the
 * tabular score digits) in one holder class and passes them into every Compose TextStyle. Replacing
 * each read of that holder with Compose's `FontFamily.Default` makes the whole UI use the device's
 * system font (including Google Sans on devices that ship it) while leaving the custom font files,
 * the typography sizes and the layout untouched.
 *
 * The holder is located by shape: a class with exactly four static fields that all share one type,
 * and that type is a direct subclass of Compose's FontFamily. The replacement target is
 * `FontFamily.Default`, found through its toString() literal. Every static read of the four fields
 * is rewritten, so nothing keeps a reference to the old families.
 */
@Suppress("unused")
val useSystemFontPatch = bytecodePatch(
    name = "Use system font",
    description = "Uses the device's system font instead of Flashscore's custom font.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_FLASHSCORE)

    dependsOn(useSystemFontStylesPatch)

    execute {
        // 1. FontFamily.Default: the generic family whose toString() reports the name.
        val defaultClasses = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (classDef.methods.any { it.toStringReturns(DEFAULT_FAMILY) }) defaultClasses += classDef
        }
        val defaultFamily = defaultClasses.singleOrNull() ?: throw PatchException(
            "expected one \"$DEFAULT_FAMILY\" class, found ${defaultClasses.size}: " +
                defaultClasses.joinToString { it.type },
        )

        // 2. FontFamily itself: it declares that Default instance as a static field.
        val fontFamilies = mutableListOf<Pair<ClassDef, Field>>()
        classDefForEach { classDef ->
            val field = classDef.fields.singleOrNull {
                it.type == defaultFamily.type && AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: return@classDefForEach
            fontFamilies += classDef to field
        }
        val (fontFamily, defaultField) = fontFamilies.singleOrNull() ?: throw PatchException(
            "expected one FontFamily holding $DEFAULT_FAMILY, found ${fontFamilies.size}: " +
                fontFamilies.joinToString { (classDef, field) -> "${classDef.type}->${field.name}" },
        )

        // 3. The app's family holder: four static fields of one FontFamily subclass.
        val holders = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            val statics = classDef.fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) }
            if (statics.size != 4) return@classDefForEach
            val familyType = statics.first().type
            if (statics.any { it.type != familyType }) return@classDefForEach
            if (familyType == fontFamily.type) return@classDefForEach
            val family = classDefByOrNull(familyType) ?: return@classDefForEach
            if (family.superclass != fontFamily.type) return@classDefForEach
            holders += classDef
        }
        val holder = holders.singleOrNull() ?: throw PatchException(
            "expected one Flashscore font family holder, found ${holders.size}: " +
                holders.joinToString { it.type },
        )
        val holderFields = holder.fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) }
            .associateBy { it.name }

        // 4. Collect every static read of those fields before mutating anything.
        val reads = mutableListOf<Triple<ClassDef, Method, List<Int>>>()
        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                val indices = implementation.instructions.withIndex()
                    .filter { (_, instruction) -> instruction.readsFieldOf(holder.type, holderFields.keys) }
                    .map { it.index }
                if (indices.isNotEmpty()) reads += Triple(classDef, method, indices)
            }
        }
        if (reads.isEmpty()) throw PatchException("${holder.type}: no reads of the font families found")

        var replaced = 0
        val defaultReference = "${fontFamily.type}->${defaultField.name}:${defaultField.type}"
        for ((classDef, method, indices) in reads) {
            val mutable = mutableClassDefBy(classDef).methods.single {
                it.name == method.name && it.returnType == method.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
            }
            for (index in indices) {
                val instruction = mutable.instructions.toList()[index]
                val register = (instruction as OneRegisterInstruction).registerA
                mutable.replaceInstruction(index, "sget-object v$register, $defaultReference")
                replaced++
            }
        }
        if (replaced == 0) throw PatchException("${holder.type}: no font family read was rewritten")
    }
}

private fun Method.toStringReturns(value: String): Boolean {
    if (name != "toString" || parameterTypes.isNotEmpty() || returnType != "Ljava/lang/String;") return false
    val implementation = implementation ?: return false
    val insns = implementation.instructions.toList()
    if (insns.size != 2 || insns[0].opcode != Opcode.CONST_STRING || insns[1].opcode != Opcode.RETURN_OBJECT) {
        return false
    }
    val text = ((insns[0] as ReferenceInstruction).reference as? StringReference)?.string
    return text == value
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.readsFieldOf(
    owner: String,
    names: Set<String>,
): Boolean {
    if (opcode != Opcode.SGET_OBJECT && opcode != Opcode.SGET) return false
    val reference = (this as? ReferenceInstruction)?.reference as? FieldReference ?: return false
    return reference.definingClass == owner && reference.name in names
}
