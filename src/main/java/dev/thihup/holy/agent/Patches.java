package dev.thihup.holy.agent;

import java.lang.classfile.*;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.*;
import java.lang.invoke.*;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Predicate;

class Patches {
    private static final ClassDesc PREDICATE_DESC = Predicate.class.describeConstable().orElseThrow();

    private static final DirectMethodHandleDesc METAFACTORY = ConstantDescs.ofCallsiteBootstrap(
            LambdaMetafactory.class.describeConstable().orElseThrow(), "metafactory",
            ConstantDescs.CD_CallSite,
            ConstantDescs.CD_MethodType, ConstantDescs.CD_MethodHandle, ConstantDescs.CD_MethodType);
    
    private static final DirectMethodHandleDesc STRING_STARTS_WITH = MethodHandleDesc.ofMethod(
        DirectMethodHandleDesc.Kind.VIRTUAL,
        ConstantDescs.CD_String, "startsWith", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String));
    
    private static final DynamicCallSiteDesc PREDICATE_STRING_STARTS_WITH = 
        DynamicCallSiteDesc.of(
                METAFACTORY, 
                "test",
                MethodTypeDesc.of(PREDICATE_DESC, ConstantDescs.CD_String))
            .withArgs(MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
                STRING_STARTS_WITH,
                STRING_STARTS_WITH.invocationType());

    static byte[] transform(ClassFile classFile, ClassModel classModel) {
        return classFile.transform(classModel, (classBuilder, classElement) -> {
            switch (classElement) {
                // sun/font/FontDesignMetrics::metricsCache
                case FieldModel fieldModel when fieldModel.fieldName().equalsString("metricsCache") -> classBuilder.transformField(fieldModel, Patches::finalFlagRemover);

                // javax/swing/text/html/HTMLEditorKit
                case FieldModel fieldModel when fieldModel.fieldName().equalsString("defaultFactory") -> classBuilder.transformField(fieldModel, Patches::finalFlagRemover);

                // jdk/internal/reflect/Reflection
                case MethodModel methodModel when methodModel.methodName().equalsString("filterFields") -> classBuilder.transformMethod(methodModel, Patches::returnArgumentWithoutFiltering);

                // com/alee/utils/ProprietaryUtils
                case MethodModel methodModel when methodModel.methodName().equalsString("setupUIDefaults") -> classBuilder.transformMethod(methodModel, Patches::callSwingUtilities2);

                // sun/management/RuntimeImpl
                case MethodModel methodModel when methodModel.methodName().equalsString("getInputArguments") -> classBuilder.transformMethod(methodModel, Patches::hideAgentFromCommandLine);

                // uk/co/caprica/vlcj/player/NativeString
                case MethodModel methodModel when methodModel.methodName().equalsString("getNativeString") -> classBuilder.transformMethod(methodModel, Patches::removeDeprecatedCallJNA);
                case MethodModel methodModel when methodModel.methodName().equalsString("copyNativeString") -> classBuilder.transformMethod(methodModel, Patches::removeDeprecatedCallJNA);
                case MethodModel methodModel when methodModel.methodName().equalsString("format") -> classBuilder.transformMethod(methodModel, Patches::removeDeprecatedCallJNA);

                default -> classBuilder.with(classElement);
            }
        });
    }

    // https://github.com/caprica/vlcj/commit/66ef1f303b6849fc5e63087bb4a706b320ef056c
    private static void removeDeprecatedCallJNA(MethodBuilder methodBuilder, MethodElement methodElement) {
        if (!(methodElement instanceof CodeModel codeModel)) {
            methodBuilder.with(methodElement);
            return;
        }
        methodBuilder.transformCode(codeModel, (codeBuilder, codeElement) -> {
            switch (codeElement) {
                case InvokeInstruction instruction when instruction.name().equalsString("getString") -> {
                    codeBuilder.pop();
                    codeBuilder.invokevirtual(ClassDesc.of("com.sun.jna.Pointer"), "getString", MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_long));
                }
                default -> codeBuilder.with(codeElement);
            }
        });
    }

    private static void returnArgumentWithoutFiltering(MethodBuilder methodBuilder, MethodElement methodElement) {
        // Field[] filterFields(Class<?> containingClass, Field[] fields) {
        //   return fields;
        //
        if (!(methodElement instanceof CodeModel)) {
            methodBuilder.with(methodElement);
            return;
        }

        methodBuilder.withCode((codeBuilder) -> codeBuilder.aload(1).areturn());
    }

    private static void hideAgentFromCommandLine(MethodBuilder methodBuilder, MethodElement methodElement) {
        // Util.checkMonitorAccess();
        // var _1 = new ArrayList(jvm.getVmArguments());
        // _1.removeIf("-javaagent"::startWith);
        // _1.removeIf("-agentlib"::startWith);
        // _1.removeIf("-agentpath"::startWith);
        // return Collections.unmodifiableList(_1);
        if (!(methodElement instanceof CodeModel)) {
            methodBuilder.with(methodElement);
            return;
        }

        ClassDesc arrayListClass = ArrayList.class.describeConstable().orElseThrow();
        ClassDesc vmManagementDesc = ClassDesc.ofInternalName("sun/management/VMManagement");
        methodBuilder.withCode(codeBuilder ->
                codeBuilder.invokestatic(ClassDesc.ofInternalName("sun/management/Util"), "checkMonitorAccess", ConstantDescs.MTD_void)
                        .new_(arrayListClass)
                        .dup()
                        .aload(0)
                        .getfield(ClassDesc.ofInternalName("sun/management/RuntimeImpl"),
                                "jvm", vmManagementDesc)
                        .invokeinterface(vmManagementDesc,
                                "getVmArguments", MethodTypeDesc.of(ConstantDescs.CD_List))
                        .invokespecial(arrayListClass,
                                ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Collection))
                        .astore(1)
                        .aload(1)
                        .loadConstant("-javaagent")
                        .block(Patches::removeIf)
                        .loadConstant("-agentlib")
                        .block(Patches::removeIf)
                        .loadConstant("-agentpath")
                        .block(Patches::removeIf)
                        .pop()
                        .aload(1)
                        .invokestatic(ConstantDescs.CD_Collection, "unmodifiableList", MethodTypeDesc.of(ConstantDescs.CD_List, ConstantDescs.CD_List))
                        .areturn()
        );
    }

    private static void removeIf(CodeBuilder.BlockCodeBuilder blockCodeBuilder) {
        blockCodeBuilder
                .invokedynamic(PREDICATE_STRING_STARTS_WITH)
                .invokeinterface(ConstantDescs.CD_List,
                        "removeIf",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, PREDICATE_DESC));
    }

    private static void callSwingUtilities2(MethodBuilder methodBuilder, MethodElement methodElement) {
        if (!(methodElement instanceof CodeModel cm)) {
            methodBuilder.with(methodElement);
            return;
        }

        methodBuilder.transformCode(cm, (codeBuilder, e) ->
                    codeBuilder.iconst_1()
                            .aload(0)
                            .invokestatic(ClassDesc.ofInternalName("sun/swing/SwingUtilities2"), "putAATextInfo", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean, ConstantDescs.CD_Map))
                            .return_());
                }

    private static FieldBuilder finalFlagRemover(FieldBuilder fieldBuilder, FieldElement fieldElement) {
        return fieldBuilder.with(switch (fieldElement) {
            case AccessFlags flags -> AccessFlags.ofField(flags.flagsMask() ^ Modifier.FINAL);
            default -> fieldElement;
        });
    }

}
