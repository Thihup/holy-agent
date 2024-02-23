package dev.thihup.holy.agent;

import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassFile;
import java.lang.classfile.components.ClassRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.lang.constant.ClassDesc;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class Premain {

    private static final System.Logger LOGGER = System.getLogger(Premain.class.getName());

    enum SetupUIFix {
        AA_TEXT_INFO,
        RENDERING_HINTS
    }

    enum BytecodeModificationType {
        REMAP,
        PATCH
    }

    static SetupUIFix UI_FIX_TYPE = SetupUIFix.AA_TEXT_INFO;

    private static void openPackagesForModule(String moduleName,
                                              Map<String, Set<Module>> packageToModule,
                                              Instrumentation instrumentation) {
        ModuleLayer.boot().findModule(moduleName).ifPresentOrElse(module -> {
            instrumentation.redefineModule(module, Set.of(), Map.of(), packageToModule, Set.of(),
                    Map.of());
        }, () -> LOGGER.log(Level.WARNING,
                "[Holyrics Patcher] Module " + moduleName + " not found"));
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        if (agentArgs != null) {
            if (Integer.parseInt(agentArgs) == 1) {
                UI_FIX_TYPE = SetupUIFix.AA_TEXT_INFO;
            } else if (Integer.parseInt(agentArgs) == 2) {
                UI_FIX_TYPE = SetupUIFix.RENDERING_HINTS;
            }

        }
        LOGGER.log(Level.INFO, "[Holyrics Patcher] Agent loaded (Using " + UI_FIX_TYPE + ")");
        addOpens(inst);

        redefineReflection(inst);
        inst.addTransformer(new HolyPatcher());
    }

    private static void redefineReflection(Instrumentation inst) {
        try {

            byte[] classfileBuffer = requireNonNull(ClassLoader.getSystemResourceAsStream(
                    "jdk/internal/reflect/Reflection.class")).readAllBytes();
            //noinspection Java9ReflectionClassVisibility
            ClassDefinition classDefinition = new ClassDefinition(
                    Class.forName("jdk.internal.reflect.Reflection"),
                    HolyPatcher.patchClass("jdk/internal/reflect/Reflection",
                            classfileBuffer,
                            BytecodeModificationType.PATCH));
            inst.redefineClasses(classDefinition);
        } catch (UnmodifiableClassException | ClassNotFoundException | IOException e) {
            LOGGER.log(Level.WARNING,
                    "[Holyrics Patcher] Failed to patch jdk/internal/reflect/Reflection");
        }
    }

    private static void addOpens(Instrumentation instrumentation) {
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

        Module unnamedModule = systemClassLoader.getUnnamedModule();

        openPackagesForModule("java.base", Map.of(
                "java.util", Set.of(unnamedModule),
                "java.lang", Set.of(unnamedModule),
                "java.lang.reflect", Set.of(unnamedModule),
                "java.text", Set.of(unnamedModule)), instrumentation);

        openPackagesForModule("java.desktop", Map.of(
                "java.awt", Set.of(unnamedModule),
                "java.awt.font", Set.of(unnamedModule),
                "java.awt.event", Set.of(unnamedModule),
                "javax.swing", Set.of(unnamedModule),
                "javax.swing.table", Set.of(unnamedModule),
                "javax.swing.text", Set.of(unnamedModule),
                "javax.swing.text.html", Set.of(unnamedModule),
                "javax.swing.plaf.basic", Set.of(unnamedModule),
                "sun.swing", Set.of(unnamedModule),
                "sun.font", Set.of(unnamedModule)), instrumentation);

        try {
            openPackagesForModule("java.desktop", Map.of("sun.awt.X11", Set.of(unnamedModule)),
                    instrumentation);
        } catch (Exception ignored) {
        }

        try {
            openPackagesForModule("java.desktop", Map.of("sun.awt.shell", Set.of(unnamedModule)),
                    instrumentation);
        } catch (Exception ignored) {
        }

        openPackagesForModule("javafx.web", Map.of(
                "com.sun.webkit.dom", Set.of(unnamedModule)
        ), instrumentation);
    }

    private static class HolyPatcher implements ClassFileTransformer {

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

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {

            return switch (className) {
                case "com/alee/utils/system/JavaVersion" -> {
                    try (InputStream inputStream = Premain.class.getResourceAsStream(
                            "/dev/thihup/holy/agent/JavaVersion.class")) {
                        yield requireNonNull(inputStream).readAllBytes();
                    } catch (Exception e) {
                        System.out.println("[Holyrics Patcher] Failed to patch " + className);
                        yield null;
                    }
                }
                case "com/alee/utils/ProprietaryUtils",
                        "sun/management/RuntimeImpl",
                        "jdk/internal/reflect/Reflection",
                        "sun/font/FontDesignMetrics",
                        "javax/swing/text/html/HTMLEditorKit" ->
                        patchClass(className, classfileBuffer, BytecodeModificationType.PATCH);
                case "com/limagiran/js/JavaScriptSecure",
                        "com/limagiran/js/MyClassFilter",
                        "com/limagiran/js/JSUtils",
                        "com/limagiran/holyrics/js/JSLibHolyrics" ->
                        patchClass(className, classfileBuffer, BytecodeModificationType.REMAP);
                default -> null;
            };
        }

        private static byte[] patchClass(String className, byte[] classfileBuffer,
                                         BytecodeModificationType changeType) {
            LOGGER.log(Level.DEBUG, "[Holyrics Patcher] Patching " + className);

            ClassFile classfile = ClassFile.of();
            ClassModel classModel = classfile.parse(classfileBuffer);

            return switch (changeType) {
                case REMAP -> ClassRemapper.of(RENAMES).remapClass(classfile, classModel);
                case PATCH -> Patches.transform(classfile, classModel);
            };

        }
    }

}
