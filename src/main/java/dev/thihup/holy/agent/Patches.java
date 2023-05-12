package dev.thihup.holy.agent;

import jdk.internal.classfile.*;

import java.lang.constant.*;
import java.lang.invoke.LambdaMetafactory;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Predicate;

class Patches {
    private static final ClassDesc PREDICATE_DESC = Predicate.class.describeConstable().orElseThrow();

    private static final DirectMethodHandleDesc METAFACTORY = ConstantDescs.ofCallsiteBootstrap(
            LambdaMetafactory.class.describeConstable().orElseThrow(), "metafactory",
            ConstantDescs.CD_CallSite,
            ConstantDescs.CD_MethodType, ConstantDescs.CD_MethodHandle, ConstantDescs.CD_MethodType);
    private static final MethodTypeDesc METHOD_TYPE_PREDICATE = MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String);
    private static final DirectMethodHandleDesc STRING_STARTS_WITH = MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.VIRTUAL,
        ConstantDescs.CD_String, "startsWith", METHOD_TYPE_PREDICATE);
    private static final DynamicCallSiteDesc PREDICATE_STRING_STARTS_WITH = DynamicCallSiteDesc.of(METAFACTORY, "test",
            MethodTypeDesc.of(PREDICATE_DESC, ConstantDescs.CD_String),
            MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
            STRING_STARTS_WITH,
            METHOD_TYPE_PREDICATE);

    static byte[] transform(ClassModel classModel) {

        String className = classModel.thisClass().asInternalName();

        return classModel.transform((classBuilder, classElement) -> {
            switch (className) {
                case "sun/font/FontDesignMetrics" -> {
                    if (classElement instanceof FieldModel fieldModel
                            && fieldModel.fieldName().equalsString("metricsCache")) {
                        classBuilder.transformField(fieldModel, Patches::finalFlagRemover);
                    } else classBuilder.with(classElement);
                }
                case "javax/swing/text/html/HTMLEditorKit" -> {
                    if (classElement instanceof FieldModel fieldModel
                            && fieldModel.fieldName().equalsString("defaultFactory")) {
                        classBuilder.transformField(fieldModel, Patches::finalFlagRemover);
                    } else classBuilder.with(classElement);

                }

                case "jdk/internal/reflect/Reflection" -> {
                    if (classElement instanceof MethodModel methodModel
                            && methodModel.methodName().equalsString("filterFields")) {
                        classBuilder.transformMethod(methodModel, Patches::returnArgumentWithoutFiltering);
                    } else {
                        classBuilder.with(classElement);
                    }
                }
                case "com/alee/utils/ProprietaryUtils" -> {
                    if (classElement instanceof MethodModel methodModel
                            && methodModel.methodName().equalsString("setupUIDefaults")) {
                        MethodTransform methodTransform = switch (Premain.UI_FIX_TYPE) {
                            case AA_TEXT_INFO -> Patches::callSwingUtilities2;
                            case RENDERING_HINTS -> Patches::addRenderingHints;
                        };
                        classBuilder.transformMethod(methodModel, methodTransform);
                    } else {
                        classBuilder.with(classElement);
                    }
                }
                case "sun/management/RuntimeImpl" -> {
                    if (classElement instanceof MethodModel methodModel
                            && methodModel.methodName().equalsString("getInputArguments")) {
                        classBuilder.transformMethod(methodModel, Patches::hideAgentFromCommandLine);
                    } else {
                        classBuilder.with(classElement);
                    }
                }
                default -> throw new IllegalStateException("Unexpected value: " + className);
            }
        });
    }

    private static void addRenderingHints(MethodBuilder methodBuilder, MethodElement methodElement) {
        // table.put(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        // table.put(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (!(methodElement instanceof CodeModel)) {
            methodBuilder.with(methodElement);
            return;
        }

        methodBuilder.withCode(codeBuilder -> {
            MethodTypeDesc methodTypeMapPut = MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object, ConstantDescs.CD_Object);
            ClassDesc renderingHintsDesc = ClassDesc.ofInternalName("java/awt/RenderingHints");
            ClassDesc renderingHintsKeyDesc = ClassDesc.ofInternalName("java/awt/RenderingHints$Key");
            codeBuilder.aload(0)
                    .getstatic(renderingHintsDesc, "KEY_ANTIALIASING", renderingHintsKeyDesc)
                    .getstatic(renderingHintsDesc, "VALUE_ANTIALIAS_ON", ConstantDescs.CD_Object)
                    .invokeInstruction(Opcode.INVOKEVIRTUAL, ConstantDescs.CD_Map, "put", methodTypeMapPut, false)
                    .pop()
                    .aload(0)
                    .getstatic(renderingHintsDesc, "KEY_TEXT_ANTIALIASING", renderingHintsKeyDesc)
                    .getstatic(renderingHintsDesc, "VALUE_TEXT_ANTIALIAS_ON", ConstantDescs.CD_Object)
                    .invokeinterface(ConstantDescs.CD_Map, "put", methodTypeMapPut)
                    .pop()
                    .return_();
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
                        .newObjectInstruction(arrayListClass)
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
                        .constantInstruction("-javaagent")
                        .block(Patches::removeIf)
                        .constantInstruction("-agentlib")
                        .block(Patches::removeIf)
                        .constantInstruction("-agentpath")
                        .block(Patches::removeIf)
                        .pop()
                        .aload(1)
                        .invokestatic(ConstantDescs.CD_Collection, "unmodifiableList", MethodTypeDesc.of(ConstantDescs.CD_List, ConstantDescs.CD_List))
                        .areturn()
        );
    }

    private static void removeIf(CodeBuilder.BlockCodeBuilder blockCodeBuilder) {
        blockCodeBuilder
                .invokeDynamicInstruction(PREDICATE_STRING_STARTS_WITH)
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
