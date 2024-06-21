package dev.thihup.holy.agent;

import java.io.InputStream;
import java.lang.classfile.*;
import java.lang.classfile.components.ClassRemapper;
import java.lang.constant.ClassDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;

import static java.util.Objects.requireNonNull;

class HolyPatcher implements ClassFileTransformer {

    private static final Map<ClassDesc, ClassDesc> RENAMES = Map.of(
            ClassDesc.ofInternalName("jdk/nashorn/api/scripting/NashornScriptEngineFactory"),
            ClassDesc.ofInternalName("org/openjdk/nashorn/api/scripting/NashornScriptEngineFactory"),
            ClassDesc.ofInternalName("jdk/nashorn/api/scripting/ScriptObjectMirror"),
            ClassDesc.ofInternalName("org/openjdk/nashorn/api/scripting/ScriptObjectMirror"),
            ClassDesc.ofInternalName("jdk/nashorn/api/scripting/ClassFilter"),
            ClassDesc.ofInternalName("org/openjdk/nashorn/api/scripting/ClassFilter"),
            ClassDesc.ofInternalName("jdk/nashorn/internal/objects/Global"),
            ClassDesc.ofInternalName("org/openjdk/nashorn/internal/objects/Global")
    );

    private static final ClassRemapper CLASS_REMAPPER = ClassRemapper.of(RENAMES);

    static ClassTransform matchingFieldName(String fieldName, FieldTransform fieldTransform) {
        return (builder, element) -> {
            if (element instanceof FieldModel fieldModel && fieldModel.fieldName().equalsString(fieldName)) {
                builder.transformField(fieldModel, fieldTransform);
                return;
            }
            builder.with(element);
        };
    }

    static ClassTransform matchingMethodName(String methodName, MethodTransform methodTransform) {
        return (builder, element) -> {
            if (element instanceof MethodModel methodModel && methodModel.methodName().equalsString(methodName)) {
                builder.transformMethod(methodModel, methodTransform);
                return;
            }
            builder.with(element);
        };
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        return switch (className) {
            case "com/alee/utils/system/JavaVersion" -> loadNewJavaVersionClass(className);
            case "com/alee/utils/ProprietaryUtils" ->
                    patchClass(classfileBuffer, matchingMethodName("setupUIDefaults", Patches.callSwingUtilities2()));
            case "sun/management/RuntimeImpl" ->
                    patchClass(classfileBuffer, matchingMethodName("getInputArguments", Patches.hideAgentFromCommandLine()));
            case "jdk/internal/reflect/Reflection" ->
                    patchClass(classfileBuffer, matchingMethodName("filterFields", Patches.returnArgumentWithoutFiltering()));
            case "sun/font/FontDesignMetrics" ->
                    patchClass(classfileBuffer, matchingFieldName("metricsCache", Patches.finalFlagRemover()));
            case "javax/swing/text/html/HTMLEditorKit" ->
                    patchClass(classfileBuffer, matchingFieldName("defaultFactory", Patches.finalFlagRemover()));
            case "uk/co/caprica/vlcj/player/NativeString" ->
                    patchClass(classfileBuffer, matchingMethodName("getNativeString", Patches.removeDeprecatedCallJNA()));
            case "uk/co/caprica/vlcj/player/direct/DefaultDirectMediaPlayer" ->
                    patchClass(classfileBuffer, matchingMethodName("format", Patches.removeDeprecatedCallJNA()));

            case "com/limagiran/js/JavaScriptSecure",
                 "com/limagiran/js/MyClassFilter",
                 "com/limagiran/js/JSUtils",
                 "com/limagiran/holyrics/js/JSLibHolyrics" -> patchClass(classfileBuffer, CLASS_REMAPPER);
            default -> null;
        };
    }

    private static byte[] loadNewJavaVersionClass(String className) {
        try (InputStream inputStream = Premain.class.getResourceAsStream(
                "/dev/thihup/holy/agent/JavaVersion.class")) {
            return requireNonNull(inputStream).readAllBytes();
        } catch (Exception e) {
            System.out.println("[Holyrics Patcher] Failed to patch " + className);
            return null;
        }
    }

    public static byte[] patchClass(byte[] classfileBuffer,
                                     ClassTransform classTransform) {
        ClassFile classfile = ClassFile.of();
        ClassModel classModel = classfile.parse(classfileBuffer);
        return classfile.transform(classModel, classTransform);
    }

}
