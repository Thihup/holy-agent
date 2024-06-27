package dev.thihup.holy.agent;

import java.lang.classfile.AccessFlags;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.FieldTransform;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.*;
import java.lang.invoke.LambdaMetafactory;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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


    // https://github.com/caprica/vlcj/commit/66ef1f303b6849fc5e63087bb4a706b320ef056c
    static MethodTransform removeDeprecatedCallJNA() {
        return MethodTransform.transformingCode((codeBuilder, codeElement) -> {
            if (codeElement instanceof InvokeInstruction instruction && instruction.name().equalsString("getString")) {
                codeBuilder.pop();
                codeBuilder.invokevirtual(ClassDesc.of("com.sun.jna.Pointer"),
                        "getString", MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_long));
                return;
            }
            codeBuilder.with(codeElement);
        });
    }

    static MethodTransform returnArgumentWithoutFiltering() {
        // Field[] filterFields(Class<?> containingClass, Field[] fields) {
        //   return fields;
        //
        return MethodTransform.transformingCode((codeBuilder, _) -> codeBuilder.aload(1).areturn());
    }

    static MethodTransform hideAgentFromCommandLine() {
        // Util.checkMonitorAccess();
        // var _1 = new ArrayList(jvm.getVmArguments());
        // _1.removeIf("-javaagent"::startWith);
        // _1.removeIf("-agentlib"::startWith);
        // _1.removeIf("-agentpath"::startWith);
        // return Collections.unmodifiableList(_1);
        ClassDesc arrayListClass = ArrayList.class.describeConstable().orElseThrow();
        ClassDesc vmManagementDesc = ClassDesc.ofInternalName("sun/management/VMManagement");
        return MethodTransform.transformingCode((codeBuilder, _) ->
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
                .invokestatic(ConstantDescs.CD_Collection,
                    "unmodifiableList", MethodTypeDesc.of(ConstantDescs.CD_List, ConstantDescs.CD_List))
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

    static MethodTransform callSwingUtilities2() {
        return MethodTransform.transformingCode((codeBuilder, _) ->
                codeBuilder.iconst_1()
                        .aload(0)
                        .invokestatic(ClassDesc.ofInternalName("sun/swing/SwingUtilities2"),
                                "putAATextInfo",
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean, ConstantDescs.CD_Map))
                        .return_());
    }

    static FieldTransform finalFlagRemover() {
        return ((builder, element) -> {
            if (element instanceof AccessFlags flags) {
                builder.with(AccessFlags.ofField(flags.flagsMask() ^ Modifier.FINAL));
                return;
            }
            builder.with(element);
        });
    }

    public static MethodTransform enableAnyTypePermissionXStream() {
        return MethodTransform.transformingCode((builder, element) -> {
            if (element instanceof FieldInstruction fieldInstruction && fieldInstruction.name().equalsString("xStream")) {
                // xStream.addPermission(AnyTypePermission.ANY);
                builder.with(fieldInstruction);

                ClassDesc xstreamClass = ClassDesc.of("com.thoughtworks.xstream.XStream");
                ClassDesc typePermissionClass = ClassDesc.of("com.thoughtworks.xstream.security.TypePermission");

                builder.getstatic(ClassDesc.of("com.alee.utils.XmlUtils"), "xStream", xstreamClass);
                builder.getstatic(ClassDesc.of("com.thoughtworks.xstream.security.AnyTypePermission"), "ANY", typePermissionClass);
                builder.invokevirtual(xstreamClass, "addPermission", MethodTypeDesc.of(ConstantDescs.CD_void, typePermissionClass));
                return;
            }
            builder.with(element);
        });
    }
}
