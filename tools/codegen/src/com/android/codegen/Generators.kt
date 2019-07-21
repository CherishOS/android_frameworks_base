package com.android.codegen

import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.*
import java.io.File


/**
 * IntDefs and StringDefs based on constants
 */
fun ClassPrinter.generateConstDefs() {
    val consts = classAst.fields.filter {
        it.isStatic && it.isFinal && it.variables.all { variable ->
            val initializer = variable.initializer.orElse(null)
            val isLiteral = initializer is LiteralExpr
                    || (initializer is UnaryExpr && initializer.expression is LiteralExpr)
            isLiteral && variable.type.asString() in listOf("int", "String")
        }
    }.flatMap { field -> field.variables.map { it to field } }
    val intConsts = consts.filter { it.first.type.asString() == "int" }
    val strConsts = consts.filter { it.first.type.asString() == "String" }
    val intGroups = intConsts.groupBy { it.first.nameAsString.split("_")[0] }.values
    val strGroups = strConsts.groupBy { it.first.nameAsString.split("_")[0] }.values
    intGroups.forEach {
        generateConstDef(it)
    }
    strGroups.forEach {
        generateConstDef(it)
    }
}

fun ClassPrinter.generateConstDef(consts: List<Pair<VariableDeclarator, FieldDeclaration>>) {
    if (consts.size <= 1) return

    val names = consts.map { it.first.nameAsString!! }
    val prefix = names
            .reduce { a, b -> a.commonPrefixWith(b) }
            .dropLastWhile { it != '_' }
            .dropLast(1)
    if (prefix.isEmpty()) {
        println("Failed to generate const def for $names")
        return
    }
    var AnnotationName = prefix.split("_")
            .filterNot { it.isBlank() }
            .map { it.toLowerCase().capitalize() }
            .joinToString("")
    val annotatedConst = consts.find { it.second.annotations.isNonEmpty }
    if (annotatedConst != null) {
        AnnotationName = annotatedConst.second.annotations.first().nameAsString
    }
    val type = consts[0].first.type.asString()
    val flag = type == "int" && consts.all { it.first.initializer.get().toString().startsWith("0x") }
    val constDef = ConstDef(type = when {
        type == "String" -> ConstDef.Type.STRING
        flag -> ConstDef.Type.INT_FLAGS
        else -> ConstDef.Type.INT
    },
            AnnotationName = AnnotationName,
            values = consts.map { it.second }
    )
    constDefs += constDef
    fields.forEachApply {
        if (fieldAst.annotations.any { it.nameAsString == AnnotationName }) {
            this.intOrStringDef = constDef
        }
    }

    val visibility = if (consts[0].second.isPublic) "public" else "/* package-*/"

    val Retention = classRef("java.lang.annotation.Retention")
    val RetentionPolicySource = memberRef("java.lang.annotation.RetentionPolicy.SOURCE")
    val ConstDef = classRef("android.annotation.${type.capitalize()}Def")

    "@$ConstDef(${if_(flag, "flag = true, ")}prefix = \"${prefix}_\", value = {" {
        names.forEachLastAware { name, isLast ->
            +"$name${if_(!isLast, ",")}"
        }
    } + ")"
    +"@$Retention($RetentionPolicySource)"
    +GENERATED_MEMBER_HEADER
    +"$visibility @interface $AnnotationName {}"
    +""

    if (type == "int") {
        +GENERATED_MEMBER_HEADER
        val methodDefLine = "$visibility static String ${AnnotationName.decapitalize()}ToString(" +
                "@$AnnotationName int value)"
        if (flag) {
            val flg2str = memberRef("com.android.internal.util.BitUtils.flagsToString")
            methodDefLine {
                "return $flg2str(" {
                    +"value, $ClassName::single${AnnotationName}ToString"
                } + ";"
            }
            +GENERATED_MEMBER_HEADER
            !"static String single${AnnotationName}ToString(@$AnnotationName int value)"
        } else {
            !methodDefLine
        }
        " {" {
            "switch (value) {" {
                names.forEach { name ->
                    "case $name:" {
                        +"return \"$name\";"
                    }
                }
                +"default: return Integer.toHexString(value);"
            }
        }
    }
}

fun ClassPrinter.generateAidl(javaFile: File) {
    val aidl = File(javaFile.path.substringBeforeLast(".java") + ".aidl")
    if (aidl.exists()) return
    aidl.writeText(buildString {
        sourceLines.dropLastWhile { !it.startsWith("package ") }.forEach {
            appendln(it)
        }
        append("\nparcelable $ClassName;\n")
    })
}

/**
 * ```
 * Foo newFoo = oldFoo.withBar(newBar);
 * ```
 */
fun ClassPrinter.generateWithers() {
    fields.forEachApply {
        val metodName = "with$NameUpperCamel"
        if (!hasMethod(metodName, Type)) {
            generateFieldJavadoc(forceHide = FeatureFlag.WITHERS.hidden)
            """@$NonNull
                        $GENERATED_MEMBER_HEADER
                        public $ClassType $metodName($annotatedTypeForSetterParam value)""" {
                val changedFieldName = name

                "return new $ClassType(" {
                    fields.forEachTrimmingTrailingComma {
                        if (name == changedFieldName) +"value," else +"$name,"
                    }
                } + ";"
            }
        }
    }
}

fun ClassPrinter.generateCopyConstructor() {
    if (classAst.constructors.any {
                it.parameters.size == 1 &&
                        it.parameters[0].type.asString() == ClassType
            }) {
        return
    }

    +"/** Copy constructor */"
    +GENERATED_MEMBER_HEADER
    "public $ClassName(@$NonNull $ClassName orig)" {
        fields.forEachApply {
            +"$name = orig.$name;"
        }
    }
}

/**
 * ```
 * Foo newFoo = oldFoo.buildUpon().setBar(newBar).build();
 * ```
 */
fun ClassPrinter.generateBuildUpon() {
    if (hasMethod("buildUpon")) return

    +"/**"
    +" * Provides an instance of {@link $BuilderClass} with state corresponding to this instance."
    if (FeatureFlag.BUILD_UPON.hidden) {
        +" * @hide"
    }
    +" */"
    +GENERATED_MEMBER_HEADER
    "public $BuilderType buildUpon()" {
        "return new $BuilderType()" {
            fields.forEachApply {
                +".set$NameUpperCamel($internalGetter)"
            } + ";"
        }
    }
}

fun ClassPrinter.generateBuilder() {
    val setterVisibility = if (cliArgs.contains(FLAG_BUILDER_PROTECTED_SETTERS))
        "protected" else "public"
    val constructorVisibility = if (BuilderClass == CANONICAL_BUILDER_CLASS)
        "public" else "/* package-*/"

    val OneTimeUseBuilder = classRef("android.provider.OneTimeUseBuilder")

    +"/**"
    +" * A builder for {@link $ClassName}"
    if (FeatureFlag.BUILDER.hidden) +" * @hide"
    +" */"
    +"@SuppressWarnings(\"WeakerAccess\")"
    +GENERATED_MEMBER_HEADER
    "public static class $BuilderClass$genericArgs" {
        +"extends $OneTimeUseBuilder<$ClassType>"
    }
    " {" {

        +""
        fields.forEachApply {
            +"protected $annotationsAndType $name;"
        }
        +""
        +"protected long mBuilderFieldsSet = 0L;"
        +""
        +"$constructorVisibility $BuilderClass() {};"
        +""

        generateBuilderSetters(setterVisibility)

        generateBuilderBuild()

        rmEmptyLine()
    }
}

private fun ClassPrinter.generateBuilderSetters(visibility: String) {

    fields.forEachApply {
        val maybeCast =
                if_(BuilderClass != CANONICAL_BUILDER_CLASS, " ($CANONICAL_BUILDER_CLASS)")

        generateFieldJavadoc()
        +GENERATED_MEMBER_HEADER
        "$visibility $CANONICAL_BUILDER_CLASS set$NameUpperCamel($annotatedTypeForSetterParam value)" {
            +"checkNotUsed();"
            +"mBuilderFieldsSet |= $fieldBit;"
            +"$name = value;"
            +"return$maybeCast this;"
        }


        val javadocSeeSetter = "/** @see #set$NameUpperCamel */"
        val singularNameCustomizationHint = if (SingularNameOrNull == null) {
            "// You can refine this method's name by providing item's singular name, e.g.:\n" +
                    "// @DataClass.PluralOf(\"item\")) mItems = ...\n\n"
        } else ""

        if (isList && FieldInnerType != null) {

            +javadocSeeSetter
            +GENERATED_MEMBER_HEADER
            "$visibility $CANONICAL_BUILDER_CLASS add$SingularName(@$NonNull $FieldInnerType value)" {
                !singularNameCustomizationHint
                +"if ($name == null) set$NameUpperCamel(new $ArrayList<>());"
                +"$name.add(value);"
                +"return$maybeCast this;"
            }
        }

        if (Type.contains("Map<")) {
            val (Key, Value) = fieldTypeGenegicArgs

            +javadocSeeSetter
            +GENERATED_MEMBER_HEADER
            "$visibility $CANONICAL_BUILDER_CLASS add$SingularName($Key key, $Value value)" {
                !singularNameCustomizationHint
                +"if ($name == null) set$NameUpperCamel(new $LinkedHashMap());"
                +"$name.put(key, value);"
                +"return$maybeCast this;"
            }
        }

        if (Type == "boolean") {
            +javadocSeeSetter
            +GENERATED_MEMBER_HEADER
            "$visibility $CANONICAL_BUILDER_CLASS mark$NameUpperCamel()" {
                +"return set$NameUpperCamel(true);"
            }

            +javadocSeeSetter
            +GENERATED_MEMBER_HEADER
            "$visibility $CANONICAL_BUILDER_CLASS markNot$NameUpperCamel()" {
                +"return set$NameUpperCamel(false);"
            }
        }
    }
}

private fun ClassPrinter.generateBuilderBuild() {
    +"/** Builds the instance. This builder should not be touched after calling this! */"
    "public $ClassType build()" {
        +"markUsed();"
        fields.forEachApply {
            if (!isNullable || hasDefault) {
                "if ((mBuilderFieldsSet & $fieldBit) == 0)" {
                    if (!isNullable && !hasDefault) {
                        +"throw new IllegalStateException(\"Required field not set: $nameLowerCamel\");"
                    } else {
                        +"$name = $defaultExpr;"
                    }
                }
            }
        }
        "$ClassType o = new $ClassType(" {
            fields.forEachTrimmingTrailingComma {
                +"$name,"
            }
        } + ";"
        +"return o;"
    }
}

fun ClassPrinter.generateParcelable() {
    val booleanFields = fields.filter { it.Type == "boolean" }
    val objectFields = fields.filter { it.Type !in PRIMITIVE_TYPES }
    val nullableFields = objectFields.filter { it.mayBeNull }
    val nonBooleanFields = fields - booleanFields


    val flagStorageType = when (fields.size) {
        in 0..7 -> "byte"
        in 8..15 -> "int"
        in 16..31 -> "long"
        else -> throw NotImplementedError("32+ field classes not yet supported")
    }
    val FlagStorageType = flagStorageType.capitalize()

    fields.forEachApply {
        if (sParcelling != null) {
            +GENERATED_MEMBER_HEADER
            "static $Parcelling<$Type> $sParcelling =" {
                "$Parcelling.Cache.get(" {
                    +"$customParcellingClass.class"
                } + ";"
            }
            "static {" {
                "if ($sParcelling == null)" {
                    "$sParcelling = $Parcelling.Cache.put(" {
                        +"new $customParcellingClass()"
                    } + ";"
                }
            }
            +""
        }
    }

    val Parcel = classRef("android.os.Parcel")
    if (!hasMethod("writeToParcel", Parcel, "int")) {
        +"@Override"
        +GENERATED_MEMBER_HEADER
        "public void writeToParcel($Parcel dest, int flags)" {
            +"// You can override field parcelling by defining methods like:"
            +"// void parcelFieldName(Parcel dest, int flags) { ... }"
            +""

            if (booleanFields.isNotEmpty() || nullableFields.isNotEmpty()) {
                +"$flagStorageType flg = 0;"
                booleanFields.forEachApply {
                    +"if ($internalGetter) flg |= $fieldBit;"
                }
                nullableFields.forEachApply {
                    +"if ($internalGetter != null) flg |= $fieldBit;"
                }
                +"dest.write$FlagStorageType(flg);"
            }

            nonBooleanFields.forEachApply {
                val customParcellingMethod = "parcel$NameUpperCamel"
                when {
                    hasMethod(customParcellingMethod, Parcel, "int") ->
                        +"$customParcellingMethod(dest, flags);"
                    customParcellingClass != null -> +"$sParcelling.parcel($name, dest, flags);"
                    hasAnnotation("@$DataClassEnum") ->
                        +"dest.writeInt($internalGetter == null ? -1 : $internalGetter.ordinal());"
                    else -> {
                        if (mayBeNull) !"if ($internalGetter != null) "
                        var args = internalGetter
                        if (ParcelMethodsSuffix.startsWith("Parcelable")
                                || ParcelMethodsSuffix.startsWith("TypedObject")
                                || ParcelMethodsSuffix == "TypedArray") {
                            args += ", flags"
                        }
                        +"dest.write$ParcelMethodsSuffix($args);"
                    }
                }
            }
        }
    }

    if (!hasMethod("describeContents")) {
        +"@Override"
        +GENERATED_MEMBER_HEADER
        +"public int describeContents() { return 0; }"
        +""
    }

    if (classAst.fields.none { it.variables[0].nameAsString == "CREATOR" }) {
        val Creator = classRef("android.os.Parcelable.Creator")

        +GENERATED_MEMBER_HEADER
        "public static final @$NonNull $Creator<$ClassName> CREATOR" {
            +"= new $Creator<$ClassName>()"
        }; " {" {

            +"@Override"
            "public $ClassName[] newArray(int size)" {
                +"return new $ClassName[size];"
            }

            +"@Override"
            +"@SuppressWarnings({\"unchecked\", \"RedundantCast\"})"
            "public $ClassName createFromParcel($Parcel in)" {
                +"// You can override field unparcelling by defining methods like:"
                +"// static FieldType unparcelFieldName(Parcel in) { ... }"
                +""
                if (booleanFields.isNotEmpty() || nullableFields.isNotEmpty()) {
                    +"$flagStorageType flg = in.read$FlagStorageType();"
                }
                booleanFields.forEachApply {
                    +"$Type $_name = (flg & $fieldBit) != 0;"
                }
                nonBooleanFields.forEachApply {

                    // Handle customized parceling
                    val customParcellingMethod = "unparcel$NameUpperCamel"
                    if (hasMethod(customParcellingMethod, Parcel)) {
                        +"$Type $_name = $customParcellingMethod(in);"
                    } else if (customParcellingClass != null) {
                        +"$Type $_name = $sParcelling.unparcel(in);"
                    } else if (hasAnnotation("@$DataClassEnum")) {
                        val ordinal = "${_name}Ordinal"
                        +"int $ordinal = in.readInt();"
                        +"$Type $_name = $ordinal < 0 ? null : $FieldClass.values()[$ordinal];"
                    } else {
                        val methodArgs = mutableListOf<String>()

                        // Create container if any
                        val containerInitExpr = when {
                            FieldClass.endsWith("Map") -> "new $LinkedHashMap<>()"
                            FieldClass == "List" || FieldClass == "ArrayList" ->
                                "new ${classRef("java.util.ArrayList")}<>()"
//                            isArray && FieldInnerType in (PRIMITIVE_TYPES + "String") ->
//                                "new $FieldInnerType[in.readInt()]"
                            else -> ""
                        }
                        val passContainer = containerInitExpr.isNotEmpty()

                        // nullcheck +
                        // "FieldType fieldName = (FieldType)"
                        if (passContainer) {
                            methodArgs.add(_name)
                            !"$Type $_name = "
                            if (mayBeNull) {
                                +"null;"
                                !"if ((flg & $fieldBit) != 0) {"
                                pushIndent()
                                +""
                                !"$_name = "
                            }
                            +"$containerInitExpr;"
                        } else {
                            !"$Type $_name = "
                            if (mayBeNull) !"(flg & $fieldBit) == 0 ? null : "
                            if (ParcelMethodsSuffix == "StrongInterface") {
                                !"$FieldClass.Stub.asInterface("
                            } else if (Type !in PRIMITIVE_TYPES + "String" + "Bundle" &&
                                    (!isArray || FieldInnerType !in PRIMITIVE_TYPES + "String") &&
                                    ParcelMethodsSuffix != "Parcelable") {
                                !"($Type) "
                            }
                        }

                        // Determine method args
                        when {
                            ParcelMethodsSuffix == "Parcelable" ->
                                methodArgs += "$FieldClass.class.getClassLoader()"
                            ParcelMethodsSuffix == "TypedObject" ->
                                methodArgs += "$FieldClass.CREATOR"
                            ParcelMethodsSuffix == "TypedArray" ->
                                methodArgs += "$FieldInnerClass.CREATOR"
                            ParcelMethodsSuffix.startsWith("Parcelable")
                                    || FieldClass == "Map"
                                    || (isList || isArray)
                                    && FieldInnerType !in PRIMITIVE_TYPES + "String" ->
                                methodArgs += "$FieldInnerClass.class.getClassLoader()"
                        }

                        // ...in.readFieldType(args...);
                        when {
                            ParcelMethodsSuffix == "StrongInterface" -> !"in.readStrongBinder"
                            isArray -> !"in.create$ParcelMethodsSuffix"
                            else -> !"in.read$ParcelMethodsSuffix"
                        }
                        !"(${methodArgs.joinToString(", ")})"
                        if (ParcelMethodsSuffix == "StrongInterface") !")"
                        +";"

                        // Cleanup if passContainer
                        if (passContainer && mayBeNull) {
                            popIndent()
                            rmEmptyLine()
                            +"\n}"
                        }
                    }
                }
                "return new $ClassType(" {
                    fields.forEachTrimmingTrailingComma {
                        +"$_name,"
                    }
                } + ";"
            }
            rmEmptyLine()
        } + ";"
        +""
    }
}

fun ClassPrinter.generateEqualsHashcode() {
    if (!hasMethod("equals", "Object")) {
        +"@Override"
        +GENERATED_MEMBER_HEADER
        "public boolean equals(Object o)" {
            +"// You can override field equality logic by defining either of the methods like:"
            +"// boolean fieldNameEquals($ClassName other) { ... }"
            +"// boolean fieldNameEquals(FieldType otherValue) { ... }"
            +""
            """if (this == o) return true;
                        if (o == null || getClass() != o.getClass()) return false;
                        @SuppressWarnings("unchecked")
                        $ClassType that = ($ClassType) o;
                        //noinspection PointlessBooleanExpression
                        return true""" {
                fields.forEachApply {
                    val sfx = if (isLast) ";" else ""
                    val customEquals = "${nameLowerCamel}Equals"
                    when {
                        hasMethod(customEquals, Type) -> +"&& $customEquals(that.$internalGetter)$sfx"
                        hasMethod(customEquals, ClassType) -> +"&& $customEquals(that)$sfx"
                        else -> +"&& ${isEqualToExpr("that.$internalGetter")}$sfx"
                    }
                }
            }
        }
    }

    if (!hasMethod("hashCode")) {
        +"@Override"
        +GENERATED_MEMBER_HEADER
        "public int hashCode()" {
            +"// You can override field hashCode logic by defining methods like:"
            +"// int fieldNameHashCode() { ... }"
            +""
            +"int _hash = 1;"
            fields.forEachApply {
                !"_hash = 31 * _hash + "
                val customHashCode = "${nameLowerCamel}HashCode"
                when {
                    hasMethod(customHashCode) -> +"$customHashCode();"
                    Type == "int" || Type == "byte" -> +"$internalGetter;"
                    Type in PRIMITIVE_TYPES -> +"${Type.capitalize()}.hashCode($internalGetter);"
                    isArray -> +"${memberRef("java.util.Arrays.hashCode")}($internalGetter);"
                    else -> +"${memberRef("java.util.Objects.hashCode")}($internalGetter);"
                }
            }
            +"return _hash;"
        }
    }
}

//TODO support IntDef flags?
fun ClassPrinter.generateToString() {
    if (!hasMethod("toString")) {
        +"@Override"
        +GENERATED_MEMBER_HEADER
        "public String toString()" {
            +"// You can override field toString logic by defining methods like:"
            +"// String fieldNameToString() { ... }"
            +""
            "return \"$ClassName { \" +" {
                fields.forEachApply {
                    val customToString = "${nameLowerCamel}ToString"
                    val expr = when {
                        hasMethod(customToString) -> "$customToString()"
                        isArray -> "${memberRef("java.util.Arrays.toString")}($internalGetter)"
                        intOrStringDef?.type?.isInt == true ->
                            "${intOrStringDef!!.AnnotationName.decapitalize()}ToString($name)"
                        else -> internalGetter
                    }
                    +"\"$nameLowerCamel = \" + $expr${if_(!isLast, " + \", \"")} +"
                }
            }
            +"\" }\";"
        }
    }
}

fun ClassPrinter.generateSetters() {
    fields.forEachApply {
        if (!hasMethod("set$NameUpperCamel", Type)
                && !fieldAst.isPublic
                && !isFinal) {

            generateFieldJavadoc(forceHide = FeatureFlag.SETTERS.hidden)
            +GENERATED_MEMBER_HEADER
            "public $ClassType set$NameUpperCamel($annotatedTypeForSetterParam value)" {
                generateSetFrom("value")
                +"return this;"
            }
        }
    }
}

fun ClassPrinter.generateGetters() {
    (fields + lazyTransientFields).forEachApply {
        val methodPrefix = if (Type == "boolean") "is" else "get"
        val methodName = methodPrefix + NameUpperCamel

        if (!hasMethod(methodName) && !fieldAst.isPublic) {

            generateFieldJavadoc(forceHide = FeatureFlag.GETTERS.hidden)
            +GENERATED_MEMBER_HEADER
            "public $annotationsAndType $methodName()" {
                if (lazyInitializer == null) {
                    +"return $name;"
                } else {
                    +"$Type $_name = $name;"
                    "if ($_name == null)" {
                        if (fieldAst.isVolatile) {
                            "synchronized(this)" {
                                +"$_name = $name;"
                                "if ($_name == null)" {
                                    +"$_name = $name = $lazyInitializer();"
                                }
                            }
                        } else {
                            +"// You can mark field as volatile for thread-safe double-check init"
                            +"$_name = $name = $lazyInitializer();"
                        }
                    }
                    +"return $_name;"
                }
            }
        }
    }
}

fun FieldInfo.generateFieldJavadoc(forceHide: Boolean = false) = classPrinter {
    if (javadocFull != null || forceHide) {
        var hidden = false
        (javadocFull ?: "/**\n */").lines().forEach {
            if (it.contains("@hide")) hidden = true
            if (it.contains("*/") && forceHide && !hidden) {
                if (javadocFull != null) +" *"
                +" * @hide"
            }
            +it
        }
    }
}

fun FieldInfo.generateSetFrom(source: String) = classPrinter {
    !"$name = "
    if (Type in PRIMITIVE_TYPES || mayBeNull) {
        +"$source;"
    } else if (defaultExpr != null) {
        "$source != null" {
            +"? $source"
            +": $defaultExpr;"
        }
    } else {
        val checkNotNull = memberRef("com.android.internal.util.Preconditions.checkNotNull")
        +"$checkNotNull($source);"
    }
    if (isNonEmpty) {
        "if ($isEmptyExpr)" {
            +"throw new IllegalArgumentException(\"$nameLowerCamel cannot be empty\");"
        }
    }
}

fun ClassPrinter.generateConstructor(visibility: String = "public") {
    if (visibility == "public") {
        generateConstructorJavadoc()
    }
    +GENERATED_MEMBER_HEADER
    "$visibility $ClassName(" {
        fields.forEachApply {
            +"$annotationsAndType $nameLowerCamel${if_(!isLast, ",")}"
        }
    }
    " {" {
        fields.forEachApply {
            !"this."
            generateSetFrom(nameLowerCamel)
        }

        generateStateValidation()

        generateOnConstructedCallback()
    }
}

private fun ClassPrinter.generateConstructorJavadoc() {
    if (fields.all { it.javadoc == null } && !FeatureFlag.CONSTRUCTOR.hidden) return
    +"/**"
    fields.filter { it.javadoc != null }.forEachApply {
        javadocTextNoAnnotationLines?.apply {
            +" * @param $nameLowerCamel"
            forEach {
                +" *   $it"
            }
        }
    }
    if (FeatureFlag.CONSTRUCTOR.hidden) +" * @hide"
    +" */"
}

private fun ClassPrinter.generateStateValidation() {
    val Size = classRef("android.annotation.Size")
    val knownNonValidationAnnotations = internalAnnotations + Nullable

    val validate = memberRef("com.android.internal.util.AnnotationValidations.validate")
    fun appendValidateCall(annotation: AnnotationExpr, valueToValidate: String) {
        "$validate(" {
            !"${annotation.nameAsString}.class, null, $valueToValidate"
            val params = when (annotation) {
                is MarkerAnnotationExpr -> emptyMap()
                is SingleMemberAnnotationExpr -> mapOf("value" to annotation.memberValue)
                is NormalAnnotationExpr ->
                    annotation.pairs.map { it.name.asString() to it.value }.toMap()
                else -> throw IllegalStateException()
            }
            params.forEach { name, value ->
                !",\n\"$name\", $value"
            }
        }
        +";"
    }

    fields.forEachApply {
        if (intOrStringDef != null) {
            if (intOrStringDef!!.type == ConstDef.Type.INT_FLAGS) {
                +""
                +"//noinspection PointlessBitwiseExpression"
                "$Preconditions.checkFlagsArgument(" {
                    "$name, 0" {
                        intOrStringDef!!.CONST_NAMES.forEach {
                            +"| $it"
                        }
                    }
                }
                +";"
            } else {
                +""
                +"//noinspection PointlessBooleanExpression"
                "if (true" {
                    intOrStringDef!!.CONST_NAMES.forEach { CONST_NAME ->
                        +"&& !(${isEqualToExpr(CONST_NAME)})"
                    }
                }; rmEmptyLine(); ") {" {
                    "throw new ${classRef<IllegalArgumentException>()}(" {
                        "\"$nameLowerCamel was \" + $internalGetter + \" but must be one of: \"" {

                            intOrStringDef!!.CONST_NAMES.forEachLastAware { CONST_NAME, isLast ->
                                +"""+ "$CONST_NAME(" + $CONST_NAME + ")${if_(!isLast, ", ")}""""
                            }
                        }
                    }
                    +";"
                }
            }
        }

        val eachLine = fieldAst.annotations.find { it.nameAsString == Each }?.range?.orElse(null)?.end?.line
        val perElementValidations = if (eachLine == null) emptyList() else fieldAst.annotations.filter {
            it.nameAsString != Each &&
                it.range.orElse(null)?.begin?.line?.let { it >= eachLine } ?: false
        }

        fieldAst.annotations.filterNot {
            it.nameAsString == intOrStringDef?.AnnotationName
                    || it.nameAsString in knownNonValidationAnnotations
                    || it in perElementValidations
        }.forEach { annotation ->
            appendValidateCall(annotation,
                    valueToValidate = if (annotation.nameAsString == Size) sizeExpr else name)
        }

        if (perElementValidations.isNotEmpty()) {
            +"int ${nameLowerCamel}Size = $sizeExpr;"
            "for (int i = 0; i < ${nameLowerCamel}Size; i++) {" {
                perElementValidations.forEach { annotation ->
                    appendValidateCall(annotation,
                            valueToValidate = elemAtIndexExpr("i"))
                }
            }
        }
    }
}

private fun ClassPrinter.generateOnConstructedCallback(prefix: String = "") {
    +""
    val call = "${prefix}onConstructed();"
    if (hasMethod("onConstructed")) {
        +call
    } else {
        +"// $call // You can define this method to get a callback"
    }
}

fun ClassPrinter.generateForEachField() {
    val specializations = listOf("Object", "int")
    val usedSpecializations = fields.map { if (it.Type in specializations) it.Type else "Object" }
    val usedSpecializationsSet = usedSpecializations.toSet()

    val PerObjectFieldAction = classRef("com.android.internal.util.DataClass.PerObjectFieldAction")

    +GENERATED_MEMBER_HEADER
    "void forEachField(" {
        usedSpecializationsSet.toList().forEachLastAware { specType, isLast ->
            val SpecType = specType.capitalize()
            val ActionClass = classRef("com.android.internal.util.DataClass.Per${SpecType}FieldAction")
            +"$ActionClass<$ClassType> action$SpecType${if_(!isLast, ",")}"
        }
    }; " {" {
        usedSpecializations.forEachIndexed { i, specType ->
            val SpecType = specType.capitalize()
            fields[i].apply {
                +"action$SpecType.accept$SpecType(this, \"$nameLowerCamel\", $name);"
            }
        }
    }

    if (usedSpecializationsSet.size > 1) {
        +"/** @deprecated May cause boxing allocations - use with caution! */"
        +"@Deprecated"
        +GENERATED_MEMBER_HEADER
        "void forEachField($PerObjectFieldAction<$ClassType> action)" {
            fields.forEachApply {
                +"action.acceptObject(this, \"$nameLowerCamel\", $name);"
            }
        }
    }
}
