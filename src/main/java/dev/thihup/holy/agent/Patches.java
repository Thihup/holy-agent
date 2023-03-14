package dev.thihup.holy.agent;

import jdk.internal.classfile.*;

import java.lang.constant.*;
import java.lang.invoke.LambdaMetafactory;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

class Patches {

    public static final ClassDesc LIST_CLASS = List.class.describeConstable().orElseThrow();
    public static final DirectMethodHandleDesc METAFACTORY = ConstantDescs.ofCallsiteBootstrap(
            LambdaMetafactory.class.describeConstable().orElseThrow(), "metafactory",
            ConstantDescs.CD_CallSite,
            ConstantDescs.CD_MethodHandles_Lookup, ConstantDescs.CD_String, ConstantDescs.CD_MethodType,
            ConstantDescs.CD_MethodType, ConstantDescs.CD_MethodHandle, ConstantDescs.CD_MethodType);
    public static final DynamicCallSiteDesc STRING_STARTS_WITH_PREDICATE = DynamicCallSiteDesc.of(METAFACTORY, "test", MethodTypeDesc.of(Predicate.class.describeConstable().orElseThrow(), ConstantDescs.CD_String),
            MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
            MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.VIRTUAL, ConstantDescs.CD_String, "startsWith", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String)),
            MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String));
    public static final ClassDesc MAP_CLASS = Map.class.describeConstable().orElseThrow();

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
        if (!(methodElement instanceof CodeModel cm)) {
            methodBuilder.with(methodElement);
            return;
        }

        methodBuilder.withCode(codeBuilder -> {
            codeBuilder.aload(0)
                    .getstatic(ClassDesc.ofInternalName("java/awt/RenderingHints"),
                            "KEY_ANTIALIASING", ClassDesc.ofDescriptor("Ljava/awt/RenderingHints$Key;"))
                    .getstatic(ClassDesc.ofInternalName("java/awt/RenderingHints"),
                            "VALUE_ANTIALIAS_ON", ConstantDescs.CD_Object)
                    .invokeinterface(MAP_CLASS, "put",
                            MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object, ConstantDescs.CD_Object))
                    .pop()
                    .aload(0)
                    .getstatic(ClassDesc.ofInternalName("java/awt/RenderingHints"),
                            "KEY_TEXT_ANTIALIASING", ClassDesc.ofDescriptor("Ljava/awt/RenderingHints$Key;"))
                    .getstatic(ClassDesc.ofInternalName("java/awt/RenderingHints"),
                            "VALUE_TEXT_ANTIALIAS_ON", ConstantDescs.CD_Object)
                    .invokeinterface(MAP_CLASS, "put",
                            MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object, ConstantDescs.CD_Object))
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

        methodBuilder.withCode((codeBuilder) -> codeBuilder.aload(1)
                .areturn());
    }

    private static void hideAgentFromCommandLine(MethodBuilder methodBuilder, MethodElement methodElement) {
        // Util.checkMonitorAccess();
        // var 0 = new ArrayList(jvm.getVmArguments());
        // 0.removeIf("-javaagent"::startWith);
        // 0.removeIf("-agentlib"::startWith);
        // 0.removeIf("-agentpath"::startWith);
        // return Collections.unmodifiableList(0);
        if (!(methodElement instanceof CodeModel)) {
            methodBuilder.with(methodElement);
            return;
        }

        ClassDesc arrayListClass = ArrayList.class.describeConstable().orElseThrow();
        methodBuilder.withCode(codeBuilder ->
                codeBuilder.invokestatic(ClassDesc.ofInternalName("sun/management/Util"), "checkMonitorAccess", MethodTypeDesc.of(ConstantDescs.CD_void))
                        .newObjectInstruction(arrayListClass)
                        .dup()
                        .aload(0)
                        .getfield(ClassDesc.ofInternalName("sun/management/RuntimeImpl"),
                                "jvm", ClassDesc.ofInternalName("sun/management/VMManagement"))
                        .invokeinterface(ClassDesc.ofInternalName("sun/management/VMManagement"),
                                "getVmArguments", MethodTypeDesc.of(LIST_CLASS))
                        .invokespecial(arrayListClass,
                                "<init>", MethodTypeDesc.of(void.class.describeConstable().orElseThrow(), Collection.class.describeConstable().orElseThrow()))
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
                        .invokestatic(Collections.class.describeConstable().orElseThrow(), "unmodifiableList", MethodTypeDesc.of(LIST_CLASS, LIST_CLASS))
                        .areturn()
        );
    }

    private static void removeIf(CodeBuilder.BlockCodeBuilder blockCodeBuilder) {
        blockCodeBuilder.invokeDynamicInstruction(STRING_STARTS_WITH_PREDICATE)
                .invokeinterface(LIST_CLASS,
                        "removeIf",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, Predicate.class.describeConstable().orElseThrow()));
    }

    private static void callSwingUtilities2(MethodBuilder methodBuilder, MethodElement methodElement) {
        if (!(methodElement instanceof CodeModel cm)) {
            methodBuilder.with(methodElement);
            return;
        }
        methodBuilder.transformCode(cm, (codeBuilder, e) ->
                codeBuilder.iconst_1()
                        .aload(0)
                        .invokestatic(ClassDesc.ofInternalName("sun/swing/SwingUtilities2"), "putAATextInfo", MethodTypeDesc.ofDescriptor("(ZLjava/util/Map;)V"))
                        .return_());
    }

    private static FieldBuilder finalFlagRemover(FieldBuilder fieldBuilder, FieldElement fieldElement) {
        return fieldBuilder.with(switch (fieldElement) {
            case AccessFlags flags -> AccessFlags.ofField(flags.flagsMask() ^ Modifier.FINAL);
            default -> fieldElement;
        });
    }

}
